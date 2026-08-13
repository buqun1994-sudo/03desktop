package com.tcrrry.desktop.apps

import com.tcrrry.desktop.model.AppEntry
import com.tcrrry.desktop.model.AppIdentity

object AppOrderReconciler {
    fun reconcile(
        storedOrder: List<AppIdentity>,
        currentEntries: List<AppEntry>,
    ): List<AppEntry> {
        val currentByIdentity = currentEntries.associateBy { it.identity }
        val retained = linkedSetOf<AppIdentity>()
        val result = ArrayList<AppEntry>(currentEntries.size)

        storedOrder.forEach { identity ->
            val entry = currentByIdentity[identity] ?: return@forEach
            if (retained.add(identity)) result += entry
        }
        currentEntries.forEach { entry ->
            if (retained.add(entry.identity)) result += entry
        }
        return result
    }
}
