package intake

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mcp.McpServerProcess
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Integration tests for the code_intake MCP tool.
 *
 * Uses fake-codex.sh so no real Codex CLI or API key is needed.
 */
class CodeIntakeIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarPath: String by lazy { System.getProperty("mcp.jar.path") ?: error("mcp.jar.path not set") }
    private val fakeCodexPath: String by lazy { System.getProperty("fake.codex.path") ?: error("fake.codex.path not set") }
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var server: McpServerProcess

    @AfterEach
    fun tearDown() { server.close() }

    private fun startServer(extraEnv: Map<String, String> = emptyMap()): McpServerProcess {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            extraEnv = mapOf("FAKE_CODEX_MODE" to "success") + extraEnv,
        )
        server.start()
        server.client.initialize()
        return server
    }

    private fun writeIntakeFile(relative: String = ".agent-intake/TASK-1/intake-request.md"): String {
        val f = File(tempDir.toFile(), relative)
        f.parentFile.mkdirs()
        f.writeText("# Intake Request\n\n## Task ID\nTASK-1\n\n## Problem Statement\nTest problem.")
        return f.canonicalPath
    }

    private fun callIntake(args: Map<String, Any?>): IntakeResultShape {
        val argsJson = buildJsonObject {
            for ((k, v) in args) when (v) {
                is String -> put(k, v)
                is Long -> put(k, v)
                is Int -> put(k, v.toLong())
            }
        }
        val response = server.client.callTool("code_intake", argsJson)
        val text = response["result"]!!.jsonObject["content"]!!
            .jsonArray.first().jsonObject["text"]!!.jsonPrimitive.contentOrNull!!
        return json.decodeFromString(IntakeResultShape.serializer(), text)
    }

    private fun callIntakeRaw(args: Map<String, Any?>): kotlinx.serialization.json.JsonObject {
        val argsJson = buildJsonObject {
            for ((k, v) in args) when (v) {
                is String -> put(k, v)
                is Long -> put(k, v.toLong())
            }
        }
        return server.client.callTool("code_intake", argsJson)
    }

    // ---------- Successful execution ----------

    @Test
    fun `valid intake call returns successful result with mode=intake`() {
        startServer()
        writeIntakeFile()
        val result = callIntake(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to ".agent-intake/TASK-1/intake-request.md",
        ))
        assertFalse(result.timedOut)
        assertEquals("intake", result.mode)
        assertEquals("read-only", result.sandbox)
        assertEquals(".agent-intake/TASK-1/intake-request.md", result.requestFile)
        assertEquals("yaml", result.outputFormat)
    }

    @Test
    fun `taskId is echoed in intake result`() {
        startServer()
        writeIntakeFile()
        val result = callIntake(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to ".agent-intake/TASK-1/intake-request.md",
            "taskId" to "TASK-999",
        ))
        assertEquals("TASK-999", result.taskId)
    }

    @Test
    fun `outputFormat json is echoed in result`() {
        startServer()
        writeIntakeFile()
        val result = callIntake(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to ".agent-intake/TASK-1/intake-request.md",
            "outputFormat" to "json",
        ))
        assertEquals("json", result.outputFormat)
    }

    @Test
    fun `outputFormat markdown is echoed in result`() {
        startServer()
        writeIntakeFile()
        val result = callIntake(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to ".agent-intake/TASK-1/intake-request.md",
            "outputFormat" to "markdown",
        ))
        assertEquals("markdown", result.outputFormat)
    }

    @Test
    fun `successful intake does not set isError`() {
        startServer()
        writeIntakeFile()
        val response = callIntakeRaw(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to ".agent-intake/TASK-1/intake-request.md",
        ))
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertFalse(isError == true, "Successful intake should not set isError=true")
    }

    // ---------- Security rejections ----------

    @Test
    fun `missing requestFile is rejected with isError=true`() {
        startServer()
        val response = callIntakeRaw(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
        ))
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true, "Missing requestFile must produce isError=true")
    }

    @Test
    fun `non-existent requestFile is rejected`() {
        startServer()
        val response = callIntakeRaw(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to ".agent-intake/NO/no-such-file.md",
        ))
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true)
    }

    @Test
    fun `workspace-write sandbox is rejected`() {
        startServer()
        writeIntakeFile()
        val response = callIntakeRaw(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to ".agent-intake/TASK-1/intake-request.md",
            "sandbox" to "workspace-write",
        ))
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true, "workspace-write sandbox must be rejected for intake")
        val text = response["result"]!!.jsonObject["content"]!!
            .jsonArray.first().jsonObject["text"]!!.jsonPrimitive.contentOrNull!!
        assertTrue(text.contains("read-only"), "Error message should mention read-only")
    }

    @Test
    fun `danger-full-access sandbox is rejected`() {
        startServer(extraEnv = mapOf("CODEX_MCP_ALLOW_DANGER_FULL_ACCESS" to "true"))
        writeIntakeFile()
        val response = callIntakeRaw(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to ".agent-intake/TASK-1/intake-request.md",
            "sandbox" to "danger-full-access",
        ))
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true, "danger-full-access must be rejected even when globally allowed")
    }

    @Test
    fun `invalid outputFormat is rejected`() {
        startServer()
        writeIntakeFile()
        val response = callIntakeRaw(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to ".agent-intake/TASK-1/intake-request.md",
            "outputFormat" to "xml",
        ))
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true)
    }

    @Test
    fun `requestFile with dotdot path traversal is rejected`() {
        startServer()
        val response = callIntakeRaw(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to "../escape.md",
        ))
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true, "Path traversal must be rejected")
    }

    @Test
    fun `absolute requestFile path is rejected`() {
        startServer()
        writeIntakeFile()
        val abs = File(tempDir.toFile(), ".agent-intake/TASK-1/intake-request.md").canonicalPath
        val response = callIntakeRaw(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to abs,
        ))
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true, "Absolute requestFile must be rejected")
    }

    @Test
    fun `existing execute_codex tool is still reachable`() {
        startServer()
        val response = server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "list files")
            put("cwd", tempDir.toFile().canonicalPath)
        })
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertFalse(isError == true, "execute_codex must still work after adding code_intake")
    }

    // ---------- Audit log ----------

    @Test
    fun `intake invocation writes intake_invocation audit event`() {
        startServer()
        writeIntakeFile()
        callIntake(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
            "requestFile" to ".agent-intake/TASK-1/intake-request.md",
            "taskId" to "AUDIT-INTAKE-1",
        ))
        Thread.sleep(500)
        val auditLines = server.getStderr().filter { it.startsWith("AUDIT") }
        val intakeAuditLine = auditLines.firstOrNull { it.contains("intake_invocation") }
        assertNotNull(intakeAuditLine,
            "Expected intake_invocation audit event.\nAll audit lines:\n${auditLines.joinToString("\n")}")
        assertTrue(intakeAuditLine!!.contains("AUDIT-INTAKE-1"), "taskId must appear in audit")
        assertTrue(intakeAuditLine.contains("requestFile"), "requestFile must appear in audit")
    }

    @Test
    fun `intake rejection writes intake_rejection audit event`() {
        startServer()
        callIntakeRaw(mapOf(
            "cwd" to tempDir.toFile().canonicalPath,
        ))
        Thread.sleep(500)
        val auditLines = server.getStderr().filter { it.startsWith("AUDIT") }
        val rejectionLine = auditLines.firstOrNull { it.contains("intake_rejection") }
        assertNotNull(rejectionLine,
            "Expected intake_rejection audit event.\nAll audit lines:\n${auditLines.joinToString("\n")}")
    }
}

@Serializable
data class IntakeResultShape(
    val mode: String = "",
    val exitCode: Int = -1,
    val timedOut: Boolean = false,
    val durationMs: Long = 0,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val commandPreview: String = "",
    val workingDirectory: String = "",
    val requestFile: String = "",
    val outputFormat: String = "",
    val sandbox: String = "",
    val taskId: String? = null,
)
