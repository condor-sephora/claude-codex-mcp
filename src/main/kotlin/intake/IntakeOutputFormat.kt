package intake

/**
 * The structured shape that intake mode asks Codex to return.
 *
 * The MCP itself never parses Codex's output — the format choice only changes the
 * instructions placed in the prompt and is echoed back in the result for the caller.
 */
enum class IntakeOutputFormat(val value: String) {
    YAML("yaml"),
    JSON("json"),
    MARKDOWN("markdown");

    companion object {
        fun default(): IntakeOutputFormat = YAML

        /** Returns the matched format or null if [value] is not recognised. */
        fun fromString(value: String?): IntakeOutputFormat? {
            if (value == null) return null
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
        }
    }
}
