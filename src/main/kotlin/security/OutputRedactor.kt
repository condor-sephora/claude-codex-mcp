package security

/**
 * Redacts common secret patterns from Codex stdout and stderr before they are returned
 * to the MCP caller or written to any log.
 *
 * This is a defense-in-depth measure. Secrets should never appear in Codex output in
 * the first place — this layer catches accidental leakage.
 *
 * Patterns covered:
 *   - OpenAI API keys (sk-...)
 *   - GitHub personal access tokens (ghp_, gho_, ghs_, ghr_, github_pat_)
 *   - Bearer tokens in Authorization headers
 *   - AWS access keys (AKIA...)
 *   - Generic KEY=VALUE pairs where the key name suggests a secret
 */
object OutputRedactor {

    private const val REDACTED = "[REDACTED]"

    /** Substitution rules applied in order. Earlier rules take precedence. */
    private val RULES: List<Pair<Regex, String>> = listOf(
        // OpenAI API keys: sk- followed by alphanumeric/dash characters
        Regex("""sk-[A-Za-z0-9\-_]{20,}""") to REDACTED,

        // OpenAI project keys
        Regex("""sk-proj-[A-Za-z0-9\-_]{20,}""") to REDACTED,

        // GitHub tokens
        Regex("""ghp_[A-Za-z0-9]{36,}""") to REDACTED,
        Regex("""gho_[A-Za-z0-9]{36,}""") to REDACTED,
        Regex("""ghs_[A-Za-z0-9]{36,}""") to REDACTED,
        Regex("""ghr_[A-Za-z0-9]{36,}""") to REDACTED,
        Regex("""github_pat_[A-Za-z0-9_]{36,}""") to REDACTED,

        // Bearer tokens in headers or env vars
        Regex("""(?i)Bearer\s+[A-Za-z0-9\-_.~+/]{20,}={0,3}""") to "Bearer $REDACTED",

        // AWS access keys
        Regex("""AKIA[0-9A-Z]{16}""") to REDACTED,

        // Generic KEY=VALUE patterns where key suggests a secret
        Regex(
            """(?i)(TOKEN|SECRET|PASSWORD|API_KEY|ACCESS_KEY|PRIVATE_KEY|PASSWD|CREDENTIALS)(\s*=\s*|\s*:\s*)['"]?([^\s'"]{4,})['"]?"""
        ) to "$1$2$REDACTED",
    )

    /**
     * Returns a copy of [text] with all detected secret patterns replaced by [REDACTED].
     *
     * The function is idempotent — running it twice produces the same result.
     */
    fun redact(text: String): String {
        if (text.isBlank()) return text
        var result = text
        for ((pattern, replacement) in RULES) {
            result = pattern.replace(result, replacement)
        }
        return result
    }

    /**
     * Bounds [text] to [maxChars] characters and then redacts secrets.
     *
     * @return Pair of (processedText, wasTruncated).
     */
    fun boundAndRedact(text: String, maxChars: Int): Pair<String, Boolean> {
        // Redact before truncating so the full secret is visible to the regex engine.
        val redacted = redact(text)
        val truncated = redacted.length > maxChars
        val bounded = if (truncated) redacted.substring(0, maxChars) else redacted
        return Pair(bounded, truncated)
    }
}
