package com.ninepointnine.desktop.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartProxyPolicyTest {
    @Test
    fun `desktop and lyrics identities are never proxy launch targets`() {
        setOf(
            "com.tcrrry.desktop",
            "com.ninepointnine.desktop",
            "com.ninepointnine.desktop.test",
            "com.tcrrry.desktoplyrics",
            "com.ninepointnine.desktoplyrics",
            "com.ninepointnine.desktoplyrics.test",
        ).forEach { packageName ->
            assertFalse(packageName, AutostartProxyPolicy.canProxyLaunch(packageName))
        }

        assertTrue(AutostartProxyPolicy.canProxyLaunch("com.example.player"))
    }

    @Test
    fun `reconciliation removes formerly configured self managed applications`() {
        assertEquals(
            setOf("com.example.player", "com.example.maps"),
            AutostartProxyPolicy.reconcile(
                setOf(
                    "com.ninepointnine.desktop.test",
                    "com.ninepointnine.desktoplyrics.test",
                    "com.example.player",
                    "com.example.maps",
                ),
            ),
        )
    }
}
