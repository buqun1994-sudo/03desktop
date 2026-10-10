package com.ninepointnine.desktop.overlay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ninepointnine.desktop.R

internal data class MoreMenuItem(
    val id: Id,
    val label: String,
    val iconResId: Int,
    val enabled: Boolean = true,
) {
    enum class Id {
        REMOVED_APPS,
        AUTOSTART_APPS,
        INSTALL_APK,
        SETTINGS,
    }
}

internal class MoreMenuAdapter(
    private val onItemClicked: (MoreMenuItem.Id) -> Unit,
) : RecyclerView.Adapter<MoreMenuAdapter.ViewHolder>() {
    private var items: List<MoreMenuItem> = emptyList()

    fun submitItems(nextItems: List<MoreMenuItem>) {
        if (items == nextItems) return
        items = nextItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_more_menu, parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.more_menu_icon)
        private val label = view.findViewById<TextView>(R.id.more_menu_label)

        fun bind(item: MoreMenuItem) {
            icon.setImageResource(item.iconResId)
            label.text = item.label
            itemView.isEnabled = item.enabled
            itemView.setOnClickListener(if (item.enabled) {
                View.OnClickListener { onItemClicked(item.id) }
            } else {
                null
            })
            val alpha = if (item.enabled) 1f else 0.38f
            icon.alpha = alpha
            label.alpha = alpha
            itemView.isClickable = item.enabled
            itemView.isFocusable = item.enabled
        }
    }
}
