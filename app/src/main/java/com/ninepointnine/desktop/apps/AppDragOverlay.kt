package com.ninepointnine.desktop.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/** Only the selected item leaves the clipped grid; the real cell stays in the sorting layout. */
internal class AppDragOverlay(
    private val host: ViewGroup,
    private val recyclerView: RecyclerView,
) {
    private val gridBounds = Rect()
    private var hiddenItem: View? = null
    private var originalAlpha = 1f
    private var shadow: ImageView? = null

    fun show(itemView: View): View {
        clear()
        val bitmap = Bitmap.createBitmap(itemView.width, itemView.height, Bitmap.Config.ARGB_8888)
        itemView.draw(Canvas(bitmap))
        val nextShadow = ImageView(host.context).apply {
            setImageBitmap(bitmap)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        originalAlpha = itemView.alpha
        hiddenItem = itemView
        shadow = nextShadow
        host.overlay.add(nextShadow)
        move(itemView, itemView.translationX, itemView.translationY)
        itemView.alpha = 0f
        return nextShadow
    }

    fun move(itemView: View, dX: Float, dY: Float) {
        val currentShadow = shadow ?: return
        gridBounds.set(0, 0, recyclerView.width, recyclerView.height)
        // Coordinates stay in the panel's design space, inheriting its runtime screen scale once.
        host.offsetDescendantRectToMyCoords(recyclerView, gridBounds)
        val left = gridBounds.left + itemView.left
        val top = gridBounds.top + itemView.top
        currentShadow.layout(left, top, left + itemView.width, top + itemView.height)
        currentShadow.translationX = dX
        currentShadow.translationY = dY
    }

    fun clear() {
        shadow?.let {
            it.animate().cancel()
            host.overlay.remove(it)
            it.setImageDrawable(null)
        }
        shadow = null
        hiddenItem?.alpha = originalAlpha
        hiddenItem = null
    }
}
