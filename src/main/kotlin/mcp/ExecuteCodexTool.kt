package mcp

import codex.CodexExecutor
import codex.CodexResult
import config.AppConfig
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
import security.SecurityPolicy

object ExecuteCodexTool {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    const val TOOL_NAME = "execute_codex"
    const val TOOL_DESCRIPTION =
        "Execute OpenAI Codex CLI locally with least-privilege sandboxing and return a bounded, " +
            "redacted execution result."

    fun register(server: Server, config: AppConfig) {
        server.addTool(
            name = TOOL_NAME,
            description = TOOL_DESCRIPTION,
            inputSchema = ToolSchemas.executeCodexInput,
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            handleCall(args, config)
        }
    }

    private suspend fun handleCall(args: JsonObject, config: AppConfig): CallToolResult {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull
        val cwdRaw = args["cwd"]?.jsonPrimitive?.contentOrNull
        val sandboxRaw = args["sandbox"]?.jsonPrimitive?.contentOrNull
        val timeoutMsRaw = args["timeoutMs"]?.jsonPrimitive?.longOrNull
        val taskId = args["taskId"]?.jsonPrimitive?.contentOrNull

        val policyResult = SecurityPolicy.evaluate(
            prompt = prompt,
            cwdRaw = cwdRaw,
            sandboxRaw = sandboxRaw,
            timeoutMsRaw = timeoutMsRaw,
            taskId = taskId,
            config = config,
        )

        when (policyResult) {
            is SecurityPolicy.PolicyResult.Rejected -> {
                AuditLogger.logRejection(policyResult.violation.userMessage, taskId)
                return rejectionResult(policyResult.violation.userMessage)
            }

            is SecurityPolicy.PolicyResult.Approved -> {
                val validatedRequest = policyResult.request

                val result = withContext(Dispatchers.IO) {
                    CodexExecutor.execute(validatedRequest, config)
                }

                AuditLogger.logInvocation(validatedRequest, result)

                return CallToolResult(
                    content = listOf(TextContent(text = json.encodeToString(result))),
                    isError = false,
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
}
