package codex

data class CodexExecutionRequest(
    val prompt: String,
    val cwd: String,
    val sandbox: SandboxMode,
    val timeoutMs: Long,
    val taskId: String?,
)
