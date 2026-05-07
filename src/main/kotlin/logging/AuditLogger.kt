package logging

import codex.CodexExecutionRequest
import codex.CodexResult
import java.io.FileOutputStream
import java.io.PrintStream
import java.security.MessageDigest
import java.time.Instant

/**
 * Writes a structured audit log entry for every tool invocation.
 *
 * Security invariants:
 *   - Raw prompts are NEVER written to the audit log.
 *   - Only the prompt length and a SHA-256 hash of the prompt are recorded.
 *   - Environment variable values are NEVER written.
 *   - All output is written to stderr (or a configured file) — NEVER to stdout.
 *
 * Log format is single-line key=value pairs per invocation so that tools like
 * `grep`, `jq` (when structured), or a log aggregator can process them easily.
 */
object AuditLogger {

    @Volatile
    private var auditOut: PrintStream? = null

    /** Initialize the audit logger. Call once at server startup. */
    fun init(auditLogPath: String?) {
        auditOut = if (auditLogPath != null) {
            PrintStream(FileOutputStream(auditLogPath, /* append= */ true), /* autoFlush= */ true)
        } else {
            // Default: write audit logs to stderr. This keeps stdout clean for MCP transport.
            System.err
        }
    }

    /**
     * Logs an invocation result. Never call this before [init].
     *
     * Fields written:
     *   timestamp, event, taskId, phase, cwd, sandbox, timeoutMs,
     *   promptHash, promptLength, exitCode, timedOut, durationMs,
     *   stdoutTruncated, stderrTruncated
     */
    fun logInvocation(request: CodexExecutionRequest, result: CodexResult) {
        val out = auditOut ?: System.err
        val fields = buildString {
            append("timestamp=").append(Instant.now())
            append(" event=codex_invocation")
            append(" taskId=").append(request.taskId ?: "none")
            append(" phase=").append(request.phase?.value ?: "none")
            append(" cwd=").append(sanitizeForLog(request.cwd))
            append(" sandbox=").append(request.sandbox.value)
            append(" timeoutMs=").append(request.timeoutMs)
            // Record only a hash of the prompt — never the raw content.
            append(" promptHash=").append(sha256Short(request.prompt))
            append(" promptLength=").append(request.prompt.length)
            append(" exitCode=").append(result.exitCode)
            append(" timedOut=").append(result.timedOut)
            append(" durationMs=").append(result.durationMs)
            append(" stdoutTruncated=").append(result.stdoutTruncated)
            append(" stderrTruncated=").append(result.stderrTruncated)
        }
        // Prefix with AUDIT so log shippers can filter on this tag.
        out.println("AUDIT $fields")
    }

    /**
     * Logs a security policy rejection without including sensitive detail.
     */
    fun logRejection(reason: String, taskId: String?) {
        val out = auditOut ?: System.err
        val fields = buildString {
            append("timestamp=").append(Instant.now())
            append(" event=security_rejection")
            append(" taskId=").append(taskId ?: "none")
            // Reason is already a pre-validated, non-secret policy message.
            append(" reason=").append(sanitizeForLog(reason))
        }
        out.println("AUDIT $fields")
    }

    // ---------- Helpers ----------

    /** Returns the first 16 hex characters of the SHA-256 hash of [text]. */
    private fun sha256Short(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Removes newlines and control characters to prevent log injection. */
    private fun sanitizeForLog(value: String): String =
        value.replace(Regex("""[\r\n\t]"""), " ").take(256)
}
