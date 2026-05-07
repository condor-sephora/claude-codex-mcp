package config

data class AppConfig(
    val timeoutMs: Long,
    val maxPromptChars: Int,
    val maxOutputChars: Int,
    val allowDangerFullAccess: Boolean,
    val envPassthroughAllowlist: Set<String>,
    val auditLogPath: String?,
)
