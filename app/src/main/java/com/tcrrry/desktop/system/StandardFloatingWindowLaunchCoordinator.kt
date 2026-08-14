package com.tcrrry.desktop.system

class StandardFloatingWindowLaunchCoordinator(
    private val gateway: Gateway,
    private val onFailure: (Failure) -> Unit,
) {
    enum class TargetKind {
        REGULAR,
        STANDARD_FLOATING_WINDOW,
        UNAVAILABLE,
    }

    sealed class WindowModeRead {
        data class Value(val value: Int) : WindowModeRead()
        data object Unavailable : WindowModeRead()
    }

    enum class Failure {
        TARGET_UNAVAILABLE,
        SYSTEM_ACTION_UNAVAILABLE,
        WINDOW_STATE_UNAVAILABLE,
        HOME_UNAVAILABLE,
        TRANSITION_TIMEOUT,
    }

    interface Cancellable {
        fun cancel()
    }

    interface WindowModeObservation : Cancellable {
        fun start(): Boolean
    }

    interface Gateway {
        fun targetKind(componentName: String): TargetKind
        fun readWindowMode(): WindowModeRead
        fun startHome(): Boolean
        fun startTarget(componentName: String): Boolean
        fun createWindowModeObservation(onChanged: () -> Unit): WindowModeObservation
        fun scheduleTimeout(delayMillis: Long, onTimeout: () -> Unit): Cancellable?
    }

    private data class PendingLaunch(
        val startTarget: () -> Boolean,
        val targetFailure: Failure,
        var result: Boolean? = null,
    )

    private enum class StandardWindowOccupancy {
        CLEAR,
        OCCUPIED,
        UNKNOWN,
    }

    private var pendingLaunch: PendingLaunch? = null
    private var observation: WindowModeObservation? = null
    private var timeout: Cancellable? = null

    fun launch(componentName: String): Boolean {
        if (pendingLaunch != null) return false

        return when (gateway.targetKind(componentName)) {
            TargetKind.REGULAR -> startTarget(Failure.TARGET_UNAVAILABLE) {
                gateway.startTarget(componentName)
            }
            TargetKind.UNAVAILABLE -> fail(Failure.TARGET_UNAVAILABLE)
            TargetKind.STANDARD_FLOATING_WINDOW -> launchAfterClearingStandardWindow(
                targetFailure = Failure.TARGET_UNAVAILABLE,
                startTarget = { gateway.startTarget(componentName) },
            )
        }
    }

    fun launchExclusive(
        targetFailure: Failure = Failure.TARGET_UNAVAILABLE,
        startTarget: () -> Boolean,
    ): Boolean {
        if (pendingLaunch != null) return false
        return launchAfterClearingStandardWindow(targetFailure, startTarget)
    }

    fun cancel() {
        clearPending()
    }

    fun hasPendingLaunch(): Boolean = pendingLaunch != null

    private fun launchAfterClearingStandardWindow(
        targetFailure: Failure,
        startTarget: () -> Boolean,
    ): Boolean =
        when (val mode = gateway.readWindowMode()) {
            WindowModeRead.Unavailable -> fail(Failure.WINDOW_STATE_UNAVAILABLE)
            is WindowModeRead.Value -> when (standardWindowOccupancy(mode.value)) {
                StandardWindowOccupancy.CLEAR -> startTarget(targetFailure, startTarget)
                StandardWindowOccupancy.OCCUPIED -> {
                    transitionThroughHome(targetFailure, startTarget)
                }

                StandardWindowOccupancy.UNKNOWN -> fail(Failure.WINDOW_STATE_UNAVAILABLE)
            }
        }

    private fun transitionThroughHome(
        targetFailure: Failure,
        startTarget: () -> Boolean,
    ): Boolean {
        val request = PendingLaunch(startTarget, targetFailure)
        pendingLaunch = request
        val nextObservation = gateway.createWindowModeObservation {
            handleObservedWindowMode(request)
        }
        observation = nextObservation
        if (!nextObservation.start()) {
            return failPending(request, Failure.WINDOW_STATE_UNAVAILABLE)
        }

        timeout = gateway.scheduleTimeout(TRANSITION_TIMEOUT_MS) {
            if (pendingLaunch === request) failPending(request, Failure.TRANSITION_TIMEOUT)
        }
        if (timeout == null) return failPending(request, Failure.WINDOW_STATE_UNAVAILABLE)

        val homeStarted = gateway.startHome()
        if (pendingLaunch !== request) return request.result ?: false
        if (!homeStarted) return failPending(request, Failure.HOME_UNAVAILABLE)

        // Re-read once after HOME to close the register-before-launch race without polling.
        return if (pendingLaunch === request) {
            handleObservedWindowMode(request)
        } else {
            request.result ?: false
        }
    }

    private fun handleObservedWindowMode(request: PendingLaunch): Boolean {
        if (pendingLaunch !== request) return request.result ?: false
        return when (val mode = gateway.readWindowMode()) {
            WindowModeRead.Unavailable -> failPending(request, Failure.WINDOW_STATE_UNAVAILABLE)
            is WindowModeRead.Value -> when (standardWindowOccupancy(mode.value)) {
                StandardWindowOccupancy.CLEAR -> {
                    clearPending(request)
                    startTarget(request.targetFailure, request.startTarget).also { request.result = it }
                }

                StandardWindowOccupancy.OCCUPIED -> true
                StandardWindowOccupancy.UNKNOWN -> {
                    failPending(request, Failure.WINDOW_STATE_UNAVAILABLE)
                }
            }
        }
    }

    private fun standardWindowOccupancy(windowMode: Int): StandardWindowOccupancy =
        when (windowMode) {
            WINDOW_MODE_NONE,
            WINDOW_MODE_ADAS_CARD,
            -> StandardWindowOccupancy.CLEAR

            WINDOW_MODE_STANDARD_FLOATING_WINDOW,
            WINDOW_MODE_ADAS_CARD_AND_STANDARD_FLOATING_WINDOW,
            -> StandardWindowOccupancy.OCCUPIED

            else -> StandardWindowOccupancy.UNKNOWN
        }

    private fun startTarget(targetFailure: Failure, startTarget: () -> Boolean): Boolean {
        if (startTarget()) return true
        return fail(targetFailure)
    }

    private fun failPending(request: PendingLaunch, failure: Failure): Boolean {
        clearPending(request)
        return fail(failure).also { request.result = it }
    }

    private fun fail(failure: Failure): Boolean {
        onFailure(failure)
        return false
    }

    private fun clearPending() {
        pendingLaunch = null
        observation?.cancel()
        observation = null
        timeout?.cancel()
        timeout = null
    }

    private fun clearPending(request: PendingLaunch) {
        if (pendingLaunch === request) clearPending()
    }

    companion object {
        const val STANDARD_FLOATING_WINDOW_METADATA =
            "com.tcrrry.icar.window.STANDARD_FLOATING_WINDOW"
        const val WINDOW_MODE_SETTING =
            "com.mengbo.launcher3.settings.secure.window_mode"
        const val TRANSITION_TIMEOUT_MS = 3_000L

        private const val WINDOW_MODE_NONE = 0
        private const val WINDOW_MODE_ADAS_CARD = 1
        private const val WINDOW_MODE_STANDARD_FLOATING_WINDOW = 2
        private const val WINDOW_MODE_ADAS_CARD_AND_STANDARD_FLOATING_WINDOW = 3
    }
}
