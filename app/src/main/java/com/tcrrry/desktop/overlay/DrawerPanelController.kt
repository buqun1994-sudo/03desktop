package com.tcrrry.desktop.overlay

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Rect
import com.tcrrry.desktop.R
import com.tcrrry.desktop.apps.AppCatalogCoordinator
import com.tcrrry.desktop.apps.AppCatalogRepository
import com.tcrrry.desktop.apps.AppDragController
import com.tcrrry.desktop.apps.AppGridAdapter
import com.tcrrry.desktop.apps.AppOrderStore
import com.tcrrry.desktop.apps.IconCache
import com.tcrrry.desktop.system.SystemActionLauncher

class DrawerPanelController(
    private val context: Context,
    private val requestClose: ((() -> Unit)?) -> Unit,
) {
    private var coordinator: AppCatalogCoordinator? = null
    private var dragController: AppDragController? = null
    private var adapter: AppGridAdapter? = null
    private var iconCache: IconCache? = null
    private var actionLauncher: SystemActionLauncher? = null
    private var panelView: View? = null
    private var closingForAction = false

    fun createPanelView(): View {
        @Suppress("InflateParams")
        val root = LayoutInflater.from(context).inflate(R.layout.overlay_drawer_panel, null, false)
        val grid = root.findViewById<RecyclerView>(R.id.app_grid)
        val emptyView = root.findViewById<TextView>(R.id.app_grid_empty)
        val loadingView = root.findViewById<View>(R.id.app_grid_loading)
        val actionSwitcher = root.findViewById<ViewAnimator>(R.id.drawer_action_switcher)
        val uninstallTarget = root.findViewById<View>(R.id.uninstall_target)
        val actionLauncher = SystemActionLauncher(context)
        this.actionLauncher?.release()
        this.actionLauncher = actionLauncher
        val iconCache = IconCache(context)
        this.iconCache = iconCache
        emptyView.visibility = View.VISIBLE

        grid.layoutManager = GridLayoutManager(context, 4)
        grid.addItemDecoration(
            object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State,
                ) {
                    val position = parent.getChildAdapterPosition(view)
                    if (position == RecyclerView.NO_POSITION) return
                    outRect.top = APP_ITEM_VERTICAL_OFFSET_PX
                    outRect.bottom = APP_ITEM_VERTICAL_OFFSET_PX
                }
            },
        )
        val nextAdapter = AppGridAdapter(iconCache) { entry ->
            runAfterClose {
                actionLauncher.launchApp(entry)
            }
        }
        grid.adapter = nextAdapter
        adapter = nextAdapter

        val nextCoordinator = AppCatalogCoordinator(
            repository = AppCatalogRepository(context),
            orderStore = AppOrderStore(context),
            onEntriesChanged = { entries ->
                nextAdapter.submitEntries(entries)
                emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            },
            onLoadingChanged = { loading ->
                loadingView.visibility = if (loading) View.VISIBLE else View.GONE
            },
        )
        coordinator = nextCoordinator
        dragController = AppDragController(
            recyclerView = grid,
            adapter = nextAdapter,
            actionSwitcher = actionSwitcher,
            uninstallTarget = uninstallTarget,
            onDragStateChanged = nextCoordinator::setDragging,
            onOrderCommitted = nextCoordinator::persistOrder,
            onUninstallRequested = { entry ->
                runAfterClose {
                    actionLauncher.requestUninstall(entry)
                }
            },
        )
        root.findViewById<View>(R.id.install_apk_button).setOnClickListener {
            runAfterClose { actionLauncher.launchApkInstall() }
        }
        nextCoordinator.start()
        panelView = root
        return root
    }

    fun onPanelRemoved() {
        actionLauncher?.release()
        actionLauncher = null
        dragController?.detach()
        dragController = null
        adapter?.release()
        adapter = null
        iconCache = null
        coordinator?.stop()
        coordinator = null
        panelView = null
        closingForAction = false
    }

    fun onPackageDirty() {
        coordinator?.onPackageDirty()
    }

    fun onTrimMemory(level: Int) {
        iconCache?.onTrimMemory(level)
    }

    private fun runAfterClose(action: () -> Unit) {
        if (closingForAction) return
        closingForAction = true
        requestClose {
            closingForAction = false
            action()
        }
    }

    private companion object {
        const val APP_ITEM_VERTICAL_OFFSET_PX = 11
    }
}
