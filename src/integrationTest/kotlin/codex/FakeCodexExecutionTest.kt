package codex

import mcp.McpServerProcess
import mcp.CodexResultShape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Integration tests using the fake Codex executable to verify:
 *   - Prompt is passed as a single argument.
 *   - Environment variables match the allowlist.
 *   - Working directory is set correctly.
 *   - Secrets in output are redacted.
 */
class FakeCodexExecutionTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarPath: String by lazy { System.getProperty("mcp.jar.path") ?: error("mcp.jar.path not set") }
    private val fakeCodexPath: String by lazy { System.getProperty("fake.codex.path") ?: error("fake.codex.path not set") }
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var server: McpServerProcess

    @AfterEach
    fun tearDown() { server.close() }

    private fun startAndInit(mode: String, extraEnv: Map<String, String> = emptyMap()): McpServerProcess {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            extraEnv = mapOf("FAKE_CODEX_MODE" to mode) + extraEnv,
        )
        server.start()
        server.client.initialize()
        return server
    }

    private fun callAndParse(prompt: String, cwd: String = tempDir.toFile().canonicalPath): CodexResultShape {
        val response = server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", prompt)
            put("cwd", cwd)
        })
        val text = response["result"]!!.jsonObject["content"]!!
            .jsonArray.first().jsonObject["text"]!!.jsonPrimitive.contentOrNull!!
        return json.decodeFromString(CodexResultShape.serializer(), text)
    }

    @Test
    fun `fake codex receives prompt as the last argument`() {
        startAndInit("echo-args")
        val prompt = "the exact prompt text to verify"
        val result = callAndParse(prompt)
        // echo-args mode prints "ARG: <arg>" for each argument
        assertTrue(result.stdout.contains("ARG: $prompt"),
            "Prompt '$prompt' should appear as exactly one argument.\nstdout: ${result.stdout}")
    }

    @Test
    fun `working directory is passed to the subprocess`() {
        startAndInit("echo-cwd")
        val cwd = tempDir.toFile().canonicalPath
        val result = callAndParse("echo cwd", cwd)
        assertTrue(result.stdout.trim().startsWith(cwd) || result.stdout.contains(cwd),
            "Subprocess should run in cwd=$cwd.\nstdout: ${result.stdout}")
    }

    @Test
    fun `only allowlisted env vars are forwarded to subprocess`() {
        startAndInit("echo-env", mapOf(
            "CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST" to "PATH,HOME",
        ))
        val result = callAndParse("echo env")
        // CODEX_MCP_TIMEOUT_MS and other MCP server vars must NOT appear in subprocess env
        assertFalse(result.stdout.contains("CODEX_MCP_TIMEOUT_MS"),
            "Server-internal env vars must not leak to subprocess")
        // PATH should be present
        assertTrue(result.stdout.contains("PATH="), "PATH should be forwarded")
    }

    @Test
    fun `secrets in fake codex output are redacted`() {
        startAndInit("secrets")
        val result = callAndParse("produce secrets")
        assertFalse(result.stdout.contains("sk-abc"), "OpenAI API key should be redacted")
        assertFalse(result.stdout.contains("ghp_aBc"), "GitHub token should be redacted")
        assertTrue(result.stdout.contains("[REDACTED]"), "Redaction marker should be present")
    }

    @Test
    fun `exit code is correctly captured`() {
        startAndInit("exit42")
        val result = callAndParse("exit with 42")
        assertEquals(42, result.exitCode)
    }

    @Test
    fun `command preview does not contain the raw prompt`() {
        startAndInit("success")
        val prompt = "very sensitive analysis instruction"
        val result = callAndParse(prompt)
        assertFalse(result.commandPreview.contains("very sensitive analysis instruction"),
            "Raw prompt must not appear in commandPreview")
        assertTrue(result.commandPreview.isNotBlank())
    }
}
