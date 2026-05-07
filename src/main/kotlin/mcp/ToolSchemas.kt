package mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON Schema definitions for the execute_codex tool.
 */
object ToolSchemas {

    private fun jsonStringArray(vararg values: String) = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    val executeCodexInput: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("prompt", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description", JsonPrimitive(
                        "The instruction to pass to 'codex exec'. " +
                            "Must not be empty or exceed the configured maximum length. " +
                            "Never include raw secrets or credentials."
                    )
                )
            })

            put("cwd", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description", JsonPrimitive(
                        "Absolute path to the working directory for Codex. " +
                            "Must be inside a configured allowed root. " +
                            "Defaults to the MCP server's current working directory."
                    )
                )
            })

            put("sandbox", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", jsonStringArray("read-only", "workspace-write", "danger-full-access"))
                put(
                    "description", JsonPrimitive(
                        "Isolation level. read-only (default): no writes. " +
                            "workspace-write: writes inside allowed root. " +
                            "danger-full-access: disabled unless opted-in."
                    )
                )
            })

            put("timeoutMs", buildJsonObject {
                put("type", JsonPrimitive("number"))
                put("description", JsonPrimitive(
                    "Wall-clock timeout in milliseconds. Clamped to server min/max range."
                ))
            })

            put("approvalMode", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", jsonStringArray("untrusted", "on-request", "never"))
                put("description", JsonPrimitive(
                    "Requested approval level for Codex actions. " +
                        "Recorded for audit purposes; see approvalModeWarning in result."
                ))
            })

            put("extraArgs", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                put("description", JsonPrimitive(
                    "Additional Codex CLI flags. Disabled by default; requires CODEX_MCP_ALLOW_EXTRA_ARGS=true."
                ))
            })

            put("taskId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(
                    "Caller-provided traceability identifier (e.g. Jira key). Max 128 printable ASCII chars."
                ))
            })

            put("phase", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", jsonStringArray("analysis", "planning", "implementation", "review", "pr-prep", "other"))
                put("description", JsonPrimitive(
                    "Agentic workflow phase for audit logging. Does not change security behavior."
                ))
            })

            put("metadata", buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("description", JsonPrimitive(
                    "Caller-provided non-secret metadata. Max 20 entries. Do not include credentials."
                ))
                put("additionalProperties", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                })
            })
        },
        required = listOf("prompt"),
    )
}
