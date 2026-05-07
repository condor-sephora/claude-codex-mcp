package codex

import config.AppConfig
import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class CodexCommandTest {

    private val baseConfig = AppConfig(
        codexPath = "/usr/local/bin/codex",
        allowedRoots = listOf("/workspace"),
        defaultTimeoutMs = 30_000,
        minTimeoutMs = 5_000,
        maxTimeoutMs = 300_000,
        maxPromptChars = 8_000,
        maxOutputChars = 60_000,
        allowDangerFullAccess = false,
        allowExtraArgs = false,
        extraArgsAllowlist = emptySet(),
        envPassthroughAllowlist = setOf("PATH"),
        auditLogPath = null,
    )

    private fun makeRequest(
        prompt: String = "list files",
        sandbox: SandboxMode = SandboxMode.READ_ONLY,
        extraArgs: List<String> = emptyList(),
    ) = CodexExecutionRequest(
        prompt = prompt,
        cwd = "/workspace",
        sandbox = sandbox,
        timeoutMs = 30_000,
        approvalMode = ApprovalMode.UNTRUSTED,
        taskId = null,
        phase = null,
        metadata = null,
        extraArgs = extraArgs,
    )

    // ---------- Command structure ----------

    @Test
    fun `command starts with configured codex path`() {
        val cmd = CodexCommand.build(makeRequest(), baseConfig)
        assertEquals("/usr/local/bin/codex", cmd.first())
    }

    @Test
    fun `command includes exec subcommand`() {
        val cmd = CodexCommand.build(makeRequest(), baseConfig)
        assertTrue(cmd.contains("exec"))
        assertEquals(1, cmd.indexOf("exec"))
    }

    @Test
    fun `prompt is the last argument`() {
        val prompt = "list all Kotlin source files"
        val cmd = CodexCommand.build(makeRequest(prompt = prompt), baseConfig)
        assertEquals(prompt, cmd.last())
    }

    @Test
    fun `prompt is a single argument (not split)`() {
        val prompt = "list all   files with spaces   in name"
        val cmd = CodexCommand.build(makeRequest(prompt = prompt), baseConfig)
        assertEquals(prompt, cmd.last())
        // The prompt must appear exactly once in the argument list
        assertEquals(1, cmd.count { it == prompt })
    }

    @Test
    fun `does not use shell execution - no sh or bash in command`() {
        val cmd = CodexCommand.build(makeRequest(), baseConfig)
        assertFalse(cmd.any { it == "sh" || it == "bash" || it == "cmd" })
        // Must not start with a shell invocation
        assertFalse(cmd.first().endsWith("sh"))
        assertFalse(cmd.first().endsWith("bash"))
    }

    @Test
    fun `without sandbox support flag, sandbox is not in command`() {
        val env = emptyMap<String, String>() // CODEX_MCP_CODEX_SUPPORTS_SANDBOX not set
        val cmd = CodexCommand.build(makeRequest(sandbox = SandboxMode.READ_ONLY), baseConfig, env)
        assertFalse(cmd.contains("--sandbox"))
        assertFalse(cmd.contains("read-only"))
    }

    @Test
    fun `with sandbox support flag, sandbox flag is included`() {
        val env = mapOf("CODEX_MCP_CODEX_SUPPORTS_SANDBOX" to "true")
        val cmd = CodexCommand.build(makeRequest(sandbox = SandboxMode.WORKSPACE_WRITE), baseConfig, env)
        assertTrue(cmd.contains("--sandbox"))
        val sandboxIdx = cmd.indexOf("--sandbox")
        assertEquals("workspace-write", cmd[sandboxIdx + 1])
    }

    @Test
    fun `extra args appear before prompt`() {
        val extraArgs = listOf("--quiet")
        val prompt = "run tests"
        val cmd = CodexCommand.build(
            makeRequest(prompt = prompt, extraArgs = extraArgs),
            baseConfig,
        )
        val promptIdx = cmd.indexOf(prompt)
        val quietIdx = cmd.indexOf("--quiet")
        assertTrue(quietIdx > 0, "--quiet should be in the command")
        assertTrue(promptIdx > quietIdx, "prompt must come after extra args")
    }

    // ---------- Command preview ----------

    @Test
    fun `preview does not contain raw prompt`() {
        val prompt = "very sensitive instruction"
        val preview = CodexCommand.preview(makeRequest(prompt = prompt), baseConfig)
        assertFalse(preview.contains("very sensitive instruction"), "Raw prompt must not appear in preview")
    }

    @Test
    fun `preview contains codex path`() {
        val preview = CodexCommand.preview(makeRequest(), baseConfig)
        assertTrue(preview.contains("/usr/local/bin/codex"))
    }

    @Test
    fun `preview shows prompt length`() {
        val prompt = "a".repeat(100)
        val preview = CodexCommand.preview(makeRequest(prompt = prompt), baseConfig)
        assertTrue(preview.contains("100chars"))
    }
}
