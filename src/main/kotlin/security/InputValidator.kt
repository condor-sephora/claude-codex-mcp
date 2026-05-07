package security

import config.AppConfig

/**
 * Pure validation functions for prompt, taskId, and metadata.
 *
 * All functions are side-effect-free and return a [ValidationResult] rather than
 * throwing exceptions so that the caller can decide how to handle failures cleanly.
 */
object InputValidator {

    /**
     * Heuristic patterns for obvious secret-exfiltration or security-bypass intent.
     *
     * This is a defense-in-depth heuristic, NOT a complete security system.
     * It catches low-effort attacks and documents the threat model.
     * A determined adversary who avoids these exact strings is not stopped by this check;
     * the OS-level sandbox, path policy, and environment allowlist are the primary controls.
     */
    private val DANGEROUS_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)print\s+env"""),
        Regex("""(?i)dump\s+secret"""),
        Regex("""(?i)cat\s+.*\.ssh"""),
        Regex("""(?i)read\s+\.env"""),
        Regex("""(?i)upload\s+token"""),
        Regex("""(?i)send\s+token"""),
        Regex("""(?i)exfiltrate"""),
        Regex("""(?i)ignore\s+security\s+policy"""),
        Regex("""(?i)bypass\s+sandbox"""),
        Regex("""(?i)override\s+security"""),
        Regex("""(?i)cat\s+.*\.gnupg"""),
        Regex("""(?i)curl\s+.*\|\s*sh"""),           // curl-pipe-sh pattern
        Regex("""(?i)wget\s+.*\|\s*(?:bash|sh)"""),  // wget-pipe-bash
    )

    private const val MAX_TASK_ID_CHARS = 128
    private const val MAX_METADATA_ENTRIES = 20
    private const val MAX_METADATA_KEY_CHARS = 64
    private const val MAX_METADATA_VALUE_CHARS = 512

    sealed class ValidationResult {
        data object Ok : ValidationResult()
        data class Rejected(val reason: String) : ValidationResult()
    }

    /** Validates the prompt string against configured limits and heuristic rules. */
    fun validatePrompt(prompt: String?, config: AppConfig): ValidationResult {
        if (prompt == null || prompt.isBlank()) {
            return ValidationResult.Rejected("Prompt must not be empty")
        }
        if (prompt.length > config.maxPromptChars) {
            return ValidationResult.Rejected(
                "Prompt length ${prompt.length} exceeds maximum ${config.maxPromptChars} characters"
            )
        }
        val match = DANGEROUS_PATTERNS.firstOrNull { it.containsMatchIn(prompt) }
        if (match != null) {
            return ValidationResult.Rejected(
                "Prompt was rejected by the defense-in-depth heuristic filter. " +
                    "Remove content that resembles secret exfiltration or security-policy bypass."
            )
        }
        return ValidationResult.Ok
    }

    /** Validates and bounds the caller-provided taskId. */
    fun validateTaskId(taskId: String?): ValidationResult {
        if (taskId == null) return ValidationResult.Ok
        if (taskId.isBlank()) {
            return ValidationResult.Rejected("taskId must not be blank if provided")
        }
        if (taskId.length > MAX_TASK_ID_CHARS) {
            return ValidationResult.Rejected(
                "taskId length ${taskId.length} exceeds maximum $MAX_TASK_ID_CHARS characters"
            )
        }
        // Only allow printable ASCII to prevent log injection.
        if (!taskId.all { it.code in 0x20..0x7E }) {
            return ValidationResult.Rejected("taskId contains non-printable characters")
        }
        return ValidationResult.Ok
    }

    /** Validates and bounds the caller-provided metadata map. */
    fun validateMetadata(metadata: Map<String, String>?): ValidationResult {
        if (metadata == null) return ValidationResult.Ok
        if (metadata.size > MAX_METADATA_ENTRIES) {
            return ValidationResult.Rejected(
                "metadata has ${metadata.size} entries; maximum is $MAX_METADATA_ENTRIES"
            )
        }
        for ((k, v) in metadata) {
            if (k.length > MAX_METADATA_KEY_CHARS) {
                return ValidationResult.Rejected("metadata key '$k' exceeds $MAX_METADATA_KEY_CHARS chars")
            }
            if (v.length > MAX_METADATA_VALUE_CHARS) {
                return ValidationResult.Rejected(
                    "metadata value for key '$k' exceeds $MAX_METADATA_VALUE_CHARS chars"
                )
            }
        }
        return ValidationResult.Ok
    }

    /** Validates that all extra args are in the configured allowlist. */
    fun validateExtraArgs(args: List<String>, config: AppConfig): ValidationResult {
        if (args.isEmpty()) return ValidationResult.Ok
        if (!config.allowExtraArgs) {
            return ValidationResult.Rejected(
                "extraArgs are disabled. Set CODEX_MCP_ALLOW_EXTRA_ARGS=true to enable."
            )
        }
        val disallowed = args.filterNot { it in config.extraArgsAllowlist }
        if (disallowed.isNotEmpty()) {
            return ValidationResult.Rejected(
                "extraArgs contains disallowed flags: $disallowed. " +
                    "Allowed: ${config.extraArgsAllowlist}"
            )
        }
        return ValidationResult.Ok
    }

    /**
     * Validates a requested timeout value against the configured bounds.
     *
     * @param requestedMs  null → use the server default.
     * @return             The timeout to use (clamped to bounds if null).
     */
    fun resolveTimeout(requestedMs: Long?, config: AppConfig): Long {
        val requested = requestedMs ?: return config.defaultTimeoutMs
        return requested.coerceIn(config.minTimeoutMs, config.maxTimeoutMs)
    }
}
