package com.tcrrry.desktop.install

import com.tcrrry.desktop.model.ApkSource
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ApkScannerTest {
    private data class Node(
        val name: String,
        val directory: Boolean = false,
        val readable: Boolean = true,
        val children: List<Node> = emptyList(),
        val modified: Long = 0L,
    )

    @Test
    fun `breadth first scan keeps apk case insensitively and ignores unreadable branches`() {
        val root = Node(
            name = "root",
            directory = true,
            children = listOf(
                Node("one.APK", modified = 10L),
                Node("note.txt", modified = 20L),
                Node("blocked", directory = true, readable = false),
                Node("nested", directory = true, children = listOf(Node("two.apk", modified = 30L))),
            ),
        )
        val visited = mutableListOf<String>()

        val records = ApkScanRules.breadthFirst(
            roots = listOf(root),
            isDirectory = Node::directory,
            children = { node ->
                visited += node.name
                if (!node.readable) throw SecurityException()
                node.children
            },
            toRecord = { node -> record(node.name, node.modified) },
            checkpoint = {},
        )

        assertEquals(listOf("one.APK", "two.apk"), records.map { it.displayName })
        assertEquals(listOf("root", "blocked", "nested"), visited)
    }

    @Test
    fun `stable result sorts newest first then name and deduplicates uri`() {
        val stable = ApkScanRules.stable(
            listOf(
                record("z.apk", 10L, "same"),
                record("a.apk", 20L, "a"),
                record("duplicate.apk", 30L, "same"),
                record("b.APK", 20L, "b"),
                record("ignore.txt", 40L, "ignore"),
            ),
        )

        assertEquals(listOf("duplicate.apk", "a.apk", "b.APK"), stable.map { it.displayName })
    }

    @Test
    fun `checkpoint cancellation immediately stops traversal`() {
        var checkpoints = 0
        try {
            ApkScanRules.breadthFirst(
                roots = listOf(Node("root", directory = true, children = listOf(Node("a.apk")))),
                isDirectory = Node::directory,
                children = Node::children,
                toRecord = { record(it.name, it.modified) },
                checkpoint = {
                    checkpoints += 1
                    if (checkpoints == 2) throw TestCancellation()
                },
            )
            fail("Expected cancellation")
        } catch (_: TestCancellation) {
            // Expected: cancellation must propagate out of traversal.
        }
        assertEquals(2, checkpoints)
    }

    private fun record(name: String, modified: Long, uri: String = name) = ScannedApkRecord(
        uri = uri,
        displayName = name,
        sizeBytes = 1L,
        lastModified = modified,
        source = ApkSource.DOWNLOADS,
    )

    private class TestCancellation : RuntimeException()
}
