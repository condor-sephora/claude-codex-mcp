package mcp

import codex.CodexExecutionRequest
import codex.CodexExecutor
import codex.SandboxMode
import config.AppConfig
import intake.IntakePromptBuilder
import intake.IntakeResult
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logging.AuditLogger
import security.IntakeSecurityPolicy

object CodeIntakeTool {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    const val TOOL_NAME = "code_intake"
    const val TOOL_DESCRIPTION =
        "Generic read-only codebase intake mode. " +
            "Claude writes a request artifact to the repository, then invokes this tool so Codex " +
            "reads the file and verifies repository facts. Codex runs in a strict read-only sandbox. " +
            "The result is a structured verification report (YAML, JSON, or Markdown) that Claude " +
            "uses to produce a final analysis summary. " +
            "Intake mode never modifies files and never reads secrets."

    fun register(server: Server, config: AppConfig) {
        server.addTool(
            name = TOOL_NAME,
            description = TOOL_DESCRIPTION,
            inputSchema = ToolSchemas.codeIntakeInput,
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            handleCall(args, config)
        }
    }

    private suspend fun handleCall(args: JsonObject, config: AppConfig): CallToolResult {
        val cwdRaw = args["cwd"]?.jsonPrimitive?.contentOrNull
        val requestFileRaw = args["requestFile"]?.jsonPrimitive?.contentOrNull
        val sandboxRaw = args["sandbox"]?.jsonPrimitive?.contentOrNull
        val outputFormatRaw = args["outputFormat"]?.jsonPrimitive?.contentOrNull
        val extraInstructions = args["extraInstructions"]?.jsonPrimitive?.contentOrNull
        val timeoutMsRaw = args["timeoutMs"]?.jsonPrimitive?.longOrNull
        val taskId = args["taskId"]?.jsonPrimitive?.contentOrNull

        val policyResult = IntakeSecurityPolicy.evaluate(
            cwdRaw = cwdRaw,
            requestFileRaw = requestFileRaw,
            sandboxRaw = sandboxRaw,
            outputFormatRaw = outputFormatRaw,
            extraInstructions = extraInstructions,
            timeoutMsRaw = timeoutMsRaw,
            taskId = taskId,
            config = config,
        )

        when (policyResult) {
            is IntakeSecurityPolicy.PolicyResult.Rejected -> {
                AuditLogger.logIntakeRejection(policyResult.violation.userMessage, taskId)
                return rejectionResult(policyResult.violation.userMessage)
            }

            is IntakeSecurityPolicy.PolicyResult.Approved -> {
                val intakeRequest = policyResult.request

                // Build the Codex prompt. The prompt references the request file path but never
                // inlines its contents — Codex reads the file from disk in its read-only sandbox.
                val prompt = IntakePromptBuilder.build(intakeRequest)

                // Enforce prompt length. If extra instructions push the prompt over the limit
                // the caller gets a clear rejection rather than a silent truncation.
                if (prompt.length > config.maxPromptChars) {
                    val reason = "Generated intake prompt length ${prompt.length} exceeds " +
                        "CODEX_MCP_MAX_PROMPT_CHARS (${config.maxPromptChars}). " +
                        "Reduce extraInstructions or raise CODEX_MCP_MAX_PROMPT_CHARS."
                    AuditLogger.logIntakeRejection(reason, taskId)
                    return rejectionResult(reason)
                }

                val promptHash = sha256Short(prompt)

                // Delegate to the existing Codex executor — intake is just execute_codex with
                // a generated prompt, forced read-only sandbox, and the sandbox CLI flag set.
                val codexRequest = CodexExecutionRequest(
                    prompt = prompt,
                    cwd = intakeRequest.cwd,
                    sandbox = SandboxMode.READ_ONLY,
                    timeoutMs = intakeRequest.timeoutMs,
                    taskId = intakeRequest.taskId,
                )

                val codexResult = withContext(Dispatchers.IO) {
                    CodexExecutor.execute(codexRequest, config, includeSandboxFlag = true)
                }

                val intakeResult = IntakeResult(
                    mode = "intake",
                    exitCode = codexResult.exitCode,
                    timedOut = codexResult.timedOut,
                    durationMs = codexResult.durationMs,
                    stdout = codexResult.stdout,
                    stderr = codexResult.stderr,
                    stdoutTruncated = codexResult.stdoutTruncated,
                    stderrTruncated = codexResult.stderrTruncated,
                    commandPreview = codexResult.commandPreview,
                    workingDirectory = intakeRequest.cwd,
                    requestFile = intakeRequest.requestFileRelative,
                    outputFormat = intakeRequest.outputFormat.value,
                    sandbox = SandboxMode.READ_ONLY.value,
                    taskId = intakeRequest.taskId,
                )

                AuditLogger.logIntakeInvocation(intakeRequest, promptHash, intakeResult)

                return CallToolResult(
                    content = listOf(TextContent(text = json.encodeToString(intakeResult))),
                    isError = intakeResult.timedOut || intakeResult.exitCode != 0,
                )
            }
        }
    }

    private fun rejectionResult(message: String): CallToolResult {
        val payload = """{"error":true,"message":${json.encodeToString(message)}}"""
        return CallToolResult(
            content = listOf(TextContent(text = payload)),
            isError = true,
        )
    }

    private fun sha256Short(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
