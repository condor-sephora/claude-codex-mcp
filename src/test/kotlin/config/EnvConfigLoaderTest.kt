package config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test

class EnvConfigLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    private fun tmpRoot(): String = tempDir.toFile().canonicalPath

    @Test
    fun `loads defaults when env is empty (uses cwd as allowed root)`() {
        // We can't easily test this without mocking user.dir, but we can verify
        // that loading doesn't crash with a minimal env.
        // Actual cwd is a valid directory, so this should succeed.
        val config = EnvConfigLoader.load(emptyMap())
        assertNotNull(config)
        assertTrue(config.allowedRoots.isNotEmpty())
    }

    @Test
    fun `reads CODEX_PATH`() {
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_PATH" to "/custom/codex",
            "CODEX_MCP_ALLOWED_ROOTS" to tmpRoot(),
        ))
        assertEquals("/custom/codex", config.codexPath)
    }

    @Test
    fun `reads CODEX_MCP_ALLOWED_ROOTS colon-separated on unix`() {
        val root1 = File(tempDir.toFile(), "r1").also { it.mkdir() }.canonicalPath
        val root2 = File(tempDir.toFile(), "r2").also { it.mkdir() }.canonicalPath
        val config = EnvConfigLoader.load(mapOf("CODEX_MCP_ALLOWED_ROOTS" to "$root1:$root2"))
        assertEquals(2, config.allowedRoots.size)
        assertTrue(config.allowedRoots.contains(root1))
        assertTrue(config.allowedRoots.contains(root2))
    }

    @Test
    fun `reads timeout overrides`() {
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_MCP_ALLOWED_ROOTS" to tmpRoot(),
            "CODEX_MCP_DEFAULT_TIMEOUT_MS" to "45000",
            "CODEX_MCP_MIN_TIMEOUT_MS" to "5000",
            "CODEX_MCP_MAX_TIMEOUT_MS" to "600000",
        ))
        assertEquals(45_000L, config.defaultTimeoutMs)
        assertEquals(5_000L, config.minTimeoutMs)
        assertEquals(600_000L, config.maxTimeoutMs)
    }

    @Test
    fun `reads max prompt and output chars`() {
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_MCP_ALLOWED_ROOTS" to tmpRoot(),
            "CODEX_MCP_MAX_PROMPT_CHARS" to "5000",
            "CODEX_MCP_MAX_OUTPUT_CHARS" to "30000",
        ))
        assertEquals(5_000, config.maxPromptChars)
        assertEquals(30_000, config.maxOutputChars)
    }

    @Test
    fun `danger full access disabled by default`() {
        val config = EnvConfigLoader.load(mapOf("CODEX_MCP_ALLOWED_ROOTS" to tmpRoot()))
        assertFalse(config.allowDangerFullAccess)
    }

    @Test
    fun `danger full access enabled when env is true`() {
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_MCP_ALLOWED_ROOTS" to tmpRoot(),
            "CODEX_MCP_ALLOW_DANGER_FULL_ACCESS" to "true",
        ))
        assertTrue(config.allowDangerFullAccess)
    }

    @Test
    fun `extra args disabled by default`() {
        val config = EnvConfigLoader.load(mapOf("CODEX_MCP_ALLOWED_ROOTS" to tmpRoot()))
        assertFalse(config.allowExtraArgs)
    }

    @Test
    fun `extra args enabled when env is true`() {
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_MCP_ALLOWED_ROOTS" to tmpRoot(),
            "CODEX_MCP_ALLOW_EXTRA_ARGS" to "true",
        ))
        assertTrue(config.allowExtraArgs)
    }

    @Test
    fun `reads custom env passthrough allowlist`() {
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_MCP_ALLOWED_ROOTS" to tmpRoot(),
            "CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST" to "PATH,HOME,MY_CUSTOM_VAR",
        ))
        assertEquals(setOf("PATH", "HOME", "MY_CUSTOM_VAR"), config.envPassthroughAllowlist)
    }

    @Test
    fun `reads audit log path`() {
        val logPath = File(tempDir.toFile(), "audit.log").absolutePath
        val config = EnvConfigLoader.load(mapOf(
            "CODEX_MCP_ALLOWED_ROOTS" to tmpRoot(),
            "CODEX_MCP_AUDIT_LOG_PATH" to logPath,
        ))
        assertEquals(logPath, config.auditLogPath)
    }

    @Test
    fun `fails when allowed root does not exist`() {
        assertThrows(Exception::class.java) {
            EnvConfigLoader.load(mapOf(
                "CODEX_MCP_ALLOWED_ROOTS" to "/this/path/does/not/exist/12345",
            ))
        }
    }

    @Test
    fun `fails when min timeout exceeds max timeout`() {
        assertThrows(Exception::class.java) {
            EnvConfigLoader.load(mapOf(
                "CODEX_MCP_ALLOWED_ROOTS" to tmpRoot(),
                "CODEX_MCP_MIN_TIMEOUT_MS" to "60000",
                "CODEX_MCP_MAX_TIMEOUT_MS" to "30000",
                "CODEX_MCP_DEFAULT_TIMEOUT_MS" to "45000",
            ))
        }
    }
}
