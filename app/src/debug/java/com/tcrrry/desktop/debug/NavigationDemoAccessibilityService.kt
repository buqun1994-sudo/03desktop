package com.tcrrry.desktop.debug

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.tcrrry.desktop.R
import com.tcrrry.desktop.system.GlobalBackActionGateway

class NavigationDemoAccessibilityService : AccessibilityService() {
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val globalBackExecutor = GlobalBackActionGateway.Executor {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
    private var navigationBar: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        GlobalBackActionGateway.attach(globalBackExecutor)
        if (THREE_KEY_NAVIGATION_ENABLED) showNavigationBar()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        GlobalBackActionGateway.detach(globalBackExecutor)
        navigationBar?.let { windowManager.removeView(it) }
        navigationBar = null
        super.onDestroy()
    }

    private fun showNavigationBar() {
        if (navigationBar != null) return

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(76, 0, 0, 0))
            addNavigationButton(NavigationAction.BACK, R.string.navigation_demo_back)
            addNavigationButton(NavigationAction.HOME, R.string.navigation_demo_home)
            addNavigationButton(NavigationAction.RECENTS, R.string.navigation_demo_recents)
        }
        val params = WindowManager.LayoutParams(
            NAVIGATION_BAR_WIDTH_PX,
            NAVIGATION_BAR_HEIGHT_PX,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        windowManager.addView(bar, params)
        navigationBar = bar
    }

    private fun LinearLayout.addNavigationButton(
        action: NavigationAction,
        descriptionRes: Int,
    ) {
        addView(
            ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, NAVIGATION_BAR_HEIGHT_PX, 1f)
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
                    null,
                    null,
                )
                contentDescription = getString(descriptionRes)
                tooltipText = contentDescription
                setImageDrawable(NavigationIconDrawable(action))
                scaleType = ImageView.ScaleType.CENTER
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    if (!performGlobalAction(action.globalAction)) {
                        Toast.makeText(
                            this@NavigationDemoAccessibilityService,
                            R.string.navigation_demo_unavailable,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    private enum class NavigationAction(val globalAction: Int) {
        BACK(GLOBAL_ACTION_BACK),
        HOME(GLOBAL_ACTION_HOME),
        RECENTS(GLOBAL_ACTION_RECENTS),
    }

    private class NavigationIconDrawable(
        private val action: NavigationAction,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = ICON_STROKE_WIDTH_PX
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val centerX = bounds.exactCenterX()
            val centerY = bounds.exactCenterY()
            when (action) {
                NavigationAction.BACK -> {
                    paint.style = Paint.Style.FILL
                    path.reset()
                    path.moveTo(centerX + ICON_RADIUS_PX, centerY - ICON_RADIUS_PX)
                    path.lineTo(centerX - ICON_RADIUS_PX, centerY)
                    path.lineTo(centerX + ICON_RADIUS_PX, centerY + ICON_RADIUS_PX)
                    path.close()
                    canvas.drawPath(path, paint)
                }

                NavigationAction.HOME -> {
                    paint.style = Paint.Style.STROKE
                    canvas.drawCircle(centerX, centerY, ICON_RADIUS_PX, paint)
                }

                NavigationAction.RECENTS -> {
                    paint.style = Paint.Style.STROKE
                    canvas.drawRect(
                        centerX - ICON_RADIUS_PX,
                        centerY - ICON_RADIUS_PX,
                        centerX + ICON_RADIUS_PX,
                        centerY + ICON_RADIUS_PX,
                        paint,
                    )
                }
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = ICON_SIZE_PX

        override fun getIntrinsicHeight(): Int = ICON_SIZE_PX
    }

    private companion object {
        // 三大金刚键暂不使用，保留实现供后续场景重新评估。
        const val THREE_KEY_NAVIGATION_ENABLED = false
        const val NAVIGATION_BAR_WIDTH_PX = 240
        const val NAVIGATION_BAR_HEIGHT_PX = 30
        const val ICON_SIZE_PX = 18
        const val ICON_RADIUS_PX = 6.5f
        const val ICON_STROKE_WIDTH_PX = 2f
    }
}
