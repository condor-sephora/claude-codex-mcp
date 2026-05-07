package config

object EnvConfigLoader {

    private const val DEFAULT_TIMEOUT_MS = 120_000L
    private const val DEFAULT_MAX_PROMPT_CHARS = 8_000
    private const val DEFAULT_MAX_OUTPUT_CHARS = 60_000

    private val DEFAULT_ENV_PASSTHROUGH: Set<String> = setOf(
        "PATH", "HOME", "USER", "LOGNAME", "SHELL",
        "OPENAI_API_KEY",
        "CODEX_HOME",
    )

    fun load(): AppConfig = load(System.getenv())

    fun load(env: Map<String, String>): AppConfig {
        val timeoutMs = env["CODEX_MCP_TIMEOUT_MS"]?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
        require(timeoutMs > 0) { "CODEX_MCP_TIMEOUT_MS must be positive" }

        val maxPromptChars = env["CODEX_MCP_MAX_PROMPT_CHARS"]?.toIntOrNull()
            ?: DEFAULT_MAX_PROMPT_CHARS
        require(maxPromptChars > 0) { "CODEX_MCP_MAX_PROMPT_CHARS must be positive" }

        val maxOutputChars = env["CODEX_MCP_MAX_OUTPUT_CHARS"]?.toIntOrNull()
            ?: DEFAULT_MAX_OUTPUT_CHARS
        require(maxOutputChars > 0) { "CODEX_MCP_MAX_OUTPUT_CHARS must be positive" }

        val allowDanger = env["CODEX_MCP_ALLOW_DANGER_FULL_ACCESS"]
            ?.equals("true", ignoreCase = true) == true

        val envPassthrough = env["CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: DEFAULT_ENV_PASSTHROUGH

        val auditLogPath = env["CODEX_MCP_AUDIT_LOG_PATH"]?.takeIf { it.isNotBlank() }

        return AppConfig(
            timeoutMs = timeoutMs,
            maxPromptChars = maxPromptChars,
            maxOutputChars = maxOutputChars,
            allowDangerFullAccess = allowDanger,
            envPassthroughAllowlist = envPassthrough,
            auditLogPath = auditLogPath,
        )
    }
}
