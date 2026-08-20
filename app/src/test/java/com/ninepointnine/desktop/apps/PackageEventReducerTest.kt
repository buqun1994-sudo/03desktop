package com.ninepointnine.desktop.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageEventReducerTest {
    @Test
    fun `coalesces replacement broadcasts into one refresh after 500 ms`() {
        val reducer = PackageEventReducer()
        reducer.markDirty(10L)
        reducer.markDirty(100L)
        assertFalse(reducer.consumeRefreshDue(599L))
        assertTrue(reducer.consumeRefreshDue(600L))
        assertFalse(reducer.consumeRefreshDue(601L))
    }

    @Test
    fun `delays refresh while drag is active then releases it`() {
        val reducer = PackageEventReducer()
        reducer.markDirty(0L)
        reducer.setDragging(true)
        assertFalse(reducer.consumeRefreshDue(1_000L))
        reducer.setDragging(false)
        assertTrue(reducer.consumeRefreshDue(1_000L))
    }
}
