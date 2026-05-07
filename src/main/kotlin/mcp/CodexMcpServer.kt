package mcp

import config.AppConfig
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Builds and configures the MCP [Server] instance.
 *
 * This class is intentionally thin — it only wires the MCP SDK together and
 * delegates tool registration to [ExecuteCodexTool]. All business logic lives
 * in the packages it delegates to.
 */
object CodexMcpServer {

    private const val SERVER_NAME = "claude-codex-mcp"
    private const val SERVER_VERSION = "1.0.0"

    /**
     * Creates a fully configured [Server] ready to be connected to a transport.
     *
     * The server exposes exactly one tool: [ExecuteCodexTool.TOOL_NAME].
     * This single-responsibility design makes the security model straightforward to audit.
     */
    fun build(config: AppConfig): Server {
        val server = Server(
            serverInfo = Implementation(
                name = SERVER_NAME,
                version = SERVER_VERSION,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        ExecuteCodexTool.register(server, config)

        return server
    }
}
