package codex

/**
 * Builds the argument list for the Codex subprocess.
 *
 * Security rules:
 *   1. Command is an argument list passed to ProcessBuilder — never a shell string.
 *   2. The prompt is passed as a single discrete argument, not interpolated.
 *   3. No shell metacharacters are interpreted because we do not invoke sh/bash/cmd.
 */
object CodexCommand {

    /**
     * Builds `codex exec [--sandbox <mode>] <prompt>`.
     *
     * The optional sandbox flag is opt-in to preserve the historical command shape used
     * by the `execute_codex` tool (which passes only the prompt). Intake mode passes
     * `includeSandboxFlag = true` so Codex enforces the read-only restriction at the
     * CLI level in addition to the in-process policy.
     */
    fun build(request: CodexExecutionRequest, includeSandboxFlag: Boolean = false): List<String> {
        if (!includeSandboxFlag) return listOf("codex", "exec", request.prompt)
        return listOf("codex", "exec", "--sandbox", request.sandbox.value, request.prompt)
    }

    fun preview(request: CodexExecutionRequest): String {
        val safePrompt = "[prompt:${sha256Short(request.prompt)}...${request.prompt.length}chars]"
        return "codex exec $safePrompt"
    }

    private fun sha256Short(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(8)
    }
}
