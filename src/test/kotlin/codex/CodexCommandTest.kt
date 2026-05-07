package codex

import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class CodexCommandTest {

    private fun makeRequest(prompt: String = "list files") = CodexExecutionRequest(
        prompt = prompt,
        cwd = "/workspace",
        sandbox = SandboxMode.READ_ONLY,
        timeoutMs = 30_000,
        taskId = null,
    )

    @Test
    fun `command starts with codex`() {
        assertEquals("codex", CodexCommand.build(makeRequest()).first())
    }

    @Test
    fun `command includes exec subcommand at index 1`() {
        assertEquals("exec", CodexCommand.build(makeRequest())[1])
    }

    @Test
    fun `prompt is the last argument`() {
        val prompt = "list all Kotlin source files"
        assertEquals(prompt, CodexCommand.build(makeRequest(prompt)).last())
    }

    @Test
    fun `prompt is a single argument (not split)`() {
        val prompt = "list all   files with spaces   in name"
        val cmd = CodexCommand.build(makeRequest(prompt))
        assertEquals(prompt, cmd.last())
        assertEquals(1, cmd.count { it == prompt })
    }

    @Test
    fun `does not use shell execution`() {
        val cmd = CodexCommand.build(makeRequest())
        assertFalse(cmd.any { it == "sh" || it == "bash" || it == "cmd" })
    }

    @Test
    fun `command has exactly three elements`() {
        assertEquals(3, CodexCommand.build(makeRequest()).size)
    }

    @Test
    fun `preview does not contain raw prompt`() {
        val prompt = "very sensitive instruction"
        assertFalse(CodexCommand.preview(makeRequest(prompt)).contains("very sensitive instruction"))
    }

    @Test
    fun `preview starts with codex exec`() {
        assertTrue(CodexCommand.preview(makeRequest()).startsWith("codex exec"))
    }

    @Test
    fun `preview shows prompt length`() {
        val prompt = "a".repeat(100)
        assertTrue(CodexCommand.preview(makeRequest(prompt)).contains("100chars"))
    }
}
