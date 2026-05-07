package security

import codex.ApprovalMode
import codex.CodexExecutionRequest
import codex.SandboxMode
import config.AppConfig

/**
 * Orchestrates all pre-execution security checks.
 *
 * Checks are applied in order. The first failure short-circuits evaluation and
 * returns a safe, client-visible error with a [SecurityViolation] payload.
 *
 * This class is the single point of authority for "should this request proceed?"
 * before spawning any subprocess. It must be called before [codex.CodexExecutor].
 */
object SecurityPolicy {

    data class SecurityViolation(
        val userMessage: String,
        val warnings: List<String> = emptyList(),
    )

    sealed class PolicyResult {
        data class Approved(val request: CodexExecutionRequest) : PolicyResult()
        data class Rejected(val violation: SecurityViolation) : PolicyResult()
    }

    /**
     * Validates a raw tool-call request against all configured security policies.
     *
     * @param prompt       Raw prompt string from the MCP call.
     * @param cwdRaw       Raw cwd string (may be null → use server default).
     * @param sandboxRaw   Raw sandbox string.
     * @param timeoutMsRaw Raw timeout (may be null → use server default).
     * @param approvalRaw  Raw approval mode string.
     * @param taskId       Raw task ID (may be null).
     * @param phaseRaw     Raw phase string (may be null).
     * @param metadataRaw  Raw metadata map (may be null).
     * @param extraArgsRaw Raw extra args list (may be null).
     * @param config       Resolved application configuration.
     */
    @Suppress("LongParameterList")
    fun evaluate(
        prompt: String?,
        cwdRaw: String?,
        sandboxRaw: String?,
        timeoutMsRaw: Long?,
        approvalRaw: String?,
        taskId: String?,
        phaseRaw: String?,
        metadataRaw: Map<String, String>?,
        extraArgsRaw: List<String>?,
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

        // 3. Working directory validation
        val effectiveCwd = cwdRaw ?: System.getProperty("user.dir") ?: "."
        val pathResult = PathPolicy.validate(effectiveCwd, config)
        if (pathResult is PathPolicy.PathValidationResult.Denied) {
            return PolicyResult.Rejected(SecurityViolation(pathResult.reason))
        }
        val canonicalCwd = (pathResult as PathPolicy.PathValidationResult.Allowed).canonicalPath

        // 4. Workspace-write requires being inside an allowed root (already checked above,
        //    but we repeat for clarity — PathPolicy already enforces the root constraint).
        //    No additional check needed here because PathPolicy.validate() already
        //    requires the path to be inside config.allowedRoots.

        // 5. Timeout resolution
        val resolvedTimeout = InputValidator.resolveTimeout(timeoutMsRaw, config)

        // 6. Approval mode resolution
        val approvalMode = ApprovalMode.fromString(approvalRaw) ?: ApprovalMode.default()

        // 7. taskId validation
        val taskIdResult = InputValidator.validateTaskId(taskId)
        if (taskIdResult is InputValidator.ValidationResult.Rejected) {
            return PolicyResult.Rejected(SecurityViolation(taskIdResult.reason))
        }

        // 8. Metadata validation
        val metaResult = InputValidator.validateMetadata(metadataRaw)
        if (metaResult is InputValidator.ValidationResult.Rejected) {
            return PolicyResult.Rejected(SecurityViolation(metaResult.reason))
        }

        // 9. Extra args validation
        val extraArgs = extraArgsRaw ?: emptyList()
        val extraArgsResult = InputValidator.validateExtraArgs(extraArgs, config)
        if (extraArgsResult is InputValidator.ValidationResult.Rejected) {
            return PolicyResult.Rejected(SecurityViolation(extraArgsResult.reason))
        }

        // 10. Phase parsing (informational, never a security gate)
        val phase = codex.ExecutionPhase.fromString(phaseRaw)

        return PolicyResult.Approved(
            CodexExecutionRequest(
                prompt = validatedPrompt,
                cwd = canonicalCwd,
                sandbox = sandbox,
                timeoutMs = resolvedTimeout,
                approvalMode = approvalMode,
                taskId = taskId?.trim(),
                phase = phase,
                metadata = metadataRaw,
                extraArgs = extraArgs,
            )
        )
    }
}
