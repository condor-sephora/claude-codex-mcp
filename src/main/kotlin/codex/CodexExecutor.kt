package codex

import config.AppConfig
import security.EnvironmentPolicy
import security.OutputRedactor
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Executes the Codex CLI subprocess safely.
 *
 * Security guarantees:
 *   - Uses [ProcessBuilder] with an explicit argument list — NO shell execution.
 *   - Environment is filtered through [EnvironmentPolicy.buildEnv] before spawning.
 *   - Stdout/stderr are read concurrently in background threads to prevent pipe deadlock.
 *   - Process is killed (then force-killed) when the configured timeout elapses.
 *   - All output is bounded to [AppConfig.maxOutputChars] and redacted by [OutputRedactor].
 *
 * This class is intentionally a thin execution layer. All security decisions are made
 * before reaching this class (by [security.SecurityPolicy]).
 */
object CodexExecutor {

    /**
     * Executes Codex and returns a bounded, redacted [CodexResult].
     *
     * This function blocks the calling thread (use from a background dispatcher).
     */
    fun execute(request: CodexExecutionRequest, config: AppConfig): CodexResult {
        val command = CodexCommand.build(request, config)
        val env = EnvironmentPolicy.buildEnv(config)
        val workingDir = File(request.cwd)
        val commandPreview = CodexCommand.preview(request, config)

        val startMs = System.currentTimeMillis()

        val process = try {
            ProcessBuilder(command)
                .directory(workingDir)
                .apply {
                    // Replace the subprocess environment entirely — do NOT inherit parent env.
                    environment().clear()
                    environment().putAll(env)
                    // Redirect stdin to /dev/null so the subprocess never blocks waiting
                    // for input on the MCP server's own stdin (the JSON-RPC pipe).
                    redirectInput(java.io.File("/dev/null"))
                }
                .start()
        } catch (e: IOException) {
            val durationMs = System.currentTimeMillis() - startMs
            return errorResult(
                message = "Failed to start Codex process: ${e.message}",
                commandPreview = commandPreview,
                request = request,
                durationMs = durationMs,
            )
        }

        // Read stdout and stderr concurrently in separate threads.
        // Reading both streams simultaneously prevents the subprocess from blocking
        // when the OS pipe buffer fills up (which would cause a deadlock if we read
        // one stream to completion before the other).
        val stdoutFuture: CompletableFuture<String> = CompletableFuture.supplyAsync {
            readStream(process.inputStream)
        }
        val stderrFuture: CompletableFuture<String> = CompletableFuture.supplyAsync {
            readStream(process.errorStream)
        }

        // Wait for the process with a timeout.
        val finished = try {
            process.waitFor(request.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        val timedOut = !finished
        if (timedOut) {
            // Graceful termination first.
            process.destroy()
            // Give the process up to 3 seconds to exit cleanly.
            val exitedGracefully = try {
                process.waitFor(3, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!exitedGracefully) {
                // Force kill — this always succeeds on POSIX and Windows.
                process.destroyForcibly()
            }
        }

        val durationMs = System.currentTimeMillis() - startMs

        // Collect output now that the process has exited (or been killed).
        // These futures complete quickly because the subprocess streams are closed.
        val rawStdout = try {
            stdoutFuture.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            ""
        }
        val rawStderr = try {
            stderrFuture.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            ""
        }

        val (stdout, stdoutTruncated) = OutputRedactor.boundAndRedact(rawStdout, config.maxOutputChars)
        val (stderr, stderrTruncated) = OutputRedactor.boundAndRedact(rawStderr, config.maxOutputChars)

        val exitCode = if (timedOut) -1 else try {
            process.exitValue()
        } catch (_: IllegalThreadStateException) {
            -1
        }

        return CodexResult(
            exitCode = exitCode,
            timedOut = timedOut,
            durationMs = durationMs,
            stdout = stdout,
            stderr = stderr,
            stdoutTruncated = stdoutTruncated,
            stderrTruncated = stderrTruncated,
            commandPreview = commandPreview,
            workingDirectory = request.cwd,
            sandbox = request.sandbox.value,
            approvalModeApplied = request.approvalMode.value,
            approvalModeWarning = codex.ApprovalMode.UNSUPPORTED_WARNING,
            taskId = request.taskId,
            phase = request.phase?.value,
            metadata = request.metadata,
            securityWarnings = emptyList(),
        )
    }

    // ---------- Helpers ----------

    private fun readStream(stream: java.io.InputStream): String = try {
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: IOException) {
        ""
    }

    /** Constructs a result representing a startup failure before the process ran. */
    private fun errorResult(
        message: String,
        commandPreview: String,
        request: CodexExecutionRequest,
        durationMs: Long,
    ): CodexResult = CodexResult(
        exitCode = -1,
        timedOut = false,
        durationMs = durationMs,
        stdout = "",
        stderr = message,
        stdoutTruncated = false,
        stderrTruncated = false,
        commandPreview = commandPreview,
        workingDirectory = request.cwd,
        sandbox = request.sandbox.value,
        approvalModeApplied = request.approvalMode.value,
        approvalModeWarning = codex.ApprovalMode.UNSUPPORTED_WARNING,
        taskId = request.taskId,
        phase = request.phase?.value,
        metadata = request.metadata,
        securityWarnings = listOf(message),
    )
}
