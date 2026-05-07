package codex

import config.AppConfig

/**
 * Builds the argument list for the Codex subprocess.
 *
 * Security rules (all enforced here and tested in unit tests):
 *   1. Command is an argument list passed to [ProcessBuilder] — never a shell string.
 *   2. The prompt is passed as a single discrete argument, not interpolated.
 *   3. No shell metacharacters are interpreted because we do not invoke sh/bash/cmd.
 *
 * Codex CLI interface notes:
 *   - The subcommand is `exec`.
 *   - Sandbox flag: The Codex CLI may accept `--sandbox <mode>` but this is not
 *     formally documented in the public spec as of this implementation. The flag is
 *     included when CODEX_MCP_CODEX_SUPPORTS_SANDBOX=true; otherwise omitted with a note.
 *   - Approval mode: No stable public CLI flag exists for approval mode as of this
 *     implementation. We do NOT add an approval flag; see [ApprovalMode] for details.
 *
 * Update this file when the Codex CLI documents new stable flags.
 */
object CodexCommand {

    /**
     * Env var that opts into passing `--sandbox <mode>` to Codex.
     * Default false because the flag name is not confirmed in the public spec.
     */
    private const val ENV_SUPPORTS_SANDBOX = "CODEX_MCP_CODEX_SUPPORTS_SANDBOX"

    /**
     * Builds the subprocess argument list.
     *
     * @param request  Validated execution request.
     * @param config   Application configuration.
     * @param env      Process environment map (used to check feature flags).
     * @return         Ordered argument list suitable for [ProcessBuilder].
     */
    fun build(
        request: CodexExecutionRequest,
        config: AppConfig,
        env: Map<String, String> = System.getenv(),
    ): List<String> {
        val args = mutableListOf<String>()

        // 1. Codex executable path (resolved from PATH if just "codex").
        args.add(config.codexPath)

        // 2. Subcommand.
        args.add("exec")

        // 3. Sandbox flag — only added if the installed Codex version supports it.
        //    See README §Troubleshooting for guidance on enabling this flag.
        val sandboxSupported = env[ENV_SUPPORTS_SANDBOX]?.equals("true", ignoreCase = true) == true
        if (sandboxSupported) {
            args.add("--sandbox")
            args.add(request.sandbox.value)
        }

        // 4. Extra args (already validated against the allowlist by SecurityPolicy).
        args.addAll(request.extraArgs)

        // 5. The prompt as the final, single argument.
        //    ProcessBuilder passes this as one element of argv — no shell interpolation.
        args.add(request.prompt)

        return args
    }

    /**
     * Returns a safe, non-secret preview of the command for logging and the result payload.
     *
     * The raw prompt is replaced with a SHA-256-derived placeholder so the command
     * preview can be safely included in the result without leaking prompt content.
     */
    fun preview(request: CodexExecutionRequest, config: AppConfig): String {
        val safePrompt = "[prompt:${sha256Short(request.prompt)}...${request.prompt.length}chars]"
        val base = listOf(config.codexPath, "exec")
        val extras = if (request.extraArgs.isEmpty()) emptyList() else request.extraArgs
        return (base + extras + listOf(safePrompt)).joinToString(" ")
    }

    private fun sha256Short(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(8)
    }
}
