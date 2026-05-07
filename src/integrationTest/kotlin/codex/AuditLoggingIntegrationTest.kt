package codex

import mcp.McpServerProcess
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Integration tests for the audit logger.
 *
 * Verifies that:
 *   - Audit log entries appear on stderr (captured from server stderr).
 *   - Raw prompt text does NOT appear in the audit log.
 *   - Prompt hash and length ARE logged.
 *   - Rejection events are logged.
 */
class AuditLoggingIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarPath: String by lazy { System.getProperty("mcp.jar.path") ?: error("mcp.jar.path not set") }
    private val fakeCodexPath: String by lazy { System.getProperty("fake.codex.path") ?: error("fake.codex.path not set") }

    private lateinit var server: McpServerProcess

    @AfterEach
    fun tearDown() { server.close() }

    @Test
    fun `audit log contains AUDIT prefix and metadata — not raw prompt`() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            allowedRoot = tempDir.toFile().canonicalPath,
            extraEnv = mapOf("FAKE_CODEX_MODE" to "success"),
        )
        server.start()
        server.client.initialize()

        val sensitivePrompt = "this-is-the-raw-prompt-content-do-not-log"
        server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", sensitivePrompt)
            put("cwd", tempDir.toFile().canonicalPath)
            put("taskId", "AUDIT-TEST-001")
        })

        // Wait for audit log to be written
        Thread.sleep(500)
        val stderr = server.getStderr()

        val auditLines = stderr.filter { it.startsWith("AUDIT") }
        assertTrue(auditLines.isNotEmpty(), "At least one AUDIT line expected.\nAll stderr:\n${stderr.joinToString("\n")}")

        val auditLine = auditLines.first { it.contains("event=codex_invocation") }

        // Must contain prompt hash and length
        assertTrue(auditLine.contains("promptHash="), "Audit line must contain promptHash")
        assertTrue(auditLine.contains("promptLength="), "Audit line must contain promptLength")

        // Must NOT contain the raw prompt
        assertFalse(auditLine.contains(sensitivePrompt),
            "Raw prompt must not appear in audit log.\nAudit line: $auditLine")

        // Must contain task ID
        assertTrue(auditLine.contains("AUDIT-TEST-001"), "Audit line must contain taskId")
    }

    @Test
    fun `rejection events are logged with AUDIT prefix`() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            allowedRoot = tempDir.toFile().canonicalPath,
        )
        server.start()
        server.client.initialize()

        // Call with empty prompt — should be rejected
        server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "")
            put("cwd", tempDir.toFile().canonicalPath)
        })

        Thread.sleep(500)
        val stderr = server.getStderr()
        val rejectionLines = stderr.filter { it.contains("event=security_rejection") }

        assertTrue(rejectionLines.isNotEmpty(),
            "Expected security_rejection audit event.\nAll stderr:\n${stderr.joinToString("\n")}")
    }

    @Test
    fun `audit log does not contain environment variable values`() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            allowedRoot = tempDir.toFile().canonicalPath,
            extraEnv = mapOf(
                "FAKE_CODEX_MODE" to "success",
                "OPENAI_API_KEY" to "sk-test-audit-should-not-appear",
            ),
        )
        server.start()
        server.client.initialize()

        server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "run analysis")
            put("cwd", tempDir.toFile().canonicalPath)
        })

        Thread.sleep(500)
        val stderr = server.getStderr()
        val allStderr = stderr.joinToString("\n")

        assertFalse(allStderr.contains("sk-test-audit-should-not-appear"),
            "API key value must not appear in any log output")
    }
}
