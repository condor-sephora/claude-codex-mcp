package config

/**
 * Immutable application configuration resolved from environment variables at startup.
 *
 * All security-relevant thresholds live here so that validation functions can be
 * pure functions of (input, config) without hidden global state.
 *
 * @property codexPath              Absolute path to the `codex` executable.
 * @property allowedRoots           Canonical absolute paths that `cwd` must be inside.
 * @property defaultTimeoutMs       Default process timeout.
 * @property minTimeoutMs           Minimum timeout callers may request.
 * @property maxTimeoutMs           Maximum timeout callers may request.
 * @property maxPromptChars         Maximum length of the raw prompt string.
 * @property maxOutputChars         Maximum combined chars captured per stdout/stderr stream.
 * @property allowDangerFullAccess  Must be true to allow [codex.SandboxMode.DANGER_FULL_ACCESS].
 * @property allowExtraArgs         Must be true to pass any [extraArgs] to Codex.
 * @property extraArgsAllowlist     Only these flags are allowed when extra args are enabled.
 * @property envPassthroughAllowlist  Env-var names forwarded into the Codex subprocess.
 * @property auditLogPath           Optional file path for audit logs; null → System.err.
 */
data class AppConfig(
    val codexPath: String,
    val allowedRoots: List<String>,
    val defaultTimeoutMs: Long,
    val minTimeoutMs: Long,
    val maxTimeoutMs: Long,
    val maxPromptChars: Int,
    val maxOutputChars: Int,
    val allowDangerFullAccess: Boolean,
    val allowExtraArgs: Boolean,
    val extraArgsAllowlist: Set<String>,
    val envPassthroughAllowlist: Set<String>,
    val auditLogPath: String?,
)
