package mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
            extraEnv = extraEnv,
        )
        server.start()
        server.client.initialize()
    }

    @BeforeEach
    fun noop() {}

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun callTool(args: Map<String, Any?>): CodexResultShape {
        val argsJson = buildJsonObject {
            for ((k, v) in args) when {
                v is String -> put(k, v)
                v is Long -> put(k, v)
                v is Int -> put(k, v.toLong())
                else -> {}
            }
        }
        val response = server.client.callTool("execute_codex", argsJson)
        val contentText = response["result"]!!
            .jsonObject["content"]!!
            .jsonArray.first()
            .jsonObject["text"]!!
            .jsonPrimitive.contentOrNull!!
        return json.decodeFromString(CodexResultShape.serializer(), contentText)
    }

    // ---------- Successful execution ----------

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
        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun `taskId is echoed in result`() {
        startServer(mapOf("FAKE_CODEX_MODE" to "success"))
        val result = callTool(mapOf(
            "prompt" to "analyze the codebase",
            "cwd" to tempDir.toFile().canonicalPath,
            "taskId" to "SHP-99999",
        ))

        assertEquals("SHP-99999", result.taskId)
        assertNotNull(result.workingDirectory)
        assertNotNull(result.commandPreview)
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
    fun `successful execution sets isError to false`() {
        startServer(mapOf("FAKE_CODEX_MODE" to "success"))
        val response = server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "list kotlin files")
            put("cwd", tempDir.toFile().canonicalPath)
        })
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertFalse(isError == true, "Successful execution must not set isError=true")
    }

    @Test
    fun `non-zero exit code sets isError to true`() {
        startServer(mapOf("FAKE_CODEX_MODE" to "error"))
        val response = server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "cause an error")
            put("cwd", tempDir.toFile().canonicalPath)
        })
        val isError = response["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean
        assertTrue(isError == true, "Non-zero exit code must set isError=true")
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
    val taskId: String? = null,
)
