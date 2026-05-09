package intake

/**
 * Validated, immutable input passed to the intake executor.
 *
 * All fields are guaranteed safe by [security.IntakeSecurityPolicy]:
 *   - [cwd] is canonical, exists, and is inside any configured allowed roots.
 *   - [requestFilePath] is canonical, resolves inside [cwd], passes extension/size/sensitivity checks.
 *   - [requestFileRelative] is the cwd-relative path used in the prompt and result.
 *   - [extraInstructions] (if non-null) is bounded by the configured maximum length.
 *   - [timeoutMs] is clamped to the intake bounds in AppConfig.
 *   - [taskId] (if non-null) is printable ASCII, ≤128 chars.
 */
data class IntakeRequest(
    val cwd: String,
    val requestFilePath: String,
    val requestFileRelative: String,
    val outputFormat: IntakeOutputFormat,
    val extraInstructions: String?,
    val timeoutMs: Long,
    val taskId: String?,
)
