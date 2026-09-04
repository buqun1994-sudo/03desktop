package com.ninepointnine.desktop.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppCatalogBridgeWireCodecTest {
    @Test
    fun `wire codec preserves unicode labels and the versioned digest contract`() {
        val entry = bridgeEntry(
            packageName = "com.example.music",
            displayName = "QQ音乐",
        )

        val wire = AppCatalogBridgeWireCodec.encode(entry)

        assertEquals("UVHpn7PkuZA", wire.encodedDisplayName)
        assertEquals("MTIuMy4w", wire.encodedVersionName)
        assertEquals(
            "fc01beeca66d3a2fc77f5ddc3e49530935e7cedd965dc027910259348bcf08dd",
            AppCatalogBridgeWireCodec.batchDigest(listOf(wire)),
        )
    }

    @Test
    fun `batch digest is order independent and changes with application metadata`() {
        val first = AppCatalogBridgeWireCodec.encode(
            bridgeEntry(packageName = "com.example.first", displayName = "第一个"),
        )
        val second = AppCatalogBridgeWireCodec.encode(
            bridgeEntry(packageName = "com.example.second", displayName = "第二个"),
        )

        val digest = AppCatalogBridgeWireCodec.batchDigest(listOf(first, second))

        assertEquals(digest, AppCatalogBridgeWireCodec.batchDigest(listOf(second, first)))
        assertNotEquals(
            digest,
            AppCatalogBridgeWireCodec.batchDigest(
                listOf(first, AppCatalogBridgeWireCodec.encode(second.source.copy(versionCode = 124L))),
            ),
        )
    }

    private fun bridgeEntry(
        packageName: String,
        displayName: String,
    ): AppCatalogBridgeEntry = AppCatalogBridgeEntry(
        packageName = packageName,
        displayName = displayName,
        versionName = "12.3.0",
        versionCode = 123L,
        firstInstallTime = 1_000L,
        lastUpdateTime = 2_000L,
        uid = 10_123,
        launcherComponent = "$packageName/$packageName.MainActivity",
    )
}
