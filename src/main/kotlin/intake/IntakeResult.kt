package intake

import kotlinx.serialization.Serializable

/**
 * Structured response returned to the MCP client for an intake invocation.
 *
 * Mirrors codex.CodexResult plus intake-specific metadata so the caller can
 * tell which mode produced the result and persist it alongside the request file.
 */
@Serializable
data class IntakeResult(
    val mode: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val durationMs: Long,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val commandPreview: String,
    val workingDirectory: String,
    val requestFile: String,
    val outputFormat: String,
    val sandbox: String,
    val taskId: String?,
)
