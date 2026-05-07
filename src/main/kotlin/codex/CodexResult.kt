package codex

import kotlinx.serialization.Serializable

/**
 * Structured, bounded, and redacted result returned to the MCP caller after Codex execution.
 *
 * All string fields that originate from Codex output have been:
 *   1. Truncated to [config.AppConfig.maxOutputChars] per stream.
 *   2. Redacted by [security.OutputRedactor] to remove common secret patterns.
 *
 * Callers (Claude Code) must treat [exitCode], [timedOut], and [securityWarnings] as the
 * primary signals for whether the execution succeeded.
 */
@Serializable
data class CodexResult(
    /** Process exit code, or -1 when [timedOut] is true. */
    val exitCode: Int,

    /** True when the process was killed because it exceeded [timeoutMs]. */
    val timedOut: Boolean,

    /** Wall-clock execution time in milliseconds. */
    val durationMs: Long,

    /** Redacted, bounded stdout from Codex. */
    val stdout: String,

    /** Redacted, bounded stderr from Codex. */
    val stderr: String,

    /** True when stdout was truncated to the configured maximum. */
    val stdoutTruncated: Boolean,

    /** True when stderr was truncated to the configured maximum. */
    val stderrTruncated: Boolean,

    /**
     * Safe command preview for debugging — shows executable and flags but
     * replaces the raw prompt with a shortened, hash-identified placeholder.
     */
    val commandPreview: String,

    /** Canonical absolute path of the working directory used. */
    val workingDirectory: String,

    /** Sandbox mode that was applied. */
    val sandbox: String,

    /** Approval mode that was recorded (not necessarily applied as a CLI flag). */
    val approvalModeApplied: String,

    /**
     * Non-null when the requested approval mode could not be mapped to a Codex CLI flag.
     * Callers should surface this to the developer.
     */
    val approvalModeWarning: String?,

    /** Caller-provided task ID, echo'd for traceability. */
    val taskId: String?,

    /** Caller-provided phase label, echo'd for traceability. */
    val phase: String?,

    /** Caller-provided metadata (bounded, redacted). */
    val metadata: Map<String, String>?,

    /**
     * Non-empty when any defense-in-depth heuristic triggered.
     * These are informational — they do not block execution unless a hard policy
     * check already rejected the request before reaching this point.
     */
    val securityWarnings: List<String>,
)
