package config

data class AppConfig(
    val timeoutMs: Long,
    val maxPromptChars: Int,
    val maxOutputChars: Int,
    val allowDangerFullAccess: Boolean,
    val envPassthroughAllowlist: Set<String>,
    val auditLogPath: String?,

    // ---------- Intake mode (code_intake tool) ----------

    /**
     * Optional canonical absolute paths that constrain which directories may be passed
     * as `cwd` to any tool. When empty, no allowed-root restriction is enforced (preserves
     * the historical behavior of execute_codex). When non-empty, every `cwd` must be equal
     * to or a descendant of one of these roots.
     */
    val allowedRoots: List<String>,

    /** Maximum size in bytes of an intake request file. Default 200 KB. */
    val maxRequestFileBytes: Long,

    /** Maximum length of the optional `extraInstructions` field in intake calls. */
    val maxExtraInstructionsChars: Int,

    /** Default timeout (ms) for intake calls when the caller does not supply one. */
    val defaultIntakeTimeoutMs: Long,

    /** Hard upper bound (ms) for any intake call's timeout. */
    val maxIntakeTimeoutMs: Long,
)
