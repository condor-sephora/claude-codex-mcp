package codex

import mcp.McpServerProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Integration tests for the audit logger.
 *
 * Each audit line is "AUDIT <json-object>" — one line per event.
 * Verifies that:
 *   - Audit log entries appear on stderr (captured from server stderr).
 *   - Raw prompt, stdout, stderr, outcome, and sessionId ARE logged.
 *   - Environment variable values are NEVER logged.
 *   - Rejection events are logged with a rejectionCategory.
 */
class AuditLoggingIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarPath: String by lazy { System.getProperty("mcp.jar.path") ?: error("mcp.jar.path not set") }
    private val fakeCodexPath: String by lazy { System.getProperty("fake.codex.path") ?: error("fake.codex.path not set") }
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var server: McpServerProcess

    @AfterEach
    fun tearDown() { server.close() }

    @Test
    fun `audit log contains prompt, metadata, and taskId`() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            extraEnv = mapOf("FAKE_CODEX_MODE" to "success"),
        )
        server.start()
        server.client.initialize()

        val prompt = "list kotlin files in the project"
        server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", prompt)
            put("cwd", tempDir.toFile().canonicalPath)
            put("taskId", "AUDIT-TEST-001")
        })

        Thread.sleep(500)
        val stderr = server.getStderr()

        val auditLines = stderr.filter { it.startsWith("AUDIT") }
        assertTrue(auditLines.isNotEmpty(), "At least one AUDIT line expected.\nAll stderr:\n${stderr.joinToString("\n")}")

        val auditLine = auditLines.first { it.contains("\"event\":\"codex_invocation\"") }
        val auditJson = json.parseToJsonElement(auditLine.removePrefix("AUDIT ")).jsonObject

        // Core metadata fields
        assertTrue(auditLine.contains("\"outcome\":"), "Audit line must contain outcome")
        assertTrue(auditLine.contains("\"promptHash\":"), "Audit line must contain promptHash")
        assertTrue(auditLine.contains("\"promptLength\":"), "Audit line must contain promptLength")
        assertTrue(auditLine.contains("\"sessionId\":"), "Audit line must contain sessionId")
        assertTrue(auditLine.contains("AUDIT-TEST-001"), "Audit line must contain taskId")

        // Raw prompt must be present
        assertEquals(
            prompt,
            auditJson["prompt"]?.jsonPrimitive?.contentOrNull,
            "Audit line must contain the raw prompt in the 'prompt' field",
        )
    }

    @Test
    fun `successful invocation logs outcome=success`() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            extraEnv = mapOf("FAKE_CODEX_MODE" to "success"),
        )
        server.start()
        server.client.initialize()

        server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "run analysis")
            put("cwd", tempDir.toFile().canonicalPath)
        })

        Thread.sleep(500)
        val auditLine = server.getStderr()
            .filter { it.startsWith("AUDIT") }
            .first { it.contains("\"event\":\"codex_invocation\"") }

        assertTrue(auditLine.contains("\"outcome\":\"success\""),
            "Successful call should log outcome=success.\nAudit line: $auditLine")
    }

    @Test
    fun `failed invocation logs outcome=codex_error`() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            extraEnv = mapOf("FAKE_CODEX_MODE" to "error"),
        )
        server.start()
        server.client.initialize()

        server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "cause an error")
            put("cwd", tempDir.toFile().canonicalPath)
        })

        Thread.sleep(500)
        val auditLine = server.getStderr()
            .filter { it.startsWith("AUDIT") }
            .first { it.contains("\"event\":\"codex_invocation\"") }

        assertTrue(auditLine.contains("\"outcome\":\"codex_error\""),
            "Failed call should log outcome=codex_error.\nAudit line: $auditLine")
    }

    @Test
    fun `rejection events are logged with AUDIT prefix and rejectionCategory`() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
        )
        server.start()
        server.client.initialize()

        server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "")
            put("cwd", tempDir.toFile().canonicalPath)
        })

        Thread.sleep(500)
        val stderr = server.getStderr()
        val rejectionLines = stderr.filter { it.contains("\"event\":\"security_rejection\"") }

        assertTrue(rejectionLines.isNotEmpty(),
            "Expected security_rejection audit event.\nAll stderr:\n${stderr.joinToString("\n")}")
        assertTrue(rejectionLines.first().contains("\"rejectionCategory\":\"empty_prompt\""),
            "Empty prompt rejection should be categorized as empty_prompt")
    }

    @Test
    fun `audit log contains codex stdout and stderr output`() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            extraEnv = mapOf("FAKE_CODEX_MODE" to "stderr"),
        )
        server.start()
        server.client.initialize()

        server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "run analysis")
            put("cwd", tempDir.toFile().canonicalPath)
        })

        Thread.sleep(500)
        val auditLine = server.getStderr()
            .filter { it.startsWith("AUDIT") }
            .first { it.contains("\"event\":\"codex_invocation\"") }

        assertTrue(auditLine.contains("\"stdout\":"), "Audit line must contain stdout field")
        assertTrue(auditLine.contains("\"stderr\":"), "Audit line must contain stderr field")
        assertTrue(auditLine.contains("stdout output from codex"), "Audit stdout must contain codex output")
        assertTrue(auditLine.contains("stderr warning from codex"), "Audit stderr must contain codex stderr")
    }

    @Test
    fun `audit log does not contain environment variable values`() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
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
        val allStderr = server.getStderr().joinToString("\n")

        assertFalse(allStderr.contains("sk-test-audit-should-not-appear"),
            "API key value must not appear in any log output")
    }
}
