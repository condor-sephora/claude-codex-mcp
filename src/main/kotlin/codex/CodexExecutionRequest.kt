package codex

/**
 * Validated, parsed representation of a single [execute_codex] tool invocation.
 *
 * Raw tool arguments arrive as JSON and are parsed + validated in [mcp.ExecuteCodexTool]
 * before being placed into this data class. All fields here are trusted inputs —
 * validation has already occurred upstream.
 */
data class CodexExecutionRequest(
    /** The instruction passed to `codex exec`. Never logged in raw form. */
    val prompt: String,

    /** Canonicalized, validated absolute working directory. */
    val cwd: String,

    /** Isolation level for the Codex subprocess. */
    val sandbox: SandboxMode,

    /** Wall-clock timeout in milliseconds for the Codex process. */
    val timeoutMs: Long,

    /** Requested approval mode (recorded in metadata; not mapped to a CLI flag). */
    val approvalMode: ApprovalMode,

    /** Caller-provided task identifier for traceability (validated, length-bounded). */
    val taskId: String?,

    /** Agentic workflow phase (audit only; does not affect security behavior). */
    val phase: ExecutionPhase?,

    /** Caller-provided non-secret metadata (bounded, pre-redacted). */
    val metadata: Map<String, String>?,

    /**
     * Additional CLI flags, allowed only when CODEX_MCP_ALLOW_EXTRA_ARGS=true
     * and each flag passes the extra-args allowlist check.
     */
    val extraArgs: List<String> = emptyList(),
)
