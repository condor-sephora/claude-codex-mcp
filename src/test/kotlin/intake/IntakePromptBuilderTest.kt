package intake

import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class IntakePromptBuilderTest {

    private fun makeRequest(
        format: IntakeOutputFormat = IntakeOutputFormat.YAML,
        extra: String? = null,
    ) = IntakeRequest(
        cwd = "/repo",
        requestFilePath = "/repo/.agent-intake/TASK-1/intake-request.md",
        requestFileRelative = ".agent-intake/TASK-1/intake-request.md",
        outputFormat = format,
        extraInstructions = extra,
        timeoutMs = 900_000L,
        taskId = "TASK-1",
    )

    @Test
    fun `prompt begins with generic intake mode line`() {
        val prompt = IntakePromptBuilder.build(makeRequest())
        assertTrue(prompt.startsWith("You are running in generic code intake mode."))
    }

    @Test
    fun `prompt contains the request file relative path`() {
        val prompt = IntakePromptBuilder.build(makeRequest())
        assertTrue(prompt.contains(".agent-intake/TASK-1/intake-request.md"))
    }

    @Test
    fun `prompt instructs Codex not to modify files`() {
        val prompt = IntakePromptBuilder.build(makeRequest())
        assertTrue(
            prompt.contains("Do not modify files") || prompt.contains("read-only"),
            "Prompt must contain a read-only instruction"
        )
    }

    @Test
    fun `prompt instructs separating facts from assumptions`() {
        val prompt = IntakePromptBuilder.build(makeRequest())
        assertTrue(prompt.contains("Separate facts from assumptions"))
    }

    @Test
    fun `yaml prompt contains expected top-level keys`() {
        val prompt = IntakePromptBuilder.build(makeRequest(IntakeOutputFormat.YAML))
        assertTrue(prompt.contains("task_understanding:"))
        assertTrue(prompt.contains("repository_findings:"))
        assertTrue(prompt.contains("existing_tests:"))
        assertTrue(prompt.contains("missing_tests:"))
        assertTrue(prompt.contains("risks:"))
        assertTrue(prompt.contains("confidence:"))
    }

    @Test
    fun `json prompt mentions JSON output format`() {
        val prompt = IntakePromptBuilder.build(makeRequest(IntakeOutputFormat.JSON))
        assertTrue(
            prompt.contains("Output format: JSON"),
            "JSON prompt must specify JSON output format"
        )
    }

    @Test
    fun `markdown prompt contains expected section headings`() {
        val prompt = IntakePromptBuilder.build(makeRequest(IntakeOutputFormat.MARKDOWN))
        assertTrue(prompt.contains("Task understanding"))
        assertTrue(prompt.contains("Repository findings"))
        assertTrue(prompt.contains("Risks"))
        assertTrue(prompt.contains("Confidence"))
    }

    @Test
    fun `extra instructions are appended when present`() {
        val extra = "Focus on the PaymentService class only."
        val prompt = IntakePromptBuilder.build(makeRequest(extra = extra))
        assertTrue(prompt.contains(extra))
    }

    @Test
    fun `prompt does not contain cwd absolute path`() {
        val prompt = IntakePromptBuilder.build(makeRequest())
        // The cwd (/repo) should not appear in the prompt — only the relative path does.
        assertFalse(prompt.contains("/repo/.agent-intake"))
    }

    @Test
    fun `extra instructions are absent when null`() {
        val prompt = IntakePromptBuilder.build(makeRequest(extra = null))
        assertFalse(prompt.contains("Additional caller instructions"))
    }

    @Test
    fun `extra instructions are absent when blank`() {
        val prompt = IntakePromptBuilder.build(makeRequest(extra = "   "))
        assertFalse(prompt.contains("Additional caller instructions"))
    }

    @Test
    fun `prompt instructs not to expose secrets`() {
        val prompt = IntakePromptBuilder.build(makeRequest())
        assertTrue(
            prompt.contains("Do not read, print, or expose secrets") ||
                prompt.contains("secret"),
            "Prompt must include a secret-safety instruction"
        )
    }

    @Test
    fun `prompt does not mention Jira, Android, checkout, or Sephora`() {
        val prompt = IntakePromptBuilder.build(makeRequest())
        val lower = prompt.lowercase()
        assertFalse(lower.contains("jira"), "Prompt must not reference Jira")
        assertFalse(lower.contains("android"), "Prompt must not reference Android")
        assertFalse(lower.contains("checkout"), "Prompt must not reference checkout")
        assertFalse(lower.contains("sephora"), "Prompt must not reference Sephora")
    }
}
