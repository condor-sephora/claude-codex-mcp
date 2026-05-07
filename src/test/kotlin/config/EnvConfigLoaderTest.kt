package config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test

class EnvConfigLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads defaults when env is empty`() {
        val config = EnvConfigLoader.load(emptyMap())
        assertNotNull(config)
        assertEquals(120_000L, config.timeoutMs)
    }

    @Test
    fun `reads CODEX_MCP_TIMEOUT_MS`() {
        val config = EnvConfigLoader.load(mapOf("CODEX_MCP_TIMEOUT_MS" to "45000"))
        assertEquals(45_000L, config.timeoutMs)
    }

    @Test
    fun `uses default timeout when not set`() {
        val config = EnvConfigLoader.load(emptyMap())
        assertEquals(120_000L, config.timeoutMs)
    }

    @Test
    fun `reads max prompt and output chars`() {
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_MCP_MAX_PROMPT_CHARS" to "5000",
            "CODEX_MCP_MAX_OUTPUT_CHARS" to "30000",
        ))
        assertEquals(5_000, config.maxPromptChars)
        assertEquals(30_000, config.maxOutputChars)
    }

    @Test
    fun `danger full access disabled by default`() {
        assertFalse(EnvConfigLoader.load(emptyMap()).allowDangerFullAccess)
    }

    @Test
    fun `danger full access enabled when env is true`() {
        val config = EnvConfigLoader.load(mapOf("CODEX_MCP_ALLOW_DANGER_FULL_ACCESS" to "true"))
        assertTrue(config.allowDangerFullAccess)
    }

    @Test
    fun `reads custom env passthrough allowlist`() {
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST" to "PATH,HOME,MY_CUSTOM_VAR",
        ))
        assertEquals(setOf("PATH", "HOME", "MY_CUSTOM_VAR"), config.envPassthroughAllowlist)
    }

    @Test
    fun `reads audit log path`() {
        val logPath = File(tempDir.toFile(), "audit.log").absolutePath
        val config = EnvConfigLoader.load(mapOf("CODEX_MCP_AUDIT_LOG_PATH" to logPath))
        assertEquals(logPath, config.auditLogPath)
    }

    @Test
    fun `fails when timeout is not positive`() {
        assertThrows(Exception::class.java) {
            EnvConfigLoader.load(mapOf("CODEX_MCP_TIMEOUT_MS" to "0"))
        }
    }
}
