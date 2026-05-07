package security

import codex.CodexExecutionRequest
import codex.SandboxMode
import config.AppConfig
import java.io.File

object SecurityPolicy {

    data class SecurityViolation(val userMessage: String)

    sealed class PolicyResult {
        data class Approved(val request: CodexExecutionRequest) : PolicyResult()
        data class Rejected(val violation: SecurityViolation) : PolicyResult()
    }

    fun evaluate(
        prompt: String?,
        cwdRaw: String?,
        sandboxRaw: String?,
        timeoutMsRaw: Long?,
        taskId: String?,
        config: AppConfig,
    ): PolicyResult {
        // 1. Prompt validation (length + heuristic filter)
        val promptResult = InputValidator.validatePrompt(prompt, config)
        if (promptResult is InputValidator.ValidationResult.Rejected) {
            return PolicyResult.Rejected(SecurityViolation(promptResult.reason))
        }
        val validatedPrompt = prompt!!.trim()

        // 2. Sandbox resolution and access control
        val sandbox = SandboxMode.fromString(sandboxRaw) ?: SandboxMode.default()
        if (sandbox == SandboxMode.DANGER_FULL_ACCESS && !config.allowDangerFullAccess) {
            return PolicyResult.Rejected(
                SecurityViolation(
                    "sandbox=danger-full-access is disabled. " +
                        "Set CODEX_MCP_ALLOW_DANGER_FULL_ACCESS=true to enable (not recommended)."
                )
            )
        }

        // 3. Working directory — default to server CWD, verify the path exists
        val effectiveCwd = cwdRaw?.takeIf { it.isNotBlank() } ?: System.getProperty("user.dir") ?: "."
        val cwdFile = File(effectiveCwd)
        if (!cwdFile.exists() || !cwdFile.isDirectory) {
            return PolicyResult.Rejected(
                SecurityViolation("cwd does not exist or is not a directory: $effectiveCwd")
            )
        }
        val canonicalCwd = cwdFile.canonicalPath

        // 4. Timeout resolution
        val resolvedTimeout = InputValidator.resolveTimeout(timeoutMsRaw, config)

        // 5. taskId validation
        val taskIdResult = InputValidator.validateTaskId(taskId)
        if (taskIdResult is InputValidator.ValidationResult.Rejected) {
            return PolicyResult.Rejected(SecurityViolation(taskIdResult.reason))
        }

        return PolicyResult.Approved(
            CodexExecutionRequest(
                prompt = validatedPrompt,
                cwd = canonicalCwd,
                sandbox = sandbox,
                timeoutMs = resolvedTimeout,
                taskId = taskId?.trim(),
            )
        )
    }
}
