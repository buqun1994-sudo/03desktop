package com.ninepointnine.desktop.overlay

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.ninepointnine.desktop.R
import com.ninepointnine.desktop.apps.IconCache
import com.ninepointnine.desktop.model.AppEntry
import com.ninepointnine.desktop.model.AppIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MoreAppAdapter(
    private val iconCache: IconCache,
    private val selectable: Boolean,
    private val onSelectionChanged: () -> Unit = {},
) : RecyclerView.Adapter<MoreAppAdapter.ViewHolder>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var entries: List<AppEntry> = emptyList()
    private val selectedIdentities = linkedSetOf<AppIdentity>()

    fun submitEntries(nextEntries: List<AppEntry>) {
        if (entries == nextEntries) return
        val previous = entries
        val difference = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = nextEntries.size
            override fun areItemsTheSame(old: Int, next: Int) = previous[old].identity == nextEntries[next].identity
            override fun areContentsTheSame(old: Int, next: Int) = previous[old] == nextEntries[next]
        })
        entries = nextEntries.toList()
        selectedIdentities.retainAll(nextEntries.mapTo(hashSetOf(), AppEntry::identity))
        difference.dispatchUpdatesTo(this)
    }

    fun selectedEntries(): List<AppEntry> = entries.filter { it.identity in selectedIdentities }

    fun clearSelection() {
        if (selectedIdentities.isEmpty()) return
        selectedIdentities.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun release() {
        scope.cancel()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_more_app, parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = entries.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.more_app_icon)
        private val label = view.findViewById<TextView>(R.id.more_app_label)
        private val check = view.findViewById<CheckBox>(R.id.more_app_check)
        private var iconJob: Job? = null
        private var boundIconKey: String? = null

        fun bind(entry: AppEntry) {
            boundIconKey = entry.iconKey
            iconJob?.cancel()
            icon.setImageBitmap(null)
            label.text = entry.label
            check.visibility = if (selectable) View.VISIBLE else View.GONE
            check.isChecked = entry.identity in selectedIdentities
            itemView.setOnClickListener(if (selectable) {
                View.OnClickListener {
                    if (selectedIdentities.add(entry.identity).not()) selectedIdentities.remove(entry.identity)
                    check.isChecked = entry.identity in selectedIdentities
                    onSelectionChanged()
                }
            } else {
                null
            })
            itemView.isClickable = selectable
            itemView.isFocusable = selectable
            iconJob = scope.launch {
                val bitmap = try {
                    withContext(Dispatchers.IO) { iconCache.get(entry) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: RuntimeException) {
                    null
                }
                if (boundIconKey == entry.iconKey) setIcon(bitmap)
            }
        }

        private fun setIcon(bitmap: Bitmap?) {
            if (bitmap == null) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon)
            } else {
                icon.setImageBitmap(bitmap)
            }
        }

        fun recycle() {
            iconJob?.cancel()
            iconJob = null
            boundIconKey = null
            icon.setImageBitmap(null)
            itemView.setOnClickListener(null)
        }
    }
}
