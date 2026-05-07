package codex

/**
 * Describes which phase of the agentic workflow triggered this Codex invocation.
 *
 * Used exclusively for audit logging and structured result metadata.
 * Phase MUST NOT change any security behavior — sandbox restrictions and path
 * policies apply identically regardless of phase.
 */
enum class ExecutionPhase(val value: String) {
    ANALYSIS("analysis"),
    PLANNING("planning"),
    IMPLEMENTATION("implementation"),
    REVIEW("review"),
    PR_PREP("pr-prep"),
    OTHER("other");

    companion object {
        fun fromString(value: String?): ExecutionPhase? =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
    }
}
