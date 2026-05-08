package logging

import codex.CodexExecutionRequest
import codex.CodexResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.FileOutputStream
import java.io.PrintStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Writes a structured JSON Lines audit entry for every tool invocation.
 *
 * Security invariants:
 *   - Environment variable values are NEVER written.
 *   - All output goes to stderr (or a configured file) — NEVER to stdout.
 *
 * Each line is prefixed with "AUDIT " followed by a single JSON object so the file
 * can be grepped, streamed, and fed directly to Claude or Codex for pattern analysis.
 *
 * Each invocation entry includes:
 *   - "prompt"  — the raw instruction sent to Codex
 *   - "stdout"  — the bounded, redacted output Codex produced
 *   - "stderr"  — the bounded, redacted stderr Codex produced
 */
object AuditLogger {

    @Volatile
    private var auditOut: PrintStream? = null

    // Groups all calls from the same server process — one session = one JAR lifetime.
    private val sessionId: String = UUID.randomUUID().toString().take(8)

    fun init(auditLogPath: String?) {
        auditOut = if (auditLogPath != null) {
            PrintStream(FileOutputStream(auditLogPath, true), true)
        } else {
            System.err
        }
    }

    fun logInvocation(request: CodexExecutionRequest, result: CodexResult) {
        val out = auditOut ?: System.err
        val outcome = when {
            result.timedOut -> "timeout"
            result.exitCode == 0 -> "success"
            else -> "codex_error"
        }
        val entry = buildJsonObject {
            put("timestamp", Instant.now().toString())
            put("event", "codex_invocation")
            put("sessionId", sessionId)
            put("taskId", request.taskId ?: "none")
            put("outcome", outcome)
            put("exitCode", result.exitCode)
            put("timedOut", result.timedOut)
            put("durationMs", result.durationMs)
            put("sandbox", request.sandbox.value)
            put("cwd", sanitizeForLog(request.cwd))
            put("prompt", request.prompt)
            put("promptHash", sha256Short(request.prompt))
            put("promptLength", request.prompt.length)
            put("timeoutMs", request.timeoutMs)
            put("stdoutChars", result.stdout.length)
            put("stderrChars", result.stderr.length)
            put("stdoutTruncated", result.stdoutTruncated)
            put("stderrTruncated", result.stderrTruncated)
            put("stderrNonEmpty", result.stderr.isNotBlank())
            put("stdout", result.stdout)
            put("stderr", result.stderr)
        }
        out.println("AUDIT $entry")
    }

    fun logRejection(reason: String, taskId: String?) {
        val out = auditOut ?: System.err
        val entry = buildJsonObject {
            put("timestamp", Instant.now().toString())
            put("event", "security_rejection")
            put("sessionId", sessionId)
            put("taskId", taskId ?: "none")
            put("rejectionCategory", rejectionCategory(reason))
            put("reason", sanitizeForLog(reason))
        }
        out.println("AUDIT $entry")
    }

    private fun rejectionCategory(reason: String): String = when {
        reason.contains("empty", ignoreCase = true) ||
            reason.contains("blank", ignoreCase = true) -> "empty_prompt"
        reason.contains("exceeds maximum", ignoreCase = true) -> "prompt_too_long"
        reason.contains("heuristic filter", ignoreCase = true) -> "dangerous_pattern"
        reason.contains("danger-full-access", ignoreCase = true) -> "invalid_sandbox"
        reason.contains("not a directory", ignoreCase = true) ||
            reason.contains("does not exist", ignoreCase = true) -> "cwd_invalid"
        reason.contains("taskId", ignoreCase = true) -> "invalid_task_id"
        else -> "other"
    }

    private fun sha256Short(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun sanitizeForLog(value: String): String =
        value.replace(Regex("""[\r\n\t]"""), " ").take(256)
}
