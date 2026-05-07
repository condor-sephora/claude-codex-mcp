package codex

import kotlinx.serialization.Serializable

@Serializable
data class CodexResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val durationMs: Long,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val commandPreview: String,
    val workingDirectory: String,
    val sandbox: String,
    val taskId: String?,
)
