package com.ninepointnine.desktop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputTargetResolverTest {
    private val app = RemoteInputTarget("target.app", 7)
    private val ime = RemoteInputTarget("ime.app", 128)

    @Test
    fun `omitted application window is resolved from fresh root and field`() {
        val result = resolve(
            types = mapOf(128 to RemoteInputObservedWindowType.IME, 600 to RemoteInputObservedWindowType.APPLICATION),
            root = app,
            field = focus(),
        )
        assertEquals(app, result.target)
        assertEquals(RemoteInputFocusMetadata(app, "field-1"), result.focused)
    }

    @Test
    fun `known application root can supply a target without a chosen window`() {
        val result = resolve(
            types = mapOf(7 to RemoteInputObservedWindowType.APPLICATION),
            root = app,
            field = focus(),
        )
        assertEquals(app, result.target)
        assertEquals(RemoteInputFocusMetadata(app, "field-1"), result.focused)
    }

    @Test
    fun `root only never creates a missing target`() {
        assertUnknown(resolve(root = app))
        assertUnknown(resolve(types = mapOf(7 to RemoteInputObservedWindowType.APPLICATION), root = app))
    }

    @Test
    fun `missing window rejects incomplete fresh field proof`() {
        listOf(
            focus(refreshed = false),
            focus(focused = false),
            focus(editable = false),
            focus(token = null),
            focus(token = ""),
            focus(token = " "),
        ).forEach { field ->
            assertUnknown(resolve(root = app, field = field))
        }
    }

    @Test
    fun `root and field must match package and window identity`() {
        listOf(
            RemoteInputTarget("other.app", 7),
            RemoteInputTarget(app.packageName, 8),
        ).forEach { other ->
            assertUnknown(resolve(root = app, field = focus(target = other)))
        }
    }

    @Test
    fun `unknown root or field cannot create a missing target`() {
        listOf(
            RemoteInputTarget(),
            RemoteInputTarget("", 7),
            RemoteInputTarget(app.packageName, -1),
        ).forEach { unknown ->
            assertUnknown(resolve(root = unknown, field = focus(target = unknown)))
            assertUnknown(resolve(root = app, field = focus(target = unknown)))
        }
    }

    @Test
    fun `known ime system and other roots cannot supply a missing target`() {
        listOf(
            RemoteInputObservedWindowType.IME,
            RemoteInputObservedWindowType.SYSTEM,
            RemoteInputObservedWindowType.OTHER,
        ).forEach { type ->
            assertUnknown(resolve(
                types = mapOf(7 to type),
                root = app,
                field = focus(),
            ))
        }
    }

    @Test
    fun `normal chosen application preserves window only semantics`() {
        val result = resolve(
            types = mapOf(7 to RemoteInputObservedWindowType.APPLICATION),
            chosen = app,
        )
        assertEquals(app, result.target)
        assertNull(result.focused)

        listOf(
            focus(refreshed = false),
            focus(focused = false),
            focus(editable = false),
            focus(token = null),
        ).forEach { field ->
            val unverified = resolve(chosen = app, root = app, field = field)
            assertEquals(app, unverified.target)
            assertNull(unverified.focused)
        }
    }

    @Test
    fun `normal application survives ime root without an ime field witness`() {
        val types = mapOf(
            7 to RemoteInputObservedWindowType.APPLICATION,
            128 to RemoteInputObservedWindowType.IME,
        )
        val result = resolve(types = types, chosen = app, root = ime, field = focus(target = ime))
        assertEquals(app, result.target)
        assertNull(result.focused)

        val applicationFocus = resolve(types = types, chosen = app, root = ime, field = focus())
        assertEquals(app, applicationFocus.target)
        assertEquals(RemoteInputFocusMetadata(app, "field-1"), applicationFocus.focused)
    }

    @Test
    fun `normal chosen window rejects conflicting non ime root or window type`() {
        val other = RemoteInputTarget("other.app", 8)
        listOf(
            emptyMap(),
            mapOf(8 to RemoteInputObservedWindowType.APPLICATION),
            mapOf(8 to RemoteInputObservedWindowType.SYSTEM),
            mapOf(8 to RemoteInputObservedWindowType.OTHER),
        ).forEach { types ->
            assertUnknown(resolve(types = types, chosen = app, root = other))
        }

        listOf(
            RemoteInputObservedWindowType.IME,
            RemoteInputObservedWindowType.SYSTEM,
            RemoteInputObservedWindowType.OTHER,
        ).forEach { type ->
            assertUnknown(resolve(types = mapOf(7 to type), chosen = app, root = app, field = focus()))
        }

        assertUnknown(resolve(
            chosen = app,
            root = app,
            field = focus(target = RemoteInputTarget(app.packageName, 8)),
        ))
    }

    @Test
    fun `fresh field witness is returned for every observation`() {
        val first = resolve(root = app, field = focus())
        val duplicate = resolve(root = app, field = focus())
        val next = resolve(root = app, field = focus(token = "field-2"))

        assertEquals(RemoteInputFocusMetadata(app, "field-1"), first.focused)
        assertEquals(first, duplicate)
        assertEquals(app, next.target)
        assertEquals(RemoteInputFocusMetadata(app, "field-2"), next.focused)
    }

    @Test
    fun `later observation never reuses preceding target without proof`() {
        assertEquals(app, resolve(root = app, field = focus()).target)
        assertUnknown(resolve())
        assertUnknown(resolve(root = app))
    }

    @Test
    fun `coordinator detects same app field change on a window observation`() {
        val owner = coordinator()
        owner.connected()
        val first = resolve(root = app, field = focus())
        owner.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, first.target), first.focused)
        val epoch = owner.state().epoch
        val id = "00000000-0000-0000-0000-000000000001"
        assertTrue(owner.begin(RemoteInputBegin(id, epoch, "text")).accepted)

        val next = resolve(root = app, field = focus(token = "field-2"))
        owner.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, next.target), next.focused)

        assertEquals("focus_changed", owner.result(id).getNow(null)?.reason)
        assertTrue(owner.state().epoch > epoch)
        assertEquals(app, owner.state().target)
    }

    @Test
    fun `coordinator preserves frozen request across ime frames and fresh same field`() {
        val owner = coordinator()
        owner.connected()
        val first = resolve(root = app, field = focus())
        owner.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, first.target), first.focused)
        val id = "00000000-0000-0000-0000-000000000001"
        assertTrue(owner.begin(RemoteInputBegin(id, owner.state().epoch, "text")).accepted)

        val imeFrame = resolve(
            types = mapOf(128 to RemoteInputObservedWindowType.IME),
            chosen = app,
            root = ime,
            field = focus(target = ime),
        )
        owner.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, imeFrame.target), imeFrame.focused)
        val missing = resolve()
        owner.event(RemoteWindowMetadata(RemoteKeyboard.CLOSED, missing.target), missing.focused)
        assertTrue(owner.state().sending)
        assertNull(owner.tryCommit(app.packageName, true, { true }, { first.focused }) { true })

        val restored = resolve(root = app, field = focus())
        owner.event(RemoteWindowMetadata(RemoteKeyboard.OPEN, restored.target), restored.focused)
        assertTrue(owner.state().sending)
        assertTrue(owner.tryCommit(app.packageName, true, { true }, { restored.focused }) { it == "text" }!!.committed)
    }

    private fun resolve(
        types: Map<Int, RemoteInputObservedWindowType> = emptyMap(),
        chosen: RemoteInputTarget = RemoteInputTarget(),
        root: RemoteInputTarget = RemoteInputTarget(),
        field: RemoteInputObservedFocus? = null,
    ) = RemoteInputTargetResolver.resolve(types, chosen, root, field)

    private fun focus(
        target: RemoteInputTarget = app,
        token: String? = "field-1",
        focused: Boolean = true,
        editable: Boolean = true,
        refreshed: Boolean = true,
    ) = RemoteInputObservedFocus(target, token, focused, editable, refreshed)

    private fun coordinator() = RemoteInputCoordinator(
        imeDeclared = true,
        nowMillis = { 0L },
        deadlines = RemoteInputDeadlineScheduler { _, _ -> RemoteInputCancellation { } },
    )

    private fun assertUnknown(result: RemoteInputObservationResolution) {
        assertEquals(RemoteInputTarget(), result.target)
        assertNull(result.focused)
    }
}
