package mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ToolSchemas {

    private fun jsonStringArray(vararg values: String) = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    val executeCodexInput: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("prompt", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(
                    "The instruction to pass to 'codex exec'. Must not be empty or exceed the configured maximum length."
                ))
            })

            put("cwd", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(
                    "Absolute path to the working directory for the Codex subprocess. " +
                        "Defaults to the MCP server's current working directory."
                ))
            })

            put("sandbox", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", jsonStringArray("read-only", "workspace-write", "danger-full-access"))
                put("description", JsonPrimitive(
                    "Isolation level. read-only (default): no writes. workspace-write: writes permitted. " +
                        "danger-full-access: requires CODEX_MCP_ALLOW_DANGER_FULL_ACCESS=true."
                ))
            })

            put("timeoutMs", buildJsonObject {
                put("type", JsonPrimitive("number"))
                put("description", JsonPrimitive(
                    "Wall-clock timeout in milliseconds. Clamped to [5000, 600000]. " +
                        "Defaults to CODEX_MCP_TIMEOUT_MS (120000)."
                ))
            })

            put("taskId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(
                    "Caller-provided traceability identifier (e.g. Jira key). Max 128 printable ASCII chars. " +
                        "Recorded in the audit log and echoed in the result."
                ))
            })
        },
        required = listOf("prompt"),
    )
}
