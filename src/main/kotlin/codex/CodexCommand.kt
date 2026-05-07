package codex

import config.AppConfig

/**
 * Builds the argument list for the Codex subprocess.
 *
 * Security rules:
 *   1. Command is an argument list passed to ProcessBuilder — never a shell string.
 *   2. The prompt is passed as a single discrete argument, not interpolated.
 *   3. No shell metacharacters are interpreted because we do not invoke sh/bash/cmd.
 */
object CodexCommand {

    fun build(request: CodexExecutionRequest, config: AppConfig): List<String> =
        listOf("codex", "exec", request.prompt)

    fun preview(request: CodexExecutionRequest, config: AppConfig): String {
        val safePrompt = "[prompt:${sha256Short(request.prompt)}...${request.prompt.length}chars]"
        return "codex exec $safePrompt"
    }

    private fun sha256Short(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(8)
    }
}
