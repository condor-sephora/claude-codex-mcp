package security

import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class OutputRedactorTest {

    @Test
    fun `redacts OpenAI API key`() {
        val input = "Key is sk-abcdefghijklmnopqrstuvwxyz1234567890 and done"
        val result = OutputRedactor.redact(input)
        assertFalse(result.contains("sk-abcdefghij"), "OpenAI key should be redacted")
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `redacts GitHub personal access token ghp_`() {
        val input = "GITHUB_TOKEN=ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ123456789012"
        val result = OutputRedactor.redact(input)
        assertFalse(result.contains("ghp_aBcDe"), "GitHub token should be redacted")
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `redacts GitHub OAuth token gho_`() {
        val input = "token: gho_abcdefghijklmnopqrstuvwxyz1234567890ab"
        val result = OutputRedactor.redact(input)
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `redacts Bearer token`() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abc.def"
        val result = OutputRedactor.redact(input)
        assertTrue(result.contains("[REDACTED]"))
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
    }

    @Test
    fun `redacts AWS access key`() {
        val input = "aws_access_key_id: AKIAIOSFODNN7EXAMPLE"
        val result = OutputRedactor.redact(input)
        assertTrue(result.contains("[REDACTED]"))
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"))
    }

    @Test
    fun `redacts TOKEN=value pattern`() {
        val input = "API_TOKEN=super-secret-value-that-is-long"
        val result = OutputRedactor.redact(input)
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `redacts SECRET=value pattern`() {
        val input = "DB_SECRET=mysupersecretpassword123"
        val result = OutputRedactor.redact(input)
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `redacts PASSWORD=value pattern`() {
        val input = "DB_PASSWORD=hunter2isNotSecure"
        val result = OutputRedactor.redact(input)
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `redacts API_KEY=value pattern`() {
        val input = "THIRD_PARTY_API_KEY=some-api-key-value-here"
        val result = OutputRedactor.redact(input)
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `redacts ACCESS_KEY=value pattern`() {
        val input = "AWS_ACCESS_KEY=AKIDEXAMPLEKEY12345"
        val result = OutputRedactor.redact(input)
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `leaves non-secret text unchanged`() {
        val input = "The build completed successfully in 3.2 seconds."
        val result = OutputRedactor.redact(input)
        assertEquals(input, result)
    }

    @Test
    fun `handles empty string`() {
        val result = OutputRedactor.redact("")
        assertEquals("", result)
    }

    @Test
    fun `handles blank string`() {
        val result = OutputRedactor.redact("   ")
        assertEquals("   ", result)
    }

    @Test
    fun `boundAndRedact truncates and redacts`() {
        val secret = "sk-12345678901234567890"
        val (result, truncated) = OutputRedactor.boundAndRedact("$secret extra content", 10)
        assertTrue(truncated, "Should report truncated")
        assertFalse(result.contains("sk-1234"), "Secret should be redacted even after truncation")
    }

    @Test
    fun `boundAndRedact returns false for truncated when within limit`() {
        val input = "short text"
        val (result, truncated) = OutputRedactor.boundAndRedact(input, 100)
        assertFalse(truncated)
        assertEquals(input, result)
    }

    @Test
    fun `redaction is idempotent`() {
        val input = "sk-aaabbbcccdddeee111222333444555"
        val once = OutputRedactor.redact(input)
        val twice = OutputRedactor.redact(once)
        assertEquals(once, twice)
    }
}
