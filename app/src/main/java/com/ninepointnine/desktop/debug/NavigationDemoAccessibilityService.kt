package com.ninepointnine.desktop.debug

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import com.ninepointnine.desktop.input.RemoteInputObservationResolution
import com.ninepointnine.desktop.input.RemoteInputObservedFocus
import com.ninepointnine.desktop.input.RemoteInputObservedWindowType
import com.ninepointnine.desktop.input.RemoteInputTargetResolver
import com.ninepointnine.desktop.input.RemoteInputCoordinator
import com.ninepointnine.desktop.input.RemoteInputFocusMetadata
import com.ninepointnine.desktop.input.RemoteInputRuntime
import com.ninepointnine.desktop.input.RemoteInputTarget
import com.ninepointnine.desktop.input.RemoteKeyboard
import com.ninepointnine.desktop.input.RemoteWindowMetadata
import com.ninepointnine.desktop.system.GlobalBackActionGateway

// Keep the legacy component name so existing accessibility grants survive upgrades.
class NavigationDemoAccessibilityService : AccessibilityService() {
    private val globalBackExecutor = GlobalBackActionGateway.Executor {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
    private lateinit var remoteCoordinator: RemoteInputCoordinator
    private var observationInterrupted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        GlobalBackActionGateway.attach(globalBackExecutor)
        remoteCoordinator = RemoteInputRuntime.coordinator(this)
        RemoteInputRuntime.attachFocusVerifier(this) { inspectWindows().resolution.focused }
        observationInterrupted = false
        remoteCoordinator.connected()
        // One bounded metadata refresh on connection; later updates are event driven.
        val snapshot = inspectWindows()
        remoteCoordinator.event(RemoteWindowMetadata(snapshot.keyboard, snapshot.resolution.target), snapshot.resolution.focused)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::remoteCoordinator.isInitialized || event == null ||
            event.eventType !in OBSERVED_EVENTS
        ) return
        if (observationInterrupted) {
            observationInterrupted = false
            RemoteInputRuntime.attachFocusVerifier(this) { inspectWindows().resolution.focused }
            remoteCoordinator.connected()
        }
        val snapshot = inspectWindows()
        val sourceWindowIsIme = snapshot.windowTypes[event.windowId] == RemoteInputObservedWindowType.IME
        val actualFocus = if (!sourceWindowIsIme && event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val hintedTarget = RemoteInputTarget(event.packageName?.toString().orEmpty(), event.windowId)
            RemoteInputTargetResolver.viewFocus(hintedTarget, focusToken(event, hintedTarget), snapshot.resolution.focused)
        } else null
        remoteCoordinator.event(
            RemoteWindowMetadata(snapshot.keyboard, snapshot.resolution.target),
            actualFocus ?: snapshot.resolution.focused,
        )
    }

    override fun onInterrupt() {
        observationInterrupted = true
        RemoteInputRuntime.detachFocusVerifier(this)
        if (::remoteCoordinator.isInitialized) remoteCoordinator.disconnected()
    }

    override fun onDestroy() {
        RemoteInputRuntime.detachFocusVerifier(this)
        if (::remoteCoordinator.isInitialized) remoteCoordinator.disconnected()
        GlobalBackActionGateway.detach(globalBackExecutor)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun inspectWindows(): WindowSnapshot = runCatching {
        val currentWindows = windows
        if (currentWindows.isNullOrEmpty()) return@runCatching WindowSnapshot.unknown()
        try {
            val types = currentWindows.associate { window ->
                window.id to when (window.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> RemoteInputObservedWindowType.APPLICATION
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> RemoteInputObservedWindowType.IME
                    AccessibilityWindowInfo.TYPE_SYSTEM -> RemoteInputObservedWindowType.SYSTEM
                    else -> RemoteInputObservedWindowType.OTHER
                }
            }
            val candidates = currentWindows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }
                .mapNotNull { window ->
                    val targetPackage = windowPackage(window) ?: return@mapNotNull null
                    RemoteWindowCandidate(RemoteInputTarget(targetPackage, window.id), window.isFocused)
                }.toList()
            val chosen = candidates.firstOrNull { it.focused } ?: candidates.firstOrNull()
            val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
            val resolution = try {
                val rootTarget = activeRoot?.let(::nodeTarget) ?: RemoteInputTarget()
                val inputFocus = if (types[rootTarget.windowId] in setOf(null, RemoteInputObservedWindowType.APPLICATION)) {
                    runCatching { activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                } else null
                try {
                    val freshFocus = inputFocus?.let { node ->
                        val refreshed = node.refresh()
                        RemoteInputObservedFocus(nodeTarget(node), nodeToken(node), node.isFocused, node.isEditable, refreshed)
                    }
                    RemoteInputTargetResolver.resolve(types, chosen?.target ?: RemoteInputTarget(), rootTarget, freshFocus)
                } finally {
                    inputFocus?.recycle()
                }
            } finally {
                activeRoot?.recycle()
            }
            WindowSnapshot(
                keyboard = if (types.values.any { it == RemoteInputObservedWindowType.IME }) RemoteKeyboard.OPEN else RemoteKeyboard.CLOSED,
                resolution = resolution,
                windowTypes = types,
            )
        } finally {
            currentWindows.forEach { runCatching { it.recycle() } }
        }
    }.getOrElse { WindowSnapshot.unknown() }

    @Suppress("DEPRECATION")
    private fun focusToken(event: AccessibilityEvent, expectedTarget: RemoteInputTarget): String? {
        val source = runCatching { event.source }.getOrNull() ?: return null
        return try {
            if (!source.refresh() || !source.isFocused || !source.isEditable || nodeTarget(source) != expectedTarget) null
            else nodeToken(source)
        } catch (_: RuntimeException) {
            null
        } finally {
            source.recycle()
        }
    }

    private fun nodeTarget(node: AccessibilityNodeInfo) = RemoteInputTarget(
        node.packageName?.toString().orEmpty(), node.windowId,
    )

    private fun nodeToken(node: AccessibilityNodeInfo): String? = node.viewIdResourceName
        ?.takeIf(String::isNotBlank)?.let { "${node.hashCode()}:$it" }

    @Suppress("DEPRECATION")
    private fun windowPackage(window: AccessibilityWindowInfo): String? {
        val root = runCatching { window.root }.getOrNull() ?: return null
        return try {
            root.packageName?.toString()?.takeIf(String::isNotBlank)
        } catch (_: RuntimeException) {
            null
        } finally {
            root.recycle()
        }
    }

    private data class RemoteWindowCandidate(
        val target: RemoteInputTarget,
        val focused: Boolean,
    )

    private data class WindowSnapshot(
        val keyboard: RemoteKeyboard,
        val resolution: RemoteInputObservationResolution,
        val windowTypes: Map<Int, RemoteInputObservedWindowType>,
    ) {
        companion object {
            fun unknown() = WindowSnapshot(
                RemoteKeyboard.UNKNOWN, RemoteInputObservationResolution(RemoteInputTarget(), null), emptyMap(),
            )
        }
    }

    private companion object {
        val OBSERVED_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
        )
    }
}
