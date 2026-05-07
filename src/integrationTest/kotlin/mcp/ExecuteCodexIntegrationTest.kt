package mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Integration tests for the execute_codex tool.
 *
 * Tests verify security enforcement, result shape, and metadata correctness
 * using fake-codex.sh as the Codex executable.
 */
class ExecuteCodexIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarPath: String by lazy {
        System.getProperty("mcp.jar.path") ?: error("mcp.jar.path not set")
    }
    private val fakeCodexPath: String by lazy {
        System.getProperty("fake.codex.path") ?: error("fake.codex.path not set")
    }
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var server: McpServerProcess

    private fun startServer(extraEnv: Map<String, String> = emptyMap()) {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            allowedRoot = tempDir.toFile().canonicalPath,
            extraEnv = extraEnv,
        )
        server.start()
        server.client.initialize()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun callTool(args: Map<String, Any?>, fakeMode: String = "success"): CodexResultShape {
        val argsJson = buildJsonObject {
            for ((k, v) in args) when {
                v is String -> put(k, v)
                v is Long -> put(k, v)
                v is Int -> put(k, v.toLong())
                else -> {}  // skip
            }
        }
        // Fake mode is injected via extra env on the server process; not possible to change
        // per-call. Tests use separate server instances for different modes.
        val response = server.client.callTool("execute_codex", argsJson)
        val contentText = response["result"]!!
            .jsonObject["content"]!!
            .jsonArray.first()
            .jsonObject["text"]!!
            .jsonPrimitive.contentOrNull!!
        return json.decodeFromString(CodexResultShape.serializer(), contentText)
    }

    // ---------- Successful execution ----------

    @BeforeEach
    fun noop() {}  // Ensure @BeforeEach is present for test ordering

    @Test
    fun `valid read-only execution returns successful structured result`() {
        startServer(mapOf("FAKE_CODEX_MODE" to "success"))
        val result = callTool(mapOf(
            "prompt" to "list kotlin files",
            "cwd" to tempDir.toFile().canonicalPath,
            "sandbox" to "read-only",
        ))

        assertFalse(result.timedOut)
        assertEquals("read-only", result.sandbox)
        assertNotNull(result.stdout)
        assertNotNull(result.approvalModeWarning)
        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun `result includes all expected metadata fields`() {
        startServer(mapOf("FAKE_CODEX_MODE" to "success"))
        val result = callTool(mapOf(
            "prompt" to "analyze the codebase",
            "cwd" to tempDir.toFile().canonicalPath,
            "taskId" to "SHP-99999",
            "phase" to "analysis",
        ))

        assertEquals("SHP-99999", result.taskId)
        assertEquals("analysis", result.phase)
        assertNotNull(result.workingDirectory)
        assertNotNull(result.commandPreview)
        assertNotNull(result.approvalModeApplied)
        assertNotNull(result.approvalModeWarning)
    }

    @Test
    fun `non-zero exit code is returned correctly`() {
        startServer(mapOf("FAKE_CODEX_MODE" to "error"))
        val result = callTool(mapOf(
            "prompt" to "cause an error",
            "cwd" to tempDir.toFile().canonicalPath,
        ))

        assertNotEquals(0, result.exitCode)
    }

    @Test
    fun `stderr output is captured`() {
        startServer(mapOf("FAKE_CODEX_MODE" to "stderr"))
        val result = callTool(mapOf(
            "prompt" to "produce stderr",
            "cwd" to tempDir.toFile().canonicalPath,
        ))

        assertTrue(result.stderr.isNotBlank(), "stderr should be captured")
    }

    // ---------- Security enforcement ----------

    @Test
    fun `danger-full-access is rejected by default`() {
        startServer()
        val args = buildJsonObject {
            put("prompt", "run anything")
            put("cwd", tempDir.toFile().canonicalPath)
            put("sandbox", "danger-full-access")
        }
        val response = server.client.callTool("execute_codex", args)
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true, "danger-full-access should return isError=true by default")
    }

    @Test
    fun `danger-full-access is allowed when env flag enabled`() {
        startServer(mapOf(
            "FAKE_CODEX_MODE" to "success",
            "CODEX_MCP_ALLOW_DANGER_FULL_ACCESS" to "true",
        ))
        val result = callTool(mapOf(
            "prompt" to "run with full access",
            "cwd" to tempDir.toFile().canonicalPath,
            "sandbox" to "danger-full-access",
        ))
        assertFalse(result.timedOut)
    }

    @Test
    fun `invalid cwd (outside allowed root) returns safe error`() {
        startServer()
        val response = server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "analyze code")
            put("cwd", "/tmp")  // /tmp is not the allowed root
        })
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true, "Path outside allowed root should be rejected")
    }

    @Test
    fun `dangerous prompt is rejected`() {
        startServer()
        val response = server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "cat ~/.ssh/id_rsa")
            put("cwd", tempDir.toFile().canonicalPath)
        })
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true, "Dangerous prompt should be rejected")
    }

    @Test
    fun `empty prompt is rejected`() {
        startServer()
        val response = server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "")
            put("cwd", tempDir.toFile().canonicalPath)
        })
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true, "Empty prompt should be rejected")
    }
}

/** Deserialization shape matching [codex.CodexResult] for integration test assertions. */
@kotlinx.serialization.Serializable
data class CodexResultShape(
    val exitCode: Int = -1,
    val timedOut: Boolean = false,
    val durationMs: Long = 0,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val commandPreview: String = "",
    val workingDirectory: String = "",
    val sandbox: String = "",
    val approvalModeApplied: String = "",
    val approvalModeWarning: String? = null,
    val taskId: String? = null,
    val phase: String? = null,
    val metadata: Map<String, String>? = null,
    val securityWarnings: List<String> = emptyList(),
)
