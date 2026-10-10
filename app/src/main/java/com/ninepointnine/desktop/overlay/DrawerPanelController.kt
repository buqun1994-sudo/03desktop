package com.ninepointnine.desktop.overlay

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ninepointnine.desktop.R
import com.ninepointnine.desktop.apps.AppCatalogCoordinator
import com.ninepointnine.desktop.apps.AppCatalogRepository
import com.ninepointnine.desktop.apps.AppDragController
import com.ninepointnine.desktop.apps.AppGridAdapter
import com.ninepointnine.desktop.apps.AppOrderStore
import com.ninepointnine.desktop.apps.AutostartStore
import com.ninepointnine.desktop.apps.AutostartCatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.ninepointnine.desktop.model.AppEntry
import com.ninepointnine.desktop.apps.AppVisibilityStore
import com.ninepointnine.desktop.apps.IconCache
import com.ninepointnine.desktop.system.SystemActionLauncher

class DrawerPanelController(
    private val context: Context,
    private val requestClose: ((() -> Unit)?) -> Unit,
) {
    private var coordinator: AppCatalogCoordinator? = null
    private var dragController: AppDragController? = null
    private var adapter: AppGridAdapter? = null
    private var iconCache: IconCache? = null
    private var actionLauncher: SystemActionLauncher? = null
    private var moreAction: View? = null
    private var panelView: View? = null
    private var closingForAction = false
    private var morePage: MorePageController? = null
    private var stopAutostartObserver: (() -> Unit)? = null
    private var autostartEntries: List<AppEntry> = emptyList()
    private var pageScope: CoroutineScope? = null
    private var autostartJob: Job? = null
    private var hiddenEntries: List<AppEntry> = emptyList()
    private val autostartStore = AutostartStore(context)

    fun createPanelView(): View {
        @Suppress("InflateParams")
        val root = LayoutInflater.from(context).inflate(R.layout.overlay_drawer_panel, null, false)
        val grid = root.findViewById<RecyclerView>(R.id.app_grid)
        val emptyView = root.findViewById<TextView>(R.id.app_grid_empty)
        val loadingView = root.findViewById<View>(R.id.app_grid_loading)
        val actionSwitcher = root.findViewById<ViewAnimator>(R.id.drawer_action_switcher)
        val moreAction = root.findViewById<View>(R.id.more_action)
        val removeFromDesktopTarget = root.findViewById<View>(R.id.remove_from_desktop_target)
        val uninstallTarget = root.findViewById<View>(R.id.uninstall_target)
        val actionLauncher = SystemActionLauncher(context)
        this.actionLauncher?.release()
        this.actionLauncher = actionLauncher
        this.moreAction = moreAction
        val iconCache = IconCache(context)
        this.iconCache = iconCache
        emptyView.visibility = View.VISIBLE
        val appItemVerticalOffsetPx = context.resources.getDimensionPixelSize(
            R.dimen.drawer_app_item_vertical_offset,
        )

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
                    outRect.top = appItemVerticalOffsetPx
                    outRect.bottom = appItemVerticalOffsetPx
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

        morePage = MorePageController(
            root as ViewGroup,
            iconCache,
            onRestore = { entries -> coordinator?.restoreHiddenEntries(entries) },
            onInstall = { runAfterClose { actionLauncher.launchApkInstaller() } },
        )
        pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        stopAutostartObserver = autostartStore.observeChanges(::refreshAutostartEntries)
        refreshAutostartEntries()
        val nextCoordinator = AppCatalogCoordinator(
            repository = AppCatalogRepository(context),
            orderStore = AppOrderStore(context),
            visibilityStore = AppVisibilityStore(context),
            onEntriesChanged = { entries ->
                nextAdapter.submitEntries(entries)
                emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            },
            onHiddenEntriesChanged = { entries ->
                hiddenEntries = entries
                updateMoreEntries()
            },
            onLoadingChanged = { loading ->
                loadingView.visibility = if (loading) View.VISIBLE else View.GONE
            },
        )
        coordinator = nextCoordinator
        dragController = AppDragController(
            recyclerView = grid,
            adapter = nextAdapter,
            dragOverlayHost = root,
            actionSwitcher = actionSwitcher,
            removeFromDesktopTarget = removeFromDesktopTarget,
            uninstallTarget = uninstallTarget,
            onDragStateChanged = nextCoordinator::setDragging,
            onOrderCommitted = nextCoordinator::persistOrder,
            onRemoveFromDesktopRequested = nextCoordinator::hideEntry,
            onUninstallRequested = { entry ->
                runAfterClose {
                    actionLauncher.requestUninstall(entry)
                }
            },
        )
        moreAction.setOnClickListener {
            updateMoreEntries()
            morePage?.show()
        }
        nextCoordinator.start()
        panelView = root
        return root
    }

    private fun updateMoreEntries() {
        morePage?.updateEntries(hiddenEntries, autostartEntries)
    }

    private fun refreshAutostartEntries() {
        autostartJob?.cancel()
        autostartJob = pageScope?.launch {
            try {
                autostartEntries = AutostartCatalogRepository(context).load()
                updateMoreEntries()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                android.util.Log.w("DrawerPanel", "Autostart display refresh failed")
            }
        }
    }

    fun onDrawerClosed() {
        morePage?.reset()
    }

    fun onPanelRemoved() {
        stopAutostartObserver?.invoke()
        stopAutostartObserver = null
        morePage?.release()
        morePage = null
        autostartJob?.cancel()
        autostartJob = null
        pageScope?.cancel()
        pageScope = null
        autostartEntries = emptyList()
        hiddenEntries = emptyList()
        actionLauncher?.release()
        actionLauncher = null
        moreAction = null
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
        refreshAutostartEntries()
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
}
