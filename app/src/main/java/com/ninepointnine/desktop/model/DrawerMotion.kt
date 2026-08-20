package com.ninepointnine.desktop.model

enum class DrawerDock {
    CLOSED,
    OPEN,
}

enum class GestureOrigin {
    CLOSED_TRIGGER,
    OPEN_TRIGGER,
}

data class DrawerMotion(
    val openDistancePx: Int,
    val stableDock: DrawerDock,
) {
    init {
        require(openDistancePx in MIN_OPEN_DISTANCE_PX..MAX_OPEN_DISTANCE_PX)
    }

    companion object {
        const val MIN_OPEN_DISTANCE_PX = 0
        const val MAX_OPEN_DISTANCE_PX = 610

        fun at(dock: DrawerDock): DrawerMotion =
            DrawerMotion(
                openDistancePx = if (dock == DrawerDock.OPEN) MAX_OPEN_DISTANCE_PX else MIN_OPEN_DISTANCE_PX,
                stableDock = dock,
            )
    }
}
