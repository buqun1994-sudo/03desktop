package com.ninepointnine.desktop.input

enum class RemoteInputObservedWindowType { APPLICATION, IME, SYSTEM, OTHER }

data class RemoteInputObservedFocus(
    val target: RemoteInputTarget,
    val token: String?,
    val focused: Boolean,
    val editable: Boolean,
    val refreshed: Boolean,
)

data class RemoteInputObservationResolution(
    val target: RemoteInputTarget,
    val focused: RemoteInputFocusMetadata?,
)

/** Resolves one observation without retaining a previous target or platform objects. */
object RemoteInputTargetResolver {
    fun viewFocus(
        target: RemoteInputTarget,
        sourceToken: String?,
        currentWitness: RemoteInputFocusMetadata?,
    ): RemoteInputFocusMetadata = RemoteInputFocusMetadata(
        target,
        sourceToken.takeUnless {
            currentWitness != null && (currentWitness.target != target || currentWitness.token != sourceToken)
        },
    )

    fun resolve(
        windowTypes: Map<Int, RemoteInputObservedWindowType>,
        windowTarget: RemoteInputTarget,
        activeRoot: RemoteInputTarget,
        inputFocus: RemoteInputObservedFocus?,
    ): RemoteInputObservationResolution {
        val freshFocus = inputFocus?.takeIf {
            it.target.isKnown && it.refreshed && it.focused && it.editable &&
                !it.token.isNullOrBlank() && allowsApplication(windowTypes, it.target)
        }?.let { RemoteInputFocusMetadata(it.target, it.token) }

        if (windowTarget.isKnown) {
            if (!allowsApplication(windowTypes, windowTarget)) return unknown()
            val rootIsIme = windowTypes[activeRoot.windowId] == RemoteInputObservedWindowType.IME
            if (activeRoot.isKnown && activeRoot != windowTarget && !rootIsIme) return unknown()
            if (freshFocus != null && freshFocus.target != windowTarget) return unknown()
            return RemoteInputObservationResolution(windowTarget, freshFocus)
        }

        if (!activeRoot.isKnown || !allowsApplication(windowTypes, activeRoot)) return unknown()
        if (freshFocus?.target != activeRoot) return unknown()
        return RemoteInputObservationResolution(activeRoot, freshFocus)
    }

    private fun allowsApplication(
        windowTypes: Map<Int, RemoteInputObservedWindowType>,
        target: RemoteInputTarget,
    ): Boolean = when (windowTypes[target.windowId]) {
        null, RemoteInputObservedWindowType.APPLICATION -> true
        else -> false
    }

    private fun unknown() = RemoteInputObservationResolution(RemoteInputTarget(), null)
}
