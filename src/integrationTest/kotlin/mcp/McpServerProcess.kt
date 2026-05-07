package mcp

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle of the MCP server subprocess in integration tests.
 *
 * The server JAR is launched with a configured fake Codex executable and
 * test-specific environment variables. The test communicates with it via
 * stdin/stdout, and stderr is captured for audit log assertions.
 */
class McpServerProcess(
    private val jarPath: String,
    private val fakeCodexPath: String,
    private val allowedRoot: String,
    private val extraEnv: Map<String, String> = emptyMap(),
) : AutoCloseable {

    private lateinit var process: Process
    lateinit var client: McpTestClient
    val stderrLines = mutableListOf<String>()

    fun start() {
        // FAKE_CODEX_MODE must always be in the subprocess passthrough allowlist so that
        // fake-codex.sh receives the mode. If the test provides an explicit allowlist,
        // append FAKE_CODEX_MODE to it (unless already present).
        val basePassthrough = extraEnv["CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST"] ?: "PATH,HOME"
        val passthrough = if ("FAKE_CODEX_MODE" in basePassthrough.split(",").map { it.trim() }) {
            basePassthrough
        } else {
            "$basePassthrough,FAKE_CODEX_MODE"
        }

        val env = buildMap {
            // Minimal safe environment
            put("PATH", System.getenv("PATH") ?: "/usr/bin:/bin")
            put("HOME", System.getenv("HOME") ?: "/tmp")
            // Point to fake Codex
            put("CODEX_PATH", fakeCodexPath)
            // Configure allowed root
            put("CODEX_MCP_ALLOWED_ROOTS", allowedRoot)
            // Test-friendly timeouts
            put("CODEX_MCP_DEFAULT_TIMEOUT_MS", "10000")
            put("CODEX_MCP_MIN_TIMEOUT_MS", "500")
            put("CODEX_MCP_MAX_TIMEOUT_MS", "30000")
            // Ensure FAKE_CODEX_MODE reaches the subprocess
            put("CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST", passthrough)
            // Extra env overrides (CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST already handled above)
            putAll(extraEnv.filterKeys { it != "CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST" })
        }

        val javaPath = ProcessHandle.current().info().command().orElse("java")
        val pb = ProcessBuilder(javaPath, "-jar", jarPath)
            .apply {
                environment().clear()
                environment().putAll(env)
                redirectErrorStream(false)
            }

        process = pb.start()

        // Drain stderr in background to prevent pipe buffer blocking.
        // This also captures audit log lines for assertions.
        Thread {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderrLines) { stderrLines.add(line) }
            }
        }.also { it.isDaemon = true }.start()

        client = McpTestClient(
            serverIn = process.outputStream,
            serverOut = process.inputStream,
        )

        // Allow server to start up.
        Thread.sleep(500)
    }

    fun getStderr(): List<String> = synchronized(stderrLines) { stderrLines.toList() }

    override fun close() {
        try {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
        } catch (_: Exception) { /* best effort */ }
    }
}
