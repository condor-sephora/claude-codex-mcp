package security

import codex.SandboxMode
import config.AppConfig
import intake.IntakeOutputFormat
import intake.IntakeRequest

/**
 * Validates raw intake-tool arguments and either approves a typed [IntakeRequest]
 * or returns a structured rejection.
 *
 * Intake-specific rules layered on top of the existing input validators:
 *   - Sandbox is forced to read-only. Callers passing a non-read-only value are rejected
 *     so the surprise is loud rather than silent.
 *   - cwd must be inside the optional allowed roots.
 *   - requestFile must satisfy [PathPolicy.validateRequestFile].
 *   - extraInstructions length is bounded by [AppConfig.maxExtraInstructionsChars].
 *   - timeoutMs defaults to [AppConfig.defaultIntakeTimeoutMs] and is clamped to
 *     [5_000, AppConfig.maxIntakeTimeoutMs].
 */
object IntakeSecurityPolicy {

    private const val MIN_TIMEOUT_MS = 5_000L

    data class SecurityViolation(val userMessage: String)

    sealed class PolicyResult {
        data class Approved(val request: IntakeRequest) : PolicyResult()
        data class Rejected(val violation: SecurityViolation) : PolicyResult()
    }

    fun evaluate(
        cwdRaw: String?,
        requestFileRaw: String?,
        sandboxRaw: String?,
        outputFormatRaw: String?,
        extraInstructions: String?,
        timeoutMsRaw: Long?,
        taskId: String?,
        config: AppConfig,
    ): PolicyResult {
        // 1. Sandbox: intake mode is strictly read-only. Reject explicit non-read-only
        //    values so the caller cannot accidentally widen permissions; missing/null
        //    sandbox is fine and defaults to read-only.
        if (sandboxRaw != null && sandboxRaw.isNotBlank()) {
            val parsed = SandboxMode.fromString(sandboxRaw)
                ?: return rejected("Unknown sandbox value: $sandboxRaw")
            if (parsed != SandboxMode.READ_ONLY) {
                return rejected(
                    "Intake mode requires sandbox=read-only. Received: ${parsed.value}"
                )
            }
        }

        // 2. Working directory.
        val cwdResult = PathPolicy.validateCwd(cwdRaw, config)
        if (cwdResult is PathPolicy.CwdResult.Rejected) return rejected(cwdResult.reason)
        val canonicalCwd = (cwdResult as PathPolicy.CwdResult.Ok).canonicalCwd

        // 3. Request file.
        val fileResult = PathPolicy.validateRequestFile(requestFileRaw, canonicalCwd, config)
        if (fileResult is PathPolicy.RequestFileResult.Rejected) return rejected(fileResult.reason)
        val fileOk = fileResult as PathPolicy.RequestFileResult.Ok

        // 4. Output format.
        val format = if (outputFormatRaw == null) {
            IntakeOutputFormat.default()
        } else {
            IntakeOutputFormat.fromString(outputFormatRaw)
                ?: return rejected(
                    "Unknown outputFormat: $outputFormatRaw. " +
                        "Allowed: ${IntakeOutputFormat.entries.joinToString(", ") { it.value }}"
                )
        }

        // 5. Extra instructions length.
        val trimmedExtra = extraInstructions?.trim()
        val extra = if (trimmedExtra.isNullOrEmpty()) null else trimmedExtra
        if (extra != null && extra.length > config.maxExtraInstructionsChars) {
            return rejected(
                "extraInstructions length ${extra.length} exceeds maximum " +
                    "${config.maxExtraInstructionsChars} characters"
            )
        }

        // 6. taskId — reuse the existing validator.
        val taskIdResult = InputValidator.validateTaskId(taskId)
        if (taskIdResult is InputValidator.ValidationResult.Rejected) {
            return rejected(taskIdResult.reason)
        }

        // 7. Timeout — intake gets its own bounds rather than reusing execute_codex's
        //    [5_000, 600_000] defaults because intake calls run for many minutes.
        val timeoutMs = resolveTimeout(timeoutMsRaw, config)

        return PolicyResult.Approved(
            IntakeRequest(
                cwd = canonicalCwd,
                requestFilePath = fileOk.canonicalPath,
                requestFileRelative = fileOk.relativePath,
                outputFormat = format,
                extraInstructions = extra,
                timeoutMs = timeoutMs,
                taskId = taskId?.trim(),
            )
        )
    }

    private fun resolveTimeout(requestedMs: Long?, config: AppConfig): Long {
        val requested = requestedMs ?: return config.defaultIntakeTimeoutMs
        return requested.coerceIn(MIN_TIMEOUT_MS, config.maxIntakeTimeoutMs)
    }

    private fun rejected(reason: String): PolicyResult.Rejected =
        PolicyResult.Rejected(SecurityViolation(reason))
}
