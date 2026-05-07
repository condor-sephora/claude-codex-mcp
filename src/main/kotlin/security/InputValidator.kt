package security

import config.AppConfig

object InputValidator {

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
        Regex("""(?i)curl\s+.*\|\s*sh"""),
        Regex("""(?i)wget\s+.*\|\s*(?:bash|sh)"""),
    )

    private const val MAX_TASK_ID_CHARS = 128

    sealed class ValidationResult {
        data object Ok : ValidationResult()
        data class Rejected(val reason: String) : ValidationResult()
    }

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
        if (!taskId.all { it.code in 0x20..0x7E }) {
            return ValidationResult.Rejected("taskId contains non-printable characters")
        }
        return ValidationResult.Ok
    }

    fun resolveTimeout(requestedMs: Long?, config: AppConfig): Long {
        val requested = requestedMs ?: return config.timeoutMs
        return requested.coerceIn(5_000L, 600_000L)
    }
}
