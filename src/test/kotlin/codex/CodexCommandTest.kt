package codex

import config.AppConfig
import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class CodexCommandTest {

    private val baseConfig = AppConfig(
        timeoutMs = 30_000,
        maxPromptChars = 8_000,
        maxOutputChars = 60_000,
        allowDangerFullAccess = false,
        envPassthroughAllowlist = setOf("PATH"),
        auditLogPath = null,
    )

    private fun makeRequest(prompt: String = "list files") = CodexExecutionRequest(
        prompt = prompt,
        cwd = "/workspace",
        sandbox = SandboxMode.READ_ONLY,
        timeoutMs = 30_000,
        taskId = null,
    )

    @Test
    fun `command starts with codex`() {
        assertEquals("codex", CodexCommand.build(makeRequest(), baseConfig).first())
    }

    @Test
    fun `command includes exec subcommand at index 1`() {
        assertEquals("exec", CodexCommand.build(makeRequest(), baseConfig)[1])
    }

    @Test
    fun `prompt is the last argument`() {
        val prompt = "list all Kotlin source files"
        assertEquals(prompt, CodexCommand.build(makeRequest(prompt), baseConfig).last())
    }

    @Test
    fun `prompt is a single argument (not split)`() {
        val prompt = "list all   files with spaces   in name"
        val cmd = CodexCommand.build(makeRequest(prompt), baseConfig)
        assertEquals(prompt, cmd.last())
        assertEquals(1, cmd.count { it == prompt })
    }

    @Test
    fun `does not use shell execution`() {
        val cmd = CodexCommand.build(makeRequest(), baseConfig)
        assertFalse(cmd.any { it == "sh" || it == "bash" || it == "cmd" })
    }

    @Test
    fun `command has exactly three elements`() {
        assertEquals(3, CodexCommand.build(makeRequest(), baseConfig).size)
    }

    @Test
    fun `preview does not contain raw prompt`() {
        val prompt = "very sensitive instruction"
        assertFalse(CodexCommand.preview(makeRequest(prompt), baseConfig).contains("very sensitive instruction"))
    }

    @Test
    fun `preview starts with codex exec`() {
        assertTrue(CodexCommand.preview(makeRequest(), baseConfig).startsWith("codex exec"))
    }

    @Test
    fun `preview shows prompt length`() {
        val prompt = "a".repeat(100)
        assertTrue(CodexCommand.preview(makeRequest(prompt), baseConfig).contains("100chars"))
    }
}
