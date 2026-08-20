package com.ninepointnine.desktop.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalBackActionGatewayTest {
    @Test
    fun `attached executor handles back until detached`() {
        var calls = 0
        val executor = GlobalBackActionGateway.Executor {
            calls += 1
            true
        }

        GlobalBackActionGateway.attach(executor)
        try {
            assertTrue(GlobalBackActionGateway.performBack())
            assertTrue(calls == 1)
        } finally {
            GlobalBackActionGateway.detach(executor)
        }

        assertFalse(GlobalBackActionGateway.performBack())
    }

    @Test
    fun `detaching stale executor keeps current executor active`() {
        val stale = GlobalBackActionGateway.Executor { false }
        val current = GlobalBackActionGateway.Executor { true }

        GlobalBackActionGateway.attach(stale)
        GlobalBackActionGateway.attach(current)
        GlobalBackActionGateway.detach(stale)
        try {
            assertTrue(GlobalBackActionGateway.performBack())
        } finally {
            GlobalBackActionGateway.detach(current)
        }
    }
}
