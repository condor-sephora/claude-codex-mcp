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
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Integration tests for timeout enforcement.
 *
 * Uses fake-codex with FAKE_CODEX_MODE=timeout to simulate a long-running process.
 */
class TimeoutIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarPath: String by lazy { System.getProperty("mcp.jar.path") ?: error("mcp.jar.path not set") }
    private val fakeCodexPath: String by lazy { System.getProperty("fake.codex.path") ?: error("fake.codex.path not set") }
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var server: McpServerProcess

    @AfterEach
    fun tearDown() { server.close() }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `timeout kills process and sets timedOut=true`() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
            allowedRoot = tempDir.toFile().canonicalPath,
            extraEnv = mapOf(
                "FAKE_CODEX_MODE" to "timeout",
                // Set a short timeout so the test doesn't take 300 seconds
                "CODEX_MCP_DEFAULT_TIMEOUT_MS" to "2000",
                "CODEX_MCP_MIN_TIMEOUT_MS" to "500",
                "CODEX_MCP_MAX_TIMEOUT_MS" to "30000",
            ),
        )
        server.start()
        server.client.initialize()

        val response = server.client.callTool("execute_codex", buildJsonObject {
            put("prompt", "run a long task")
            put("cwd", tempDir.toFile().canonicalPath)
            put("timeoutMs", 2000L)
        })

        val text = response["result"]!!.jsonObject["content"]!!
            .jsonArray.first().jsonObject["text"]!!.jsonPrimitive.contentOrNull!!
        val result = json.decodeFromString(CodexResultShape.serializer(), text)

        assertTrue(result.timedOut, "timedOut should be true when process is killed")
        assertEquals(-1, result.exitCode, "exitCode should be -1 when timed out")
        assertTrue(result.durationMs >= 1500, "durationMs should reflect actual wait time (was: ${result.durationMs})")
    }
}
