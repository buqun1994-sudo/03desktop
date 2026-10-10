package com.ninepointnine.desktop.overlay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ninepointnine.desktop.R
import com.ninepointnine.desktop.apps.IconCache
import com.ninepointnine.desktop.model.AppEntry

/** A page inside the existing drawer window; data comes from its catalog coordinator. */
internal class MorePageController(
    private val host: ViewGroup,
    iconCache: IconCache,
    private val onRestore: (List<AppEntry>) -> Unit,
    private val onInstall: () -> Unit,
) {
    private enum class Page { ROOT, REMOVED_APPS, AUTOSTART_APPS }
    private val context = host.context
    private val content = LayoutInflater.from(context).inflate(R.layout.more_sheet, host, false)
    private val list = content.findViewById<RecyclerView>(R.id.more_list)
    private val empty = content.findViewById<TextView>(R.id.more_empty)
    private val action = content.findViewById<TextView>(R.id.more_primary_action)
    private val title = content.findViewById<TextView>(R.id.more_page_title)
    private val menuAdapter = MoreMenuAdapter(::onMenuItemClicked)
    private val hiddenAdapter = MoreAppAdapter(iconCache, selectable = true, onSelectionChanged = ::updateAction)
    private val autostartAdapter = MoreAppAdapter(iconCache, selectable = false)
    private var hiddenEntries: List<AppEntry> = emptyList()
    private var autostartEntries: List<AppEntry> = emptyList()
    private var page = Page.ROOT
    private var closing = false

    init {
        host.addView(content)
        content.visibility = View.GONE
        content.isClickable = true
        list.layoutManager = LinearLayoutManager(context)
        content.findViewById<ImageButton>(R.id.more_back_button).setOnClickListener {
            if (page == Page.ROOT) close() else render(Page.ROOT)
        }
        action.setOnClickListener {
            val selected = hiddenAdapter.selectedEntries()
            if (selected.isNotEmpty()) onRestore(selected)
        }
    }

    fun show() {
        if (content.visibility == View.VISIBLE) return
        closing = false
        hiddenAdapter.clearSelection()
        render(Page.ROOT)
        content.visibility = View.VISIBLE
        content.translationY = host.height.toFloat()
        content.animate().translationY(0f).setDuration(DrawerGeometry.SETTLE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    fun updateEntries(hidden: List<AppEntry>, autostart: List<AppEntry>) {
        hiddenEntries = hidden
        autostartEntries = autostart
        if (content.visibility == View.VISIBLE) render(page)
    }

    fun reset() {
        content.animate().cancel()
        content.animate().withEndAction(null)
        content.visibility = View.GONE
        content.translationY = 0f
        closing = false
        hiddenAdapter.clearSelection()
    }

    fun release() {
        reset()
        list.adapter = null
        hiddenAdapter.release()
        autostartAdapter.release()
    }

    private fun close() {
        if (closing) return
        closing = true
        content.animate().translationY(host.height.toFloat()).setDuration(DrawerGeometry.SETTLE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator(1.5f)).withEndAction { reset() }.start()
    }

    private fun render(nextPage: Page) {
        page = nextPage
        content.findViewById<View>(R.id.more_page_hint).visibility =
            if (page == Page.AUTOSTART_APPS) View.VISIBLE else View.GONE
        list.visibility = View.VISIBLE
        empty.visibility = View.GONE
        action.visibility = if (page == Page.REMOVED_APPS) View.VISIBLE else View.GONE
        when (page) {
            Page.ROOT -> {
                title.setText(R.string.more_title)
                if (list.adapter !== menuAdapter) list.adapter = menuAdapter
                menuAdapter.submitItems(listOf(
                    MoreMenuItem(MoreMenuItem.Id.REMOVED_APPS,
                        context.getString(R.string.more_removed_apps, hiddenEntries.size), R.drawable.ic_more_removed_apps),
                    MoreMenuItem(MoreMenuItem.Id.AUTOSTART_APPS,
                        context.getString(R.string.more_autostart_apps), R.drawable.ic_more_autostart),
                    MoreMenuItem(MoreMenuItem.Id.INSTALL_APK,
                        context.getString(R.string.install_apk), R.drawable.ic_install_apk),
                    MoreMenuItem(MoreMenuItem.Id.SETTINGS,
                        context.getString(R.string.more_settings), R.drawable.ic_more_settings, enabled = false),
                ))
            }
            Page.REMOVED_APPS -> {
                title.setText(R.string.more_removed_apps_title)
                if (list.adapter !== hiddenAdapter) list.adapter = hiddenAdapter
                hiddenAdapter.submitEntries(hiddenEntries)
                updateAction()
                showEmpty(hiddenEntries, R.string.more_removed_apps_empty)
            }
            Page.AUTOSTART_APPS -> {
                title.setText(R.string.more_autostart_apps_title)
                if (list.adapter !== autostartAdapter) list.adapter = autostartAdapter
                autostartAdapter.submitEntries(autostartEntries)
                showEmpty(autostartEntries, R.string.more_autostart_apps_empty)
            }
        }
    }

    private fun updateAction() {
        action.isEnabled = hiddenAdapter.selectedEntries().isNotEmpty()
        action.alpha = if (action.isEnabled) 1f else 0.38f
    }

    private fun showEmpty(entries: List<AppEntry>, message: Int) {
        if (entries.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            empty.setText(message)
        }
    }

    private fun onMenuItemClicked(id: MoreMenuItem.Id) {
        when (id) {
            MoreMenuItem.Id.REMOVED_APPS -> {
                hiddenAdapter.clearSelection()
                render(Page.REMOVED_APPS)
            }
            MoreMenuItem.Id.AUTOSTART_APPS -> render(Page.AUTOSTART_APPS)
            MoreMenuItem.Id.INSTALL_APK -> { reset(); onInstall() }
            MoreMenuItem.Id.SETTINGS -> Unit
        }
    }
}
