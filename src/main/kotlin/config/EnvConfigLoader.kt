package config

import java.io.File

object EnvConfigLoader {

    private const val DEFAULT_TIMEOUT_MS = 120_000L
    private const val DEFAULT_MAX_PROMPT_CHARS = 8_000
    private const val DEFAULT_MAX_OUTPUT_CHARS = 60_000

    private const val DEFAULT_MAX_REQUEST_FILE_BYTES = 200L * 1024L          // 200 KB
    private const val DEFAULT_MAX_EXTRA_INSTRUCTIONS_CHARS = 4_000
    private const val DEFAULT_INTAKE_TIMEOUT_MS = 900_000L                   // 15 min
    private const val DEFAULT_MAX_INTAKE_TIMEOUT_MS = 1_800_000L             // 30 min
    private const val MIN_INTAKE_TIMEOUT_MS = 5_000L

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

        // ---------- Intake mode ----------

        val allowedRoots = env["CODEX_MCP_ALLOWED_ROOTS"]
            ?.split(File.pathSeparator)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.map { canonicalize(it) }
            ?: emptyList()

        val maxRequestFileBytes = env["CODEX_MCP_MAX_REQUEST_FILE_BYTES"]?.toLongOrNull()
            ?: DEFAULT_MAX_REQUEST_FILE_BYTES
        require(maxRequestFileBytes > 0) { "CODEX_MCP_MAX_REQUEST_FILE_BYTES must be positive" }

        val maxExtraInstructionsChars = env["CODEX_MCP_MAX_EXTRA_INSTRUCTIONS_CHARS"]?.toIntOrNull()
            ?: DEFAULT_MAX_EXTRA_INSTRUCTIONS_CHARS
        require(maxExtraInstructionsChars >= 0) {
            "CODEX_MCP_MAX_EXTRA_INSTRUCTIONS_CHARS must be non-negative"
        }

        val defaultIntakeTimeoutMs = env["CODEX_MCP_DEFAULT_TIMEOUT_MS"]?.toLongOrNull()
            ?: DEFAULT_INTAKE_TIMEOUT_MS
        require(defaultIntakeTimeoutMs >= MIN_INTAKE_TIMEOUT_MS) {
            "CODEX_MCP_DEFAULT_TIMEOUT_MS must be >= $MIN_INTAKE_TIMEOUT_MS"
        }

        val maxIntakeTimeoutMs = env["CODEX_MCP_MAX_TIMEOUT_MS"]?.toLongOrNull()
            ?: DEFAULT_MAX_INTAKE_TIMEOUT_MS
        require(maxIntakeTimeoutMs >= defaultIntakeTimeoutMs) {
            "CODEX_MCP_MAX_TIMEOUT_MS ($maxIntakeTimeoutMs) must be >= " +
                "CODEX_MCP_DEFAULT_TIMEOUT_MS ($defaultIntakeTimeoutMs)"
        }

        return AppConfig(
            timeoutMs = timeoutMs,
            maxPromptChars = maxPromptChars,
            maxOutputChars = maxOutputChars,
            allowDangerFullAccess = allowDanger,
            envPassthroughAllowlist = envPassthrough,
            auditLogPath = auditLogPath,
            allowedRoots = allowedRoots,
            maxRequestFileBytes = maxRequestFileBytes,
            maxExtraInstructionsChars = maxExtraInstructionsChars,
            defaultIntakeTimeoutMs = defaultIntakeTimeoutMs,
            maxIntakeTimeoutMs = maxIntakeTimeoutMs,
        )
    }

    private fun canonicalize(path: String): String =
        try { File(path).canonicalPath } catch (_: Exception) { File(path).absolutePath }
}
