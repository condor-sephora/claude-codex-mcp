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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logging.AuditLogger
import security.SecurityPolicy

/**
 * Registers the [execute_codex] tool on the MCP server and handles invocations.
 *
 * Responsibilities:
 *   1. Parse raw JSON arguments from the MCP call.
 *   2. Delegate to [SecurityPolicy] for all pre-execution security checks.
 *   3. Delegate to [CodexExecutor] for process execution (IO dispatcher).
 *   4. Serialize the structured [CodexResult] as the tool response.
 *   5. Emit an audit log entry for every invocation (approved or rejected).
 *
 * This class must remain thin — business logic belongs in the packages it delegates to.
 */
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

    // ---------- Internal ----------

    private suspend fun handleCall(args: JsonObject, config: AppConfig): CallToolResult {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull
        val cwdRaw = args["cwd"]?.jsonPrimitive?.contentOrNull
        val sandboxRaw = args["sandbox"]?.jsonPrimitive?.contentOrNull
        val timeoutMsRaw = args["timeoutMs"]?.jsonPrimitive?.longOrNull
        val approvalRaw = args["approvalMode"]?.jsonPrimitive?.contentOrNull
        val taskId = args["taskId"]?.jsonPrimitive?.contentOrNull
        val phaseRaw = args["phase"]?.jsonPrimitive?.contentOrNull
        val metadataRaw = parseMetadata(args["metadata"])
        val extraArgsRaw = parseStringList(args["extraArgs"])

        // All security checks happen here before any subprocess is spawned.
        val policyResult = SecurityPolicy.evaluate(
            prompt = prompt,
            cwdRaw = cwdRaw,
            sandboxRaw = sandboxRaw,
            timeoutMsRaw = timeoutMsRaw,
            approvalRaw = approvalRaw,
            taskId = taskId,
            phaseRaw = phaseRaw,
            metadataRaw = metadataRaw,
            extraArgsRaw = extraArgsRaw,
            config = config,
        )

        when (policyResult) {
            is SecurityPolicy.PolicyResult.Rejected -> {
                AuditLogger.logRejection(policyResult.violation.userMessage, taskId)
                return rejectionResult(policyResult.violation.userMessage, policyResult.violation.warnings)
            }

            is SecurityPolicy.PolicyResult.Approved -> {
                val validatedRequest = policyResult.request

                // Run the actual subprocess on the IO dispatcher — never block the MCP thread.
                val result = withContext(Dispatchers.IO) {
                    CodexExecutor.execute(validatedRequest, config)
                }

                AuditLogger.logInvocation(validatedRequest, result)

                val resultJson = json.encodeToString(result)
                return CallToolResult(
                    content = listOf(TextContent(text = resultJson)),
                    isError = false,
                )
            }
        }
    }

    /** Builds an error result for a security rejection. */
    private fun rejectionResult(message: String, warnings: List<String>): CallToolResult {
        val payload = buildString {
            append("""{"error":true,"message":""")
            append(json.encodeToString(message))
            if (warnings.isNotEmpty()) {
                append(""","securityWarnings":""")
                append(json.encodeToString(warnings))
            }
            append("}")
        }
        return CallToolResult(
            content = listOf(TextContent(text = payload)),
            isError = true,
        )
    }

    private fun parseMetadata(element: kotlinx.serialization.json.JsonElement?): Map<String, String>? {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        return try {
            element.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull ?: "" }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStringList(element: kotlinx.serialization.json.JsonElement?): List<String>? {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        return try {
            element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        } catch (_: Exception) {
            null
        }
    }
}
