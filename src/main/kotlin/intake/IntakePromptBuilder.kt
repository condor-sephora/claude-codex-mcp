package intake

/**
 * Builds the Codex prompt for an intake call.
 *
 * The MCP intentionally does NOT inline the request file's contents into the prompt:
 *   - Keeps the prompt small and within `maxPromptChars` regardless of request size.
 *   - Forces Codex to read the file from disk inside its read-only sandbox, which
 *     keeps a single source of truth and makes the file path the only secret-bearing
 *     surface to validate.
 *   - Lets Claude inspect the request file alongside the result without round-tripping
 *     it through the audit log.
 *
 * The prompt is generic — it never mentions Jira, Android, checkout, or any product.
 * Project-specific guidance lives in the repository's AGENTS.md (Codex-supported
 * convention), which Codex picks up automatically when present.
 */
object IntakePromptBuilder {

    fun build(request: IntakeRequest): String {
        val sb = StringBuilder()
        sb.appendLine("You are running in generic code intake mode.")
        sb.appendLine()
        sb.appendLine("Read the request file (path is relative to the current working directory):")
        sb.appendLine(request.requestFileRelative)
        sb.appendLine()
        sb.appendLine(
            "Use the repository at the current working directory to verify codebase facts " +
                "relevant to the request. Follow repository-level agent instructions when " +
                "available, such as AGENTS.md."
        )
        sb.appendLine()
        sb.appendLine("Rules:")
        sb.appendLine("- Do not modify files. Intake mode is strictly read-only.")
        sb.appendLine("- Do not make product decisions.")
        sb.appendLine("- Separate facts from assumptions.")
        sb.appendLine("- If the request lacks enough context, say what is missing.")
        sb.appendLine("- If repository evidence contradicts the request, call it out.")
        sb.appendLine(
            "- Prefer concrete file paths, symbols, tests, configs, and commands over " +
                "generic advice."
        )
        sb.appendLine("- Do not read, print, or expose secrets.")
        sb.appendLine("- Return only the requested structured output.")
        sb.appendLine()
        appendOutputFormatBlock(sb, request.outputFormat)

        val extra = request.extraInstructions?.trim()
        if (!extra.isNullOrEmpty()) {
            sb.appendLine()
            sb.appendLine("Additional caller instructions:")
            sb.appendLine(extra)
        }

        return sb.toString().trimEnd()
    }

    private fun appendOutputFormatBlock(sb: StringBuilder, format: IntakeOutputFormat) {
        when (format) {
            IntakeOutputFormat.YAML -> {
                sb.appendLine("Output format: YAML.")
                sb.appendLine("Return exactly one YAML document with the following top-level keys.")
                sb.appendLine("Leave a key empty (or use an empty list) when you have no findings for it.")
                sb.appendLine("Do not invent values. Do not add commentary outside the YAML block.")
                sb.appendLine()
                sb.append(YAML_SCHEMA)
            }

            IntakeOutputFormat.JSON -> {
                sb.appendLine("Output format: JSON.")
                sb.appendLine(
                    "Return exactly one JSON object whose keys mirror the YAML schema below. " +
                        "Use empty arrays for empty sections. No prose outside the JSON object."
                )
                sb.appendLine()
                sb.append(YAML_SCHEMA)
            }

            IntakeOutputFormat.MARKDOWN -> {
                sb.appendLine("Output format: Markdown.")
                sb.appendLine(
                    "Use the following sections (omit a section only when it would be empty)."
                )
                sb.appendLine()
                sb.append(MARKDOWN_OUTLINE)
            }
        }
    }

    private val YAML_SCHEMA: String = """
        task_understanding:
          summary:
          requested_outcome:
        repository_findings:
          relevant_areas:
            - path:
              reason:
          relevant_files:
            - path:
              reason:
              confidence:
          relevant_symbols:
            - name:
              path:
              reason:
        existing_tests:
          - path:
            reason:
        missing_tests:
          - description:
            suggested_location:
        configuration_or_feature_flags:
          - name:
            path:
            relevance:
        codebase_evidence:
          - finding:
            evidence:
            path:
        assumptions:
          - assumption:
            reason:
        unknowns:
          - unknown:
            why_it_matters:
        risks:
          - risk:
            severity:
            reason:
        recommended_next_step:
        confidence:
          level: high | medium | low
          reason:
    """.trimIndent()

    private val MARKDOWN_OUTLINE: String = """
        ## Task understanding
        - summary
        - requested outcome

        ## Repository findings
        - relevant areas (path + reason)
        - relevant files (path + reason + confidence)
        - relevant symbols (name + path + reason)

        ## Existing tests
        - path + reason

        ## Missing tests
        - description + suggested location

        ## Configuration or feature flags
        - name + path + relevance

        ## Codebase evidence
        - finding + evidence + path

        ## Assumptions
        - assumption + reason

        ## Unknowns
        - unknown + why it matters

        ## Risks
        - risk + severity + reason

        ## Recommended next step

        ## Confidence
        - level (high | medium | low) + reason
    """.trimIndent()
}
