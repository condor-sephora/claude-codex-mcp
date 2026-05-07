package security

import config.AppConfig

/**
 * Manages the environment variables passed into the Codex subprocess.
 *
 * Security principle: do NOT inherit the full parent-process environment.
 * Only variables in the configured passthrough allowlist are forwarded.
 * This limits information leakage from the MCP server process to Codex.
 *
 * Variables that are conditionally forwarded (OPENAI_API_KEY, CODEX_HOME) are
 * only included when they are present in the parent environment; their absence
 * from the parent env is not an error.
 */
object EnvironmentPolicy {

    /**
     * Builds the environment map for the Codex subprocess.
     *
     * @param config      Application configuration containing the passthrough allowlist.
     * @param parentEnv   The parent process environment (defaults to the real env).
     */
    fun buildEnv(config: AppConfig, parentEnv: Map<String, String> = System.getenv()): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (key in config.envPassthroughAllowlist) {
            val value = parentEnv[key]
            if (value != null) {
                result[key] = value
            }
            // Variables not present in the parent env are silently omitted — no error.
        }
        return result
    }

    /**
     * Returns the keys that would be forwarded from [parentEnv] given [config].
     * Useful for audit logging without exposing values.
     */
    fun resolvedKeys(config: AppConfig, parentEnv: Map<String, String> = System.getenv()): Set<String> =
        buildEnv(config, parentEnv).keys
}
