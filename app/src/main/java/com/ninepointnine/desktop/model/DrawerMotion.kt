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
    val maxOpenDistancePx: Int,
) {
    init {
        require(maxOpenDistancePx >= MIN_OPEN_DISTANCE_PX)
        require(openDistancePx in MIN_OPEN_DISTANCE_PX..maxOpenDistancePx)
    }

    companion object {
        const val MIN_OPEN_DISTANCE_PX = 0

        fun at(
            dock: DrawerDock,
            maxOpenDistancePx: Int,
        ): DrawerMotion =
            DrawerMotion(
                openDistancePx = if (dock == DrawerDock.OPEN) maxOpenDistancePx else MIN_OPEN_DISTANCE_PX,
                stableDock = dock,
                maxOpenDistancePx = maxOpenDistancePx,
            )
    }
}
