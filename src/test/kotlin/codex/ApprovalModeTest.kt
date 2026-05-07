package codex

import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class ApprovalModeTest {

    @Test
    fun `parses untrusted`() {
        assertEquals(ApprovalMode.UNTRUSTED, ApprovalMode.fromString("untrusted"))
    }

    @Test
    fun `parses on-request`() {
        assertEquals(ApprovalMode.ON_REQUEST, ApprovalMode.fromString("on-request"))
    }

    @Test
    fun `parses never`() {
        assertEquals(ApprovalMode.NEVER, ApprovalMode.fromString("never"))
    }

    @Test
    fun `parsing is case-insensitive`() {
        assertEquals(ApprovalMode.UNTRUSTED, ApprovalMode.fromString("UNTRUSTED"))
        assertEquals(ApprovalMode.ON_REQUEST, ApprovalMode.fromString("On-Request"))
    }

    @Test
    fun `returns null for unknown value`() {
        assertNull(ApprovalMode.fromString("approve-always"))
    }

    @Test
    fun `returns null for null input`() {
        assertNull(ApprovalMode.fromString(null))
    }

    @Test
    fun `default is untrusted (safest)`() {
        assertEquals(ApprovalMode.UNTRUSTED, ApprovalMode.default())
    }

    @Test
    fun `unsupported warning is non-blank`() {
        assertTrue(ApprovalMode.UNSUPPORTED_WARNING.isNotBlank())
        assertTrue(ApprovalMode.UNSUPPORTED_WARNING.contains("approvalMode"))
    }
}
