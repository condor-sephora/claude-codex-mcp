package codex

/**
 * Represents the human-approval level requested for Codex actions.
 *
 * NOTE: The Codex CLI's exact approval-mode flag is not publicly documented as of
 * this implementation. We do NOT add an approval-mode flag to the subprocess command.
 * Instead, the requested mode is recorded in the audit log and returned in the result
 * metadata with an [approvalModeWarning] explaining the limitation.
 *
 * If a future Codex CLI version documents a stable approval-mode flag, update
 * [codex.CodexCommand.build] to map these values to the correct CLI argument.
 */
enum class ApprovalMode(val value: String) {
    /** Codex must ask for approval before every action (most restrictive). */
    UNTRUSTED("untrusted"),

    /** Codex asks for approval only when it deems the action sensitive. */
    ON_REQUEST("on-request"),

    /** Codex never asks for approval (least restrictive). */
    NEVER("never");

    companion object {
        fun fromString(value: String?): ApprovalMode? =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }

        /** Default: safest option compatible with automated workflows. */
        fun default(): ApprovalMode = UNTRUSTED

        /**
         * Warning text included in every result because approval-mode flags are not
         * yet mapped to a Codex CLI argument.
         */
        const val UNSUPPORTED_WARNING =
            "approvalMode is recorded for audit purposes but is not currently mapped " +
                "to a Codex CLI flag. The Codex CLI will use its own default approval behavior. " +
                "Update CodexCommand.build() when a stable --approval-mode flag is available."
    }
}
