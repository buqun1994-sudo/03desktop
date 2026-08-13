package com.tcrrry.desktop.apps

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.ViewAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.tcrrry.desktop.model.AppEntry

internal fun isDraggedCenterInsideBounds(
    itemLeft: Int,
    itemTop: Int,
    itemWidth: Int,
    itemHeight: Int,
    dX: Float,
    dY: Float,
    targetLeft: Int,
    targetTop: Int,
    targetRight: Int,
    targetBottom: Int,
): Boolean {
    val centerX = itemLeft + itemWidth / 2f + dX
    val centerY = itemTop + itemHeight / 2f + dY
    return centerX >= targetLeft && centerX < targetRight &&
        centerY >= targetTop && centerY < targetBottom
}

class DragSession(initialEntries: List<AppEntry>) {
    private val initialEntries = initialEntries.toList()
    private val workingEntries = initialEntries.toMutableList()

    val hasChanged: Boolean
        get() = workingEntries.map(AppEntry::identity) != initialEntries.map(AppEntry::identity)

    fun move(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in workingEntries.indices || toPosition !in workingEntries.indices || fromPosition == toPosition) return
        val entry = workingEntries.removeAt(fromPosition)
        workingEntries.add(toPosition, entry)
    }

    fun snapshot(): List<AppEntry> = workingEntries.toList()

    fun restore(): List<AppEntry> = initialEntries

    fun finish(
        cancelled: Boolean,
        uninstallHit: Boolean,
        canRequestUninstall: Boolean,
    ): DragResult = when {
        cancelled -> DragResult.Restored(initialEntries)
        uninstallHit && canRequestUninstall -> DragResult.Uninstall(initialEntries)
        hasChanged -> DragResult.Commit(workingEntries.toList())
        else -> DragResult.NoChange
    }
}

sealed class DragResult {
    data class Commit(val entries: List<AppEntry>) : DragResult()
    data class Restored(val entries: List<AppEntry>) : DragResult()
    data class Uninstall(val entries: List<AppEntry>) : DragResult()
    data object NoChange : DragResult()
}

class AppDragController(
    private val recyclerView: RecyclerView,
    private val adapter: AppGridAdapter,
    private val actionSwitcher: ViewAnimator,
    private val uninstallTarget: View,
    private val onDragStateChanged: (Boolean) -> Unit,
    private val onOrderCommitted: (List<AppEntry>) -> Unit,
    private val onUninstallRequested: (AppEntry) -> Unit,
) {
    private var dragSession: DragSession? = null
    private var draggedEntry: AppEntry? = null
    private var uninstallHit = false
    private var gestureCancelled = false
    private val targetBounds = Rect()
    private val touchObserver = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) gestureCancelled = true
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) gestureCancelled = true
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    }

    private val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        0,
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.move(from, to)
            dragSession?.move(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || viewHolder == null || dragSession != null) return
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            dragSession = DragSession(adapter.snapshot())
            draggedEntry = adapter.snapshot().getOrNull(position)
            gestureCancelled = false
            uninstallHit = false
            actionSwitcher.displayedChild = UNINSTALL_ACTION_INDEX
            onDragStateChanged(true)
        }

        override fun onChildDraw(
            canvas: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || !isCurrentlyActive) return
            uninstallHit = isDraggedCenterInsideTarget(viewHolder.itemView, dX, dY)
            uninstallTarget.isActivated = uninstallHit
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            finishDrag()
        }

        override fun isLongPressDragEnabled(): Boolean = true
    })

    init {
        recyclerView.addOnItemTouchListener(touchObserver)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    fun detach() {
        itemTouchHelper.attachToRecyclerView(null)
        recyclerView.removeOnItemTouchListener(touchObserver)
        actionSwitcher.displayedChild = INSTALL_ACTION_INDEX
        uninstallTarget.isActivated = false
    }

    private fun isDraggedCenterInsideTarget(itemView: View, dX: Float, dY: Float): Boolean {
        if (actionSwitcher.displayedChild != UNINSTALL_ACTION_INDEX) return false
        val recyclerLocation = IntArray(2)
        val targetLocation = IntArray(2)
        recyclerView.getLocationInWindow(recyclerLocation)
        uninstallTarget.getLocationInWindow(targetLocation)
        targetBounds.set(
            targetLocation[0],
            targetLocation[1],
            targetLocation[0] + uninstallTarget.width,
            targetLocation[1] + uninstallTarget.height,
        )
        return isDraggedCenterInsideBounds(
            itemLeft = recyclerLocation[0] + itemView.left,
            itemTop = recyclerLocation[1] + itemView.top,
            itemWidth = itemView.width,
            itemHeight = itemView.height,
            dX = dX,
            dY = dY,
            targetLeft = targetBounds.left,
            targetTop = targetBounds.top,
            targetRight = targetBounds.right,
            targetBottom = targetBounds.bottom,
        )
    }

    private fun finishDrag() {
        val session = dragSession ?: return
        val entry = draggedEntry
        dragSession = null
        draggedEntry = null
        actionSwitcher.displayedChild = INSTALL_ACTION_INDEX
        uninstallTarget.isActivated = false
        onDragStateChanged(false)

        when (val result = session.finish(gestureCancelled, uninstallHit, entry?.canRequestUninstall == true)) {
            is DragResult.Commit -> onOrderCommitted(result.entries)
            is DragResult.Restored -> adapter.replaceWithSnapshot(result.entries)
            is DragResult.Uninstall -> {
                adapter.replaceWithSnapshot(result.entries)
                if (entry != null) onUninstallRequested(entry)
            }

            DragResult.NoChange -> Unit
        }
        uninstallHit = false
        gestureCancelled = false
    }

    private companion object {
        const val INSTALL_ACTION_INDEX = 0
        const val UNINSTALL_ACTION_INDEX = 1
    }
}
