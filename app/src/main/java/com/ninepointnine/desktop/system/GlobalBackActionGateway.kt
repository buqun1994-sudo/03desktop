package com.ninepointnine.desktop.system

object GlobalBackActionGateway {
    fun interface Executor {
        fun performBack(): Boolean
    }

    @Volatile
    private var executor: Executor? = null

    fun attach(candidate: Executor) {
        executor = candidate
    }

    fun detach(candidate: Executor) {
        if (executor === candidate) executor = null
    }

    fun performBack(): Boolean = executor?.performBack() == true
}
