package codex

/**
 * Controls the isolation level granted to the Codex subprocess.
 *
 * Security model:
 *   - READ_ONLY   – default; Codex may read files but must not write them.
 *   - WORKSPACE_WRITE – Codex may write inside the validated workspace root only.
 *   - DANGER_FULL_ACCESS – no OS-level restriction; disabled unless explicitly opted-in
 *     via CODEX_MCP_ALLOW_DANGER_FULL_ACCESS=true.
 *
 * The sandbox name is passed verbatim to `codex exec` as the value of the sandbox flag.
 * If the installed Codex CLI does not support a sandbox flag, the flag is omitted and
 * a warning is included in the result metadata.
 */
enum class SandboxMode(val value: String) {
    READ_ONLY("read-only"),
    WORKSPACE_WRITE("workspace-write"),
    DANGER_FULL_ACCESS("danger-full-access");

    companion object {
        fun fromString(value: String?): SandboxMode? =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }

        fun default(): SandboxMode = READ_ONLY
    }
}
