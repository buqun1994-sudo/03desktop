package com.tcrrry.desktop.apps

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.tcrrry.desktop.R
import com.tcrrry.desktop.model.AppEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppGridAdapter(
    private val iconCache: IconCache,
    private val onEntryClicked: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppGridAdapter.AppViewHolder>() {
    private val entries = mutableListOf<AppEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
        AppViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false),
        )

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    override fun getItemId(position: Int): Long = entries[position].identity.hashCode().toLong()

    fun submitEntries(nextEntries: List<AppEntry>) {
        val nextByPackage = nextEntries.associateBy(AppEntry::packageName)
        entries.forEach { previous ->
            val next = nextByPackage[previous.packageName]
            if (next == null || next.lastUpdateTime != previous.lastUpdateTime) {
                iconCache.invalidatePackage(previous.packageName)
            }
        }
        replaceEntries(nextEntries)
    }

    fun snapshot(): List<AppEntry> = entries.toList()

    fun move(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in entries.indices || toPosition !in entries.indices || fromPosition == toPosition) return
        val entry = entries.removeAt(fromPosition)
        entries.add(toPosition, entry)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun replaceWithSnapshot(snapshot: List<AppEntry>) {
        replaceEntries(snapshot)
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    private fun replaceEntries(nextEntries: List<AppEntry>) {
        val previousEntries = entries.toList()
        val difference = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousEntries.size
                override fun getNewListSize(): Int = nextEntries.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousEntries[oldItemPosition].identity == nextEntries[newItemPosition].identity

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousEntries[oldItemPosition] == nextEntries[newItemPosition]
            },
        )
        entries.clear()
        entries.addAll(nextEntries)
        difference.dispatchUpdatesTo(this)
    }

    fun release() {
        scope.cancel()
    }

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.app_icon)
        private val label = view.findViewById<TextView>(R.id.app_label)
        private var iconJob: Job? = null
        private var boundIconKey: String? = null

        fun bind(entry: AppEntry) {
            boundIconKey = entry.iconKey
            iconJob?.cancel()
            icon.setImageBitmap(null)
            label.text = entry.label
            itemView.setOnClickListener { onEntryClicked(entry) }
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
