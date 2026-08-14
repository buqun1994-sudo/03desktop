package com.tcrrry.desktop.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.tcrrry.desktop.R
import com.tcrrry.desktop.model.DrawerDock
import com.tcrrry.desktop.model.GestureOrigin
import com.tcrrry.desktop.model.DrawerMotion

class DrawerWindowController(
    private val context: Context,
    private val panelFactory: () -> View,
    private val onPanelRemoved: () -> Unit,
    private val onDesktopSurfaceOccupancyChanged: (Boolean) -> Unit,
    private val onClosedTriggerBackRequested: () -> Boolean,
    private val onWindowFailure: () -> Unit,
) : DrawerGestureController.Listener {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val touchSlopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private lateinit var triggerHandleView: View
    private val triggerView = createTriggerView(context)
    private val triggerLayoutParams = createTriggerLayoutParams()
    private val panelLayoutParams = createPanelLayoutParams()
    private val frameScheduler = Choreographer.getInstance()
    private val occupancyTracker = DrawerSurfaceOccupancyTracker(onDesktopSurfaceOccupancyChanged)
    private val gestureController = DrawerGestureController(
        touchSlopPx = touchSlopPx,
        motionProvider = { DrawerGestureController.MotionSnapshot(openDistancePx, stableDock) },
        listener = this,
    )

    private var triggerAttached = false
    private var panelAttached = false
    private var panelWindowView: View? = null
    private var panelContentView: View? = null
    private var panelWindowState: PanelWindowState? = null
    private var frameScheduled = false
    private var animator: ValueAnimator? = null
    private var openDistancePx = 0
    private var stableDock = DrawerDock.CLOSED
    private var pendingAfterClose: (() -> Unit)? = null
    private var releasing = false

    fun showClosedTrigger() {
        stableDock = DrawerDock.CLOSED
        openDistancePx = 0
        ensurePanelAttached()
        if (!panelAttached) return
        renderDistance(0)
        applyPanelWindowState(PanelWindowState.PARKED)
        occupancyTracker.onParked(openDistancePx)
        ensureTriggerAttached()
    }

    fun close(afterClosed: (() -> Unit)? = null) {
        pendingAfterClose = combineAfterClose(pendingAfterClose, afterClosed)
        if (stableDock == DrawerDock.CLOSED && animator == null && openDistancePx == 0) {
            applyPanelWindowState(PanelWindowState.PARKED)
            occupancyTracker.onParked(openDistancePx)
            completePendingAfterClose()
            return
        }
        animateTo(DrawerDock.CLOSED)
    }

    fun isOpen(): Boolean = stableDock == DrawerDock.OPEN

    fun onConfigurationChanged() {
        val previousPanelState = panelWindowState ?: PanelWindowState.PARKED
        removeTrigger()
        removePanel()
        refreshHandleBackground()
        ensurePanelAttached()
        if (!panelAttached) return
        applyPanelWindowState(previousPanelState)
        renderDistance(openDistancePx)
        ensureTriggerAttached()
    }

    override fun onGestureDown(): Boolean {
        val interrupted = animator != null
        animator?.cancel()
        animator = null
        return interrupted
    }

    fun release() {
        if (releasing) return
        releasing = true
        animator?.cancel()
        animator = null
        pendingAfterClose = null
        occupancyTracker.release()
        removeTrigger(allowFailureCallback = false)
        removePanel(allowFailureCallback = false)
        releasing = false
    }

    override fun onHorizontalGestureStarted(origin: GestureOrigin) {
        animator?.cancel()
        animator = null
        if (origin == GestureOrigin.CLOSED_TRIGGER) {
            ensurePanelAttached()
        }
        if (!panelAttached) return
        occupancyTracker.onVisibleMotionStarted()
        applyPanelWindowState(PanelWindowState.MOVING)
    }

    override fun onDistanceChanged(openDistancePx: Int) {
        renderDistance(openDistancePx)
    }

    override fun onSettleRequested(dock: DrawerDock) {
        animateTo(dock)
    }

    override fun onClosedTriggerTapped() {
        triggerView.performClick()
    }

    private fun animateTo(destination: DrawerDock) {
        animator?.cancel()
        val start = openDistancePx
        val end = if (destination == DrawerDock.OPEN) DrawerMotion.MAX_OPEN_DISTANCE_PX else 0
        if (start == end) {
            completeSettle(destination)
            return
        }

        if (destination == DrawerDock.OPEN) {
            ensurePanelAttached()
            if (!panelAttached) return
        }
        if (start > 0 || destination == DrawerDock.OPEN) {
            occupancyTracker.onVisibleMotionStarted()
        }
        applyPanelWindowState(PanelWindowState.MOVING)
        animator = ValueAnimator.ofInt(start, end).apply {
            duration = DrawerGeometry.SETTLE_DURATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { renderDistance(it.animatedValue as Int) }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!cancelled) completeSettle(destination)
                    }
                },
            )
            start()
        }
    }

    private fun completeSettle(destination: DrawerDock) {
        animator = null
        stableDock = destination
        renderDistance(if (destination == DrawerDock.OPEN) DrawerMotion.MAX_OPEN_DISTANCE_PX else 0)
        if (destination == DrawerDock.OPEN) {
            applyPanelWindowState(PanelWindowState.OPEN)
        } else {
            applyPanelWindowState(PanelWindowState.PARKED)
            occupancyTracker.onParked(openDistancePx)
            completePendingAfterClose()
        }
    }

    private fun renderDistance(distancePx: Int) {
        openDistancePx = DrawerGeometry.clampOpenDistance(distancePx)
        val translationX = DrawerGeometry.panelTranslationX(openDistancePx)
        panelContentView?.let { contentView ->
            if (contentView.translationX != translationX) contentView.translationX = translationX
        }
        scheduleTriggerPosition()
    }

    private fun scheduleTriggerPosition() {
        if (frameScheduled || !triggerAttached ||
            triggerLayoutParams.x == DrawerGeometry.triggerX(openDistancePx)
        ) {
            return
        }
        frameScheduled = true
        frameScheduler.postFrameCallback {
            frameScheduled = false
            if (!triggerAttached) return@postFrameCallback
            val targetX = DrawerGeometry.triggerX(openDistancePx)
            if (triggerLayoutParams.x == targetX) return@postFrameCallback
            triggerLayoutParams.x = targetX
            safely { windowManager.updateViewLayout(triggerView, triggerLayoutParams) }
        }
    }

    private fun ensureTriggerAttached() {
        if (triggerAttached) return
        triggerLayoutParams.x = DrawerGeometry.triggerX(openDistancePx)
        safely {
            windowManager.addView(triggerView, triggerLayoutParams)
            triggerAttached = true
        }
    }

    private fun ensurePanelAttached() {
        if (panelAttached) return
        configurePanelLayoutParams(PanelWindowState.PARKED)
        val translationX = DrawerGeometry.panelTranslationX(openDistancePx)
        val contentView: View
        val windowView = try {
            contentView = panelFactory().apply {
                this.translationX = translationX
            }
            FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                clipChildren = true
                clipToPadding = true
                addView(
                    contentView,
                    FrameLayout.LayoutParams(
                        DrawerGeometry.PANEL_WIDTH_PX,
                        DrawerGeometry.PANEL_HEIGHT_PX,
                    ),
                )
                setOnTouchListener { _, event -> handlePanelTouch(event) }
            }
        } catch (_: RuntimeException) {
            releaseDetachedPanelAndStop()
            return
        }
        try {
            windowManager.addView(windowView, panelLayoutParams)
            panelWindowView = windowView
            panelContentView = contentView
            panelAttached = true
            panelWindowState = PanelWindowState.PARKED
        } catch (_: WindowManager.BadTokenException) {
            releaseDetachedPanelAndStop()
        } catch (_: IllegalArgumentException) {
            releaseDetachedPanelAndStop()
        } catch (_: SecurityException) {
            releaseDetachedPanelAndStop()
        }
    }

    private fun removePanel(allowFailureCallback: Boolean = true) {
        val view = panelWindowView ?: return
        panelWindowView = null
        panelContentView = null
        panelAttached = false
        panelWindowState = null
        safely(allowFailureCallback) { windowManager.removeViewImmediate(view) }
        onPanelRemoved()
    }

    private fun removeTrigger(allowFailureCallback: Boolean = true) {
        if (!triggerAttached) return
        triggerAttached = false
        triggerHandleView.removeCallbacks(resetBackClickFeedback)
        safely(allowFailureCallback) { windowManager.removeViewImmediate(triggerView) }
    }

    private fun applyPanelWindowState(state: PanelWindowState) {
        val view = panelWindowView ?: return
        if (panelWindowState == state) return
        configurePanelLayoutParams(state)
        panelWindowState = state
        safely { windowManager.updateViewLayout(view, panelLayoutParams) }
    }

    private fun configurePanelLayoutParams(state: PanelWindowState) {
        panelLayoutParams.x = if (state == PanelWindowState.PARKED) {
            DrawerGeometry.SCREEN_WIDTH_PX
        } else {
            DrawerGeometry.PANEL_X_PX
        }
        panelLayoutParams.y = DrawerGeometry.PANEL_Y_PX
        panelLayoutParams.width = if (state == PanelWindowState.MOVING) {
            DrawerGeometry.PANEL_MOTION_WIDTH_PX
        } else {
            DrawerGeometry.PANEL_WIDTH_PX
        }
        panelLayoutParams.height = DrawerGeometry.PANEL_HEIGHT_PX
        panelLayoutParams.flags = panelFlags(notTouchable = state != PanelWindowState.OPEN)
    }

    private fun handlePanelTouch(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_OUTSIDE) return false
        if (stableDock == DrawerDock.OPEN &&
            !DrawerGeometry.isPointInsideTrigger(event.rawX, event.rawY, openDistancePx)
        ) {
            close()
        }
        return true
    }

    private fun createTriggerView(context: Context): View {
        return FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(
                View(context).apply {
                    refreshHandleBackground(this)
                    triggerHandleView = this
                },
                FrameLayout.LayoutParams(DrawerGeometry.HANDLE_WIDTH_PX, DrawerGeometry.HANDLE_HEIGHT_PX).apply {
                    leftMargin = DrawerGeometry.HANDLE_LEFT_PX
                    topMargin = DrawerGeometry.HANDLE_TOP_PX
                },
            )
            setOnClickListener {
                if (onClosedTriggerBackRequested()) playBackClickFeedback()
            }
            setOnTouchListener { _, event: MotionEvent ->
                if (pendingAfterClose != null) true else gestureController.onTouch(event)
            }
        }
    }

    private fun playBackClickFeedback() {
        triggerHandleView.removeCallbacks(resetBackClickFeedback)
        triggerHandleView.background = context.getDrawable(R.drawable.bg_drawer_handle_active)
        triggerHandleView.postDelayed(resetBackClickFeedback, BACK_FEEDBACK_DURATION_MS)
    }

    private fun createTriggerLayoutParams() = WindowManager.LayoutParams(
        DrawerGeometry.TRIGGER_WIDTH_PX,
        DrawerGeometry.TRIGGER_HEIGHT_PX,
        overlayWindowType(),
        triggerFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = DrawerGeometry.CLOSED_TRIGGER_X_PX
        y = DrawerGeometry.TRIGGER_Y_PX
    }

    private fun createPanelLayoutParams() = WindowManager.LayoutParams(
        DrawerGeometry.PANEL_WIDTH_PX,
        DrawerGeometry.PANEL_HEIGHT_PX,
        overlayWindowType(),
        panelFlags(notTouchable = true),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = DrawerGeometry.SCREEN_WIDTH_PX
        y = DrawerGeometry.PANEL_Y_PX
    }

    private fun overlayWindowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun refreshHandleBackground(view: View = triggerHandleView) {
        view.background = context.getDrawable(R.drawable.bg_drawer_handle)
    }

    private fun triggerFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun panelBaseFlags(): Int =
        triggerFlags() or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    private fun panelFlags(notTouchable: Boolean): Int =
        panelBaseFlags() or if (notTouchable) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0

    private fun safely(
        allowFailureCallback: Boolean = true,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (_: WindowManager.BadTokenException) {
            handleWindowFailure(allowFailureCallback)
        } catch (_: IllegalArgumentException) {
            handleWindowFailure(allowFailureCallback)
        } catch (_: SecurityException) {
            handleWindowFailure(allowFailureCallback)
        }
    }

    private fun handleWindowFailure(allowFailureCallback: Boolean) {
        if (releasing || !allowFailureCallback) return
        release()
        onWindowFailure()
    }

    private fun releaseDetachedPanelAndStop() {
        onPanelRemoved()
        handleWindowFailure(allowFailureCallback = true)
    }

    private fun combineAfterClose(
        existing: (() -> Unit)?,
        next: (() -> Unit)?,
    ): (() -> Unit)? = when {
        existing == null -> next
        next == null -> existing
        else -> {
            { existing.invoke(); next.invoke() }
        }
    }

    private fun completePendingAfterClose() {
        val action = pendingAfterClose
        pendingAfterClose = null
        action?.invoke()
    }

    private val resetBackClickFeedback = Runnable { refreshHandleBackground() }

    private companion object {
        const val BACK_FEEDBACK_DURATION_MS = 180L
    }

    private enum class PanelWindowState {
        PARKED,
        MOVING,
        OPEN,
    }
}
