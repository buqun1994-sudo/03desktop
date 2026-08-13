package com.tcrrry.desktop.install

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.tcrrry.desktop.R
import com.tcrrry.desktop.model.ApkEntry
import java.text.DateFormat
import java.util.Date

class ApkListAdapter(
    private val onEntryClicked: (ApkEntry) -> Unit,
) : RecyclerView.Adapter<ApkListAdapter.ApkViewHolder>() {
    private val entries = mutableListOf<ApkEntry>()

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApkViewHolder =
        ApkViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_apk, parent, false))

    override fun onBindViewHolder(holder: ApkViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    override fun getItemId(position: Int): Long = entries[position].contentUri.toString().hashCode().toLong()

    fun submitEntries(nextEntries: List<ApkEntry>) {
        val previousEntries = entries.toList()
        val difference = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousEntries.size
                override fun getNewListSize(): Int = nextEntries.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousEntries[oldItemPosition].contentUri == nextEntries[newItemPosition].contentUri

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousEntries[oldItemPosition] == nextEntries[newItemPosition]
            },
        )
        entries.clear()
        entries.addAll(nextEntries)
        difference.dispatchUpdatesTo(this)
    }

    inner class ApkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.apk_name)
        private val meta = view.findViewById<TextView>(R.id.apk_meta)

        fun bind(entry: ApkEntry) {
            name.text = entry.displayName
            meta.text = itemView.context.getString(
                R.string.apk_meta,
                formatBytes(entry.sizeBytes),
                DateFormat.getDateTimeInstance().format(Date(entry.lastModified)),
            )
            itemView.setOnClickListener { onEntryClicked(entry) }
        }
    }

    private fun formatBytes(sizeBytes: Long): String = when {
        sizeBytes >= 1024L * 1024L -> "%.1f MB".format(sizeBytes / (1024f * 1024f))
        sizeBytes >= 1024L -> "%.0f KB".format(sizeBytes / 1024f)
        else -> "$sizeBytes B"
    }
}
