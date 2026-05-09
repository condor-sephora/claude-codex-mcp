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

    val codeIntakeInput: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("requestFile", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(
                    "Path to the intake request file, relative to cwd. " +
                        "Example: .agent-intake/TASK-123/intake-request.md. " +
                        "Allowed extensions: md, txt, yaml, yml, json. Max size: CODEX_MCP_MAX_REQUEST_FILE_BYTES (default 200 KB)."
                ))
            })

            put("cwd", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(
                    "Absolute path to the repository root for Codex to inspect. " +
                        "Defaults to the MCP server's current working directory. " +
                        "Must be inside CODEX_MCP_ALLOWED_ROOTS when configured."
                ))
            })

            put("outputFormat", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", jsonStringArray("yaml", "json", "markdown"))
                put("description", JsonPrimitive(
                    "Format for Codex's structured output. Default: yaml."
                ))
            })

            put("timeoutMs", buildJsonObject {
                put("type", JsonPrimitive("number"))
                put("description", JsonPrimitive(
                    "Wall-clock timeout in milliseconds. " +
                        "Clamped to [5000, CODEX_MCP_MAX_TIMEOUT_MS]. " +
                        "Defaults to CODEX_MCP_DEFAULT_TIMEOUT_MS (900000 ms = 15 min)."
                ))
            })

            put("extraInstructions", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(
                    "Optional additional instructions appended to the intake prompt. " +
                        "Max CODEX_MCP_MAX_EXTRA_INSTRUCTIONS_CHARS (default 4000) characters."
                ))
            })

            put("sandbox", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", jsonStringArray("read-only"))
                put("description", JsonPrimitive(
                    "Intake mode is always read-only. This field is accepted for explicitness " +
                        "but must be 'read-only' or omitted. Any other value is rejected."
                ))
            })

            put("taskId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(
                    "Caller-provided traceability identifier. Max 128 printable ASCII chars. " +
                        "Recorded in the audit log and echoed in the result."
                ))
            })
        },
        required = listOf("requestFile"),
    )

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
