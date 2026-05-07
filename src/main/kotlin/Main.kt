import config.EnvConfigLoader
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import logging.AuditLogger
import mcp.CodexMcpServer

/**
 * Entry point for the claude-codex-mcp stdio MCP server.
 *
 * IMPORTANT: stdout is reserved exclusively for the MCP JSON-RPC transport stream.
 * Application logging (via SLF4J) is configured to write to stderr in
 * src/main/resources/simplelogger.properties. Never write to System.out directly.
 */
fun main() {
    // Load configuration from environment variables. Fails fast if config is invalid.
    val config = try {
        EnvConfigLoader.load()
    } catch (e: Exception) {
        System.err.println("ERROR: Failed to load configuration: ${e.message}")
        System.exit(1)
        return
    }

    // Initialize audit logger (stderr or configured file path).
    AuditLogger.init(config.auditLogPath)

    // Build the MCP server with the execute_codex tool registered.
    val server = CodexMcpServer.build(config)

    // Connect to the MCP stdio transport and run until stdin closes.
    // StdioServerTransport reads JSON-RPC from System.in and writes to System.out.
    // All application logs are directed to System.err (see simplelogger.properties).
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
}
