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

    // ---------- Intake config ----------

    @Test
    fun `intake defaults when env is empty`() {
        val config = EnvConfigLoader.load(emptyMap())
        assertTrue(config.allowedRoots.isEmpty(), "allowedRoots should be empty by default")
        assertEquals(200L * 1024L, config.maxRequestFileBytes)
        assertEquals(4_000, config.maxExtraInstructionsChars)
        assertEquals(900_000L, config.defaultIntakeTimeoutMs)
        assertEquals(1_800_000L, config.maxIntakeTimeoutMs)
    }

    @Test
    fun `reads CODEX_MCP_MAX_REQUEST_FILE_BYTES`() {
        val config = EnvConfigLoader.load(mapOf("CODEX_MCP_MAX_REQUEST_FILE_BYTES" to "51200"))
        assertEquals(51200L, config.maxRequestFileBytes)
    }

    @Test
    fun `reads CODEX_MCP_MAX_EXTRA_INSTRUCTIONS_CHARS`() {
        val config = EnvConfigLoader.load(mapOf("CODEX_MCP_MAX_EXTRA_INSTRUCTIONS_CHARS" to "1000"))
        assertEquals(1000, config.maxExtraInstructionsChars)
    }

    @Test
    fun `reads CODEX_MCP_DEFAULT_TIMEOUT_MS for intake`() {
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_MCP_DEFAULT_TIMEOUT_MS" to "600000",
            "CODEX_MCP_MAX_TIMEOUT_MS" to "1800000",
        ))
        assertEquals(600_000L, config.defaultIntakeTimeoutMs)
    }

    @Test
    fun `reads CODEX_MCP_MAX_TIMEOUT_MS for intake`() {
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_MCP_DEFAULT_TIMEOUT_MS" to "300000",
            "CODEX_MCP_MAX_TIMEOUT_MS" to "600000",
        ))
        assertEquals(600_000L, config.maxIntakeTimeoutMs)
    }

    @Test
    fun `fails when max timeout is less than default intake timeout`() {
        assertThrows(Exception::class.java) {
            EnvConfigLoader.load(mapOf(
                "CODEX_MCP_DEFAULT_TIMEOUT_MS" to "900000",
                "CODEX_MCP_MAX_TIMEOUT_MS" to "300000",
            ))
        }
    }
}
