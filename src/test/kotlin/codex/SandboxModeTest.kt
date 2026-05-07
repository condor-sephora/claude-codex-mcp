package codex

import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class SandboxModeTest {

    @Test
    fun `parses read-only`() {
        assertEquals(SandboxMode.READ_ONLY, SandboxMode.fromString("read-only"))
    }

    @Test
    fun `parses workspace-write`() {
        assertEquals(SandboxMode.WORKSPACE_WRITE, SandboxMode.fromString("workspace-write"))
    }

    @Test
    fun `parses danger-full-access`() {
        assertEquals(SandboxMode.DANGER_FULL_ACCESS, SandboxMode.fromString("danger-full-access"))
    }

    @Test
    fun `parsing is case-insensitive`() {
        assertEquals(SandboxMode.READ_ONLY, SandboxMode.fromString("READ-ONLY"))
        assertEquals(SandboxMode.WORKSPACE_WRITE, SandboxMode.fromString("Workspace-Write"))
    }

    @Test
    fun `returns null for unknown value`() {
        assertNull(SandboxMode.fromString("unknown-mode"))
    }

    @Test
    fun `returns null for null input`() {
        assertNull(SandboxMode.fromString(null))
    }

    @Test
    fun `default is read-only`() {
        assertEquals(SandboxMode.READ_ONLY, SandboxMode.default())
    }
}
