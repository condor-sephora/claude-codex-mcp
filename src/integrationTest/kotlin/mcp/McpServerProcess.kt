package mcp

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle of the MCP server subprocess in integration tests.
 *
 * Creates a temp directory containing a "codex" symlink pointing to fake-codex.sh
 * and prepends it to PATH, so the server resolves the fake binary via normal PATH lookup.
 */
class McpServerProcess(
    private val jarPath: String,
    private val fakeCodexPath: String,
    private val extraEnv: Map<String, String> = emptyMap(),
) : AutoCloseable {

    private lateinit var process: Process
    private lateinit var fakeCodexDir: File
    lateinit var client: McpTestClient
    val stderrLines = mutableListOf<String>()

    fun start() {
        // Create a temp dir with a symlink "codex" -> fake-codex.sh so PATH resolution works.
        fakeCodexDir = Files.createTempDirectory("fake-codex-bin").toFile()
        val codexLink = File(fakeCodexDir, "codex")
        Files.createSymbolicLink(codexLink.toPath(), Paths.get(fakeCodexPath))
        codexLink.setExecutable(true)

        // FAKE_CODEX_MODE must be in the subprocess passthrough allowlist so fake-codex.sh
        // receives it. Append if the test provides an explicit allowlist without it.
        val basePassthrough = extraEnv["CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST"] ?: "PATH,HOME"
        val passthrough = if ("FAKE_CODEX_MODE" in basePassthrough.split(",").map { it.trim() }) {
            basePassthrough
        } else {
            "$basePassthrough,FAKE_CODEX_MODE"
        }

        val systemPath = System.getenv("PATH") ?: "/usr/bin:/bin"
        val env = buildMap {
            put("PATH", "${fakeCodexDir.absolutePath}:$systemPath")
            put("HOME", System.getenv("HOME") ?: "/tmp")
            put("CODEX_MCP_TIMEOUT_MS", "10000")
            put("CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST", passthrough)
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

        Thread {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderrLines) { stderrLines.add(line) }
            }
        }.also { it.isDaemon = true }.start()

        client = McpTestClient(
            serverIn = process.outputStream,
            serverOut = process.inputStream,
        )

        Thread.sleep(500)
    }

    fun getStderr(): List<String> = synchronized(stderrLines) { stderrLines.toList() }

    override fun close() {
        try {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
        } catch (_: Exception) { }
        if (::fakeCodexDir.isInitialized) fakeCodexDir.deleteRecursively()
    }
}
