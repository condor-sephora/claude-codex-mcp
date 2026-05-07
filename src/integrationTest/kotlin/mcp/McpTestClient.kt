package mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter

/**
 * Minimal MCP protocol client for integration tests.
 *
 * The MCP stdio transport uses newline-delimited JSON-RPC 2.0.
 * Each message is a single line of JSON terminated by '\n'.
 *
 * This client is not production-grade — it is purpose-built for deterministic
 * integration testing of the MCP server.
 */
class McpTestClient(
    private val serverIn: OutputStream,    // We write to the server's stdin
    private val serverOut: InputStream,    // We read from the server's stdout
) {
    private val writer = PrintWriter(serverIn.writer(), /* autoFlush= */ true)
    private val reader = BufferedReader(InputStreamReader(serverOut))
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private var nextId = 1

    // ---------- Protocol lifecycle ----------

    /**
     * Performs the MCP handshake (initialize + initialized notification).
     * Must be called before any tool calls.
     */
    fun initialize(): JsonObject {
        val response = sendRequest(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        )
        // Send the initialized notification (no response expected)
        sendNotification("notifications/initialized")
        return response
    }

    /** Lists all tools registered on the server. */
    fun listTools(): JsonObject {
        return sendRequest(method = "tools/list")
    }

    /**
     * Calls a tool by name with the provided arguments.
     *
     * @param name      Tool name (e.g. "execute_codex")
     * @param arguments Key-value pairs for the tool input.
     */
    fun callTool(name: String, arguments: JsonObject): JsonObject {
        return sendRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            }
        )
    }

    // ---------- Low-level protocol ----------

    private fun sendRequest(method: String, params: JsonObject? = null): JsonObject {
        val id = nextId++
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        writer.println(message.toString())
        writer.flush()

        // Read lines until we find the response matching our id.
        repeat(50) {  // At most 50 lines to find our response
            val line = reader.readLine() ?: return@repeat
            if (line.isBlank()) return@repeat
            val parsed = try {
                json.parseToJsonElement(line).let { it as? JsonObject } ?: return@repeat
            } catch (_: Exception) {
                return@repeat
            }
            val responseId = parsed["id"]?.toString()?.trim('"')?.toIntOrNull()
            if (responseId == id) return parsed
        }
        throw AssertionError("Did not receive response for method=$method id=$id within 50 lines")
    }

    private fun sendNotification(method: String, params: JsonObject? = null) {
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        writer.println(message.toString())
        writer.flush()
    }

    /** Reads a single line from the server (for assertions on log output, etc.). */
    fun readLineOrNull(timeoutMs: Long = 1000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                return reader.readLine()
            }
            Thread.sleep(50)
        }
        return null
    }
}
