package config

import java.io.File

/**
 * Loads [AppConfig] from environment variables.
 *
 * All parsing is pure and side-effect-free so it is straightforward to unit-test
 * by injecting a custom env map.
 */
object EnvConfigLoader {

    // ---------- Defaults ----------
    private const val DEFAULT_TIMEOUT_MS = 120_000L
    private const val DEFAULT_MIN_TIMEOUT_MS = 5_000L
    private const val DEFAULT_MAX_TIMEOUT_MS = 600_000L
    private const val DEFAULT_MAX_PROMPT_CHARS = 8_000
    private const val DEFAULT_MAX_OUTPUT_CHARS = 60_000

    /** Minimum set of env vars forwarded into the Codex subprocess by default. */
    private val DEFAULT_ENV_PASSTHROUGH: Set<String> = setOf(
        "PATH", "HOME", "USER", "LOGNAME", "SHELL",
        "OPENAI_API_KEY",   // only forwarded if present in the parent env
        "CODEX_HOME",       // only forwarded if present in the parent env
    )

    /**
     * Allowlist of extra Codex CLI flags that are considered safe when
     * CODEX_MCP_ALLOW_EXTRA_ARGS=true. Expand only after careful review.
     */
    private val DEFAULT_EXTRA_ARGS_ALLOWLIST: Set<String> = setOf(
        "--quiet",
        "--verbose",
        "--no-color",
    )

    /**
     * Load configuration from the real process environment.
     *
     * Fails fast if any required configuration is invalid so the server never
     * starts in an ambiguously configured state.
     */
    fun load(): AppConfig = load(System.getenv())

    /**
     * Load configuration from the supplied [env] map (enables deterministic unit testing).
     */
    fun load(env: Map<String, String>): AppConfig {
        val codexPath = env["CODEX_PATH"] ?: resolveCodexOnPath()

        val allowedRoots = parseAllowedRoots(env["CODEX_MCP_ALLOWED_ROOTS"])
        val canonicalRoots = canonicalizeRoots(allowedRoots)

        val defaultTimeout = env["CODEX_MCP_DEFAULT_TIMEOUT_MS"]?.toLongOrNull()
            ?: DEFAULT_TIMEOUT_MS
        val minTimeout = env["CODEX_MCP_MIN_TIMEOUT_MS"]?.toLongOrNull()
            ?: DEFAULT_MIN_TIMEOUT_MS
        val maxTimeout = env["CODEX_MCP_MAX_TIMEOUT_MS"]?.toLongOrNull()
            ?: DEFAULT_MAX_TIMEOUT_MS

        require(minTimeout > 0) { "CODEX_MCP_MIN_TIMEOUT_MS must be positive" }
        require(maxTimeout >= minTimeout) {
            "CODEX_MCP_MAX_TIMEOUT_MS ($maxTimeout) must be >= CODEX_MCP_MIN_TIMEOUT_MS ($minTimeout)"
        }
        require(defaultTimeout in minTimeout..maxTimeout) {
            "CODEX_MCP_DEFAULT_TIMEOUT_MS ($defaultTimeout) must be in [$minTimeout, $maxTimeout]"
        }

        val maxPromptChars = env["CODEX_MCP_MAX_PROMPT_CHARS"]?.toIntOrNull()
            ?: DEFAULT_MAX_PROMPT_CHARS
        require(maxPromptChars > 0) { "CODEX_MCP_MAX_PROMPT_CHARS must be positive" }

        val maxOutputChars = env["CODEX_MCP_MAX_OUTPUT_CHARS"]?.toIntOrNull()
            ?: DEFAULT_MAX_OUTPUT_CHARS
        require(maxOutputChars > 0) { "CODEX_MCP_MAX_OUTPUT_CHARS must be positive" }

        val allowDanger = env["CODEX_MCP_ALLOW_DANGER_FULL_ACCESS"]?.equals("true", ignoreCase = true) == true
        val allowExtraArgs = env["CODEX_MCP_ALLOW_EXTRA_ARGS"]?.equals("true", ignoreCase = true) == true

        val extraArgsAllowlist = env["CODEX_MCP_EXTRA_ARGS_ALLOWLIST"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: DEFAULT_EXTRA_ARGS_ALLOWLIST

        val envPassthrough = env["CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: DEFAULT_ENV_PASSTHROUGH

        val auditLogPath = env["CODEX_MCP_AUDIT_LOG_PATH"]?.takeIf { it.isNotBlank() }

        return AppConfig(
            codexPath = codexPath,
            allowedRoots = canonicalRoots,
            defaultTimeoutMs = defaultTimeout,
            minTimeoutMs = minTimeout,
            maxTimeoutMs = maxTimeout,
            maxPromptChars = maxPromptChars,
            maxOutputChars = maxOutputChars,
            allowDangerFullAccess = allowDanger,
            allowExtraArgs = allowExtraArgs,
            extraArgsAllowlist = extraArgsAllowlist,
            envPassthroughAllowlist = envPassthrough,
            auditLogPath = auditLogPath,
        )
    }

    // ---------- Helpers ----------

    private fun resolveCodexOnPath(): String {
        // Let ProcessBuilder resolve `codex` via the system PATH at execution time.
        // Returning just "codex" here means the server starts even when codex is not
        // yet installed, and the error surfaces clearly at tool-call time.
        return "codex"
    }

    private fun parseAllowedRoots(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            // Default: only the server process's current working directory.
            return listOf(System.getProperty("user.dir") ?: ".")
        }
        val separator = if (System.getProperty("os.name", "").startsWith("Windows")) ";" else ":"
        return raw.split(separator).map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun canonicalizeRoots(roots: List<String>): List<String> {
        require(roots.isNotEmpty()) { "CODEX_MCP_ALLOWED_ROOTS resolved to an empty list" }
        return roots.map { raw ->
            val f = File(raw)
            require(f.exists()) { "Allowed root does not exist: $raw" }
            require(f.isDirectory) { "Allowed root is not a directory: $raw" }
            val canonical = f.canonicalPath
            require(canonical.length > 1) { "Allowed root resolves to filesystem root: $raw" }
            canonical
        }
    }
}
