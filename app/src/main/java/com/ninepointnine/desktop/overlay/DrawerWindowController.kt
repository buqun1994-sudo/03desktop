package com.ninepointnine.desktop.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.ninepointnine.desktop.R
import com.ninepointnine.desktop.model.DrawerDock
import com.ninepointnine.desktop.model.GestureOrigin
import kotlin.math.roundToInt

class DrawerWindowController(
    private val context: Context,
    private val panelFactory: () -> View,
    private val onPanelRemoved: () -> Unit,
    private val onDesktopSurfaceOccupancyChanged: (Boolean) -> Unit,
    private val onClosedTriggerBackRequested: () -> Boolean,
    private val onClosedTriggerHomeRequested: () -> Boolean,
    private val onWindowFailure: () -> Unit,
) : DrawerGestureController.Listener {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var geometry = readDisplayGeometry()
    private lateinit var triggerHandleView: View
    private val triggerView = createTriggerView(context)
    private val triggerLayoutParams = createTriggerLayoutParams()
    private val panelLayoutParams = createPanelLayoutParams()
    private val frameScheduler = Choreographer.getInstance()
    private val occupancyTracker = DrawerSurfaceOccupancyTracker(onDesktopSurfaceOccupancyChanged)
    private val gestureController = DrawerGestureController(
        touchSlopPx = touchSlopPx,
        longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
        postDelayed = { callback, delay -> triggerView.postDelayed(callback, delay) },
        removeCallbacks = { triggerView.removeCallbacks(it) },
        geometryProvider = { geometry },
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
        geometry = readDisplayGeometry()
        syncTriggerGeometry()
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
        gestureController.cancel()
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
        gestureController.cancel()
        val previousMaxOpenDistancePx = geometry.maxOpenDistancePx
        val openProgress = if (previousMaxOpenDistancePx == 0) {
            0f
        } else {
            openDistancePx.toFloat() / previousMaxOpenDistancePx
        }
        val previousPanelState = panelWindowState ?: PanelWindowState.PARKED
        removeTrigger()
        removePanel()
        geometry = readDisplayGeometry()
        syncTriggerGeometry()
        openDistancePx = (openProgress * geometry.maxOpenDistancePx).roundToInt()
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

    override fun onClosedTriggerLongPressed() {
        if (onClosedTriggerHomeRequested()) playNavigationFeedback()
    }

    private fun animateTo(destination: DrawerDock) {
        animator?.cancel()
        val start = openDistancePx
        val end = if (destination == DrawerDock.OPEN) geometry.maxOpenDistancePx else 0
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
        renderDistance(if (destination == DrawerDock.OPEN) geometry.maxOpenDistancePx else 0)
        if (destination == DrawerDock.OPEN) {
            applyPanelWindowState(PanelWindowState.OPEN)
        } else {
            applyPanelWindowState(PanelWindowState.PARKED)
            occupancyTracker.onParked(openDistancePx)
            completePendingAfterClose()
        }
    }

    private fun renderDistance(distancePx: Int) {
        openDistancePx = geometry.clampOpenDistance(distancePx)
        val translationX = geometry.panelTranslationX(openDistancePx)
        panelContentView?.let { contentView ->
            if (contentView.translationX != translationX) contentView.translationX = translationX
        }
        scheduleTriggerPosition()
    }

    private fun scheduleTriggerPosition() {
        if (frameScheduled || !triggerAttached ||
            triggerLayoutParams.x == geometry.triggerX(openDistancePx)
        ) {
            return
        }
        frameScheduled = true
        frameScheduler.postFrameCallback {
            frameScheduled = false
            if (!triggerAttached) return@postFrameCallback
            val targetX = geometry.triggerX(openDistancePx)
            if (triggerLayoutParams.x == targetX) return@postFrameCallback
            triggerLayoutParams.x = targetX
            safely { windowManager.updateViewLayout(triggerView, triggerLayoutParams) }
        }
    }

    private fun ensureTriggerAttached() {
        if (triggerAttached) return
        triggerLayoutParams.x = geometry.triggerX(openDistancePx)
        safely {
            windowManager.addView(triggerView, triggerLayoutParams)
            triggerAttached = true
        }
    }

    private fun ensurePanelAttached() {
        if (panelAttached) return
        configurePanelLayoutParams(PanelWindowState.PARKED)
        val translationX = geometry.panelTranslationX(openDistancePx)
        val contentView: View
        val windowView = try {
            contentView = panelFactory().apply {
                pivotX = 0f
                pivotY = 0f
                scaleX = geometry.scale
                scaleY = geometry.scale
                this.translationX = translationX
            }
            FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                clipChildren = true
                clipToPadding = true
                addView(
                    contentView,
                    FrameLayout.LayoutParams(
                        DrawerGeometry.DESIGN_PANEL_WIDTH_PX,
                        DrawerGeometry.DESIGN_PANEL_HEIGHT_PX,
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
        gestureController.cancel()
        if (!triggerAttached) return
        triggerAttached = false
        triggerHandleView.removeCallbacks(resetNavigationFeedback)
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
            geometry.screenWidthPx
        } else {
            geometry.panelX
        }
        panelLayoutParams.y = geometry.panelY
        panelLayoutParams.width = if (state == PanelWindowState.MOVING) {
            geometry.panelMotionWidthPx
        } else {
            geometry.panelWidthPx
        }
        panelLayoutParams.height = geometry.panelHeightPx
        panelLayoutParams.flags = panelFlags(notTouchable = state != PanelWindowState.OPEN)
    }

    private fun handlePanelTouch(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_OUTSIDE) return false
        if (stableDock == DrawerDock.OPEN &&
            !geometry.isPointInsideTrigger(event.rawX, event.rawY, openDistancePx)
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
                    pivotX = 0f
                    pivotY = 0f
                    scaleX = geometry.scale
                    scaleY = geometry.scale
                },
                FrameLayout.LayoutParams(
                    DrawerGeometry.DESIGN_HANDLE_WIDTH_PX,
                    DrawerGeometry.DESIGN_HANDLE_HEIGHT_PX,
                ).apply {
                    leftMargin = geometry.handleLeftPx
                    topMargin = geometry.handleTopPx
                },
            )
            setOnClickListener {
                if (onClosedTriggerBackRequested()) playNavigationFeedback()
            }
            setOnTouchListener { _, event: MotionEvent ->
                if (pendingAfterClose == null) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> gestureController.onDown(event.rawX, event.rawY)
                        MotionEvent.ACTION_MOVE -> gestureController.onMove(event.rawX, event.rawY)
                        MotionEvent.ACTION_UP -> gestureController.onUp(event.rawX, event.rawY)
                        MotionEvent.ACTION_CANCEL,
                        MotionEvent.ACTION_POINTER_DOWN,
                        -> gestureController.onCancel()
                    }
                }
                true
            }
        }
    }

    private fun playNavigationFeedback() {
        triggerHandleView.removeCallbacks(resetNavigationFeedback)
        triggerHandleView.background = context.getDrawable(R.drawable.bg_drawer_handle_active)
        triggerHandleView.postDelayed(resetNavigationFeedback, NAVIGATION_FEEDBACK_DURATION_MS)
    }

    private fun createTriggerLayoutParams() = WindowManager.LayoutParams(
        geometry.triggerWidthPx,
        geometry.triggerHeightPx,
        overlayWindowType(),
        triggerFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = geometry.closedTriggerX
        y = geometry.triggerY
    }

    private fun createPanelLayoutParams() = WindowManager.LayoutParams(
        geometry.panelWidthPx,
        geometry.panelHeightPx,
        overlayWindowType(),
        panelFlags(notTouchable = true),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = geometry.screenWidthPx
        y = geometry.panelY
    }

    private fun syncTriggerGeometry() {
        val handleLayoutParams = triggerHandleView.layoutParams as FrameLayout.LayoutParams
        handleLayoutParams.width = DrawerGeometry.DESIGN_HANDLE_WIDTH_PX
        handleLayoutParams.height = DrawerGeometry.DESIGN_HANDLE_HEIGHT_PX
        handleLayoutParams.leftMargin = geometry.handleLeftPx
        handleLayoutParams.topMargin = geometry.handleTopPx
        triggerHandleView.layoutParams = handleLayoutParams
        triggerHandleView.scaleX = geometry.scale
        triggerHandleView.scaleY = geometry.scale
        triggerLayoutParams.width = geometry.triggerWidthPx
        triggerLayoutParams.height = geometry.triggerHeightPx
        triggerLayoutParams.y = geometry.triggerY
    }

    @Suppress("DEPRECATION")
    private fun readDisplayGeometry(): DrawerGeometry.Spec {
        val realSize = Point()
        windowManager.defaultDisplay.getRealSize(realSize)
        val widthPx = if (realSize.x > 0) realSize.x else context.resources.displayMetrics.widthPixels
        val heightPx = if (realSize.y > 0) realSize.y else context.resources.displayMetrics.heightPixels
        return DrawerGeometry.forDisplay(widthPx, heightPx)
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

    private val resetNavigationFeedback = Runnable { refreshHandleBackground() }

    private companion object {
        const val NAVIGATION_FEEDBACK_DURATION_MS = 180L
    }

    private enum class PanelWindowState {
        PARKED,
        MOVING,
        OPEN,
    }
}
