package mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Integration tests for MCP protocol correctness.
 *
 * These tests verify:
 *   - The server starts and responds to MCP initialization.
 *   - Exactly one tool is registered and it is named "execute_codex".
 *   - No application logs corrupt the stdout JSON-RPC stream.
 *
 * All tests use fake-codex.sh instead of the real Codex CLI.
 */
class McpProtocolIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val jarPath: String by lazy {
        System.getProperty("mcp.jar.path")
            ?: error("mcp.jar.path system property not set — run via ./gradlew integrationTest")
    }
    private val fakeCodexPath: String by lazy {
        System.getProperty("fake.codex.path")
            ?: error("fake.codex.path system property not set")
    }

    private lateinit var server: McpServerProcess

    @BeforeEach
    fun setUp() {
        server = McpServerProcess(
            jarPath = jarPath,
            fakeCodexPath = fakeCodexPath,
        )
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `server starts and MCP initialization succeeds`() {
        val response = server.client.initialize()
        val result = response["result"] ?: fail("Expected 'result' key in initialize response")
        assertNotNull(result.jsonObject["serverInfo"], "serverInfo must be present")
    }

    @Test
    fun `tool listing returns exactly one tool`() {
        server.client.initialize()
        val response = server.client.listTools()
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size, "Expected exactly one tool, got: ${tools.map { it.jsonObject["name"] }}")
    }

    @Test
    fun `tool name is exactly execute_codex`() {
        server.client.initialize()
        val response = server.client.listTools()
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        val toolName = tools.first().jsonObject["name"]!!.jsonPrimitive.contentOrNull
        assertEquals("execute_codex", toolName)
    }

    @Test
    fun `tool schema includes expected required field prompt`() {
        server.client.initialize()
        val response = server.client.listTools()
        val tool = response["result"]!!.jsonObject["tools"]!!.jsonArray.first().jsonObject
        val inputSchema = tool["inputSchema"]!!.jsonObject
        val required = inputSchema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        assertNotNull(required, "required array must be present in inputSchema")
        assertTrue(required!!.contains("prompt"), "prompt must be a required field")
    }

    @Test
    fun `no application logs are written to stdout (stdout is clean JSON-RPC only)`() {
        server.client.initialize()
        val toolsResponse = server.client.listTools()
        // Both responses must be valid JSON (not corrupted by log output)
        assertNotNull(toolsResponse["result"], "listTools response must have a result field")
        // A corrupted response would not parse correctly and would fail earlier assertions
    }
}
