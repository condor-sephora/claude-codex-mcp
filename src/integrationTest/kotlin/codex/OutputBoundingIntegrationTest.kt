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
 * Integration tests for stdout/stderr output bounding and truncation.
 */
class OutputBoundingIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarPath: String by lazy { System.getProperty("mcp.jar.path") ?: error("mcp.jar.path not set") }
    private val fakeCodexPath: String by lazy { System.getProperty("fake.codex.path") ?: error("fake.codex.path not set") }
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var server: McpServerProcess

    @AfterEach
    fun tearDown() { server.close() }

    private fun startServer(mode: String, maxOutput: Int = 60_000): McpServerProcess {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            extraEnv = mapOf(
                "FAKE_CODEX_MODE" to mode,
                "CODEX_MCP_MAX_OUTPUT_CHARS" to maxOutput.toString(),
            ),
        )
        server.start()
        server.client.initialize()
        return server
    }

    private fun callAndParse(prompt: String = "test"): CodexResultShape {
        val response = server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", prompt)
            put("cwd", tempDir.toFile().canonicalPath)
        })
        val text = response["result"]!!.jsonObject["content"]!!
            .jsonArray.first().jsonObject["text"]!!.jsonPrimitive.contentOrNull!!
        return json.decodeFromString(CodexResultShape.serializer(), text)
    }

    @Test
    fun `large stdout is truncated and stdoutTruncated is true`() {
        // fake-codex writes ~120,000 chars; server limit is 60,000
        startServer("large-stdout", maxOutput = 60_000)
        val result = callAndParse("produce large stdout")

        assertTrue(result.stdoutTruncated, "stdoutTruncated should be true")
        assertTrue(result.stdout.length <= 60_000,
            "Stdout length ${result.stdout.length} should not exceed 60,000")
    }

    @Test
    fun `large stderr is truncated and stderrTruncated is true`() {
        startServer("large-stderr", maxOutput = 60_000)
        val result = callAndParse("produce large stderr")

        assertTrue(result.stderrTruncated, "stderrTruncated should be true")
        assertTrue(result.stderr.length <= 60_000,
            "Stderr length ${result.stderr.length} should not exceed 60,000")
    }

    @Test
    fun `normal output sets truncation flags to false`() {
        startServer("success")
        val result = callAndParse()

        assertFalse(result.stdoutTruncated, "stdoutTruncated should be false for small output")
        assertFalse(result.stderrTruncated, "stderrTruncated should be false for small output")
    }
}
