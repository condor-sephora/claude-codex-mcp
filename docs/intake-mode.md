# Intake Mode (`code_intake`)

## Overview

Intake mode adds a second MCP tool — `code_intake` — to the server.
It enables a structured, read-only codebase verification workflow where:

1. **Claude** gathers context from tickets, logs, screenshots, acceptance criteria, and other inputs.
2. **Claude** writes a curated request artifact to the repository.
3. **Claude** calls `code_intake` through this MCP server.
4. **Codex** reads the request file from disk and inspects the repository.
5. **Codex** returns a structured verification report.
6. **Claude** saves the report and produces a final analysis summary.

Intake mode never modifies files. It is strictly read-only at the MCP level, at the Codex subprocess level (`--sandbox read-only`), and in the prompt instructions sent to Codex.

---

## Architecture

```
Claude Code
    │
    │  1. Gather context, write intake-request.md
    │
    ├─► code_intake({requestFile, cwd, ...})
    │
    │       MCP Server (this JAR)
    │           ├─ Validates requestFile path (relative, inside cwd, safe extension, size)
    │           ├─ Validates sandbox (forced read-only)
    │           ├─ Builds Codex prompt (file path + structured output instructions)
    │           └─► codex exec --sandbox read-only "<prompt>"
    │
    │               Codex subprocess (read-only sandbox)
    │                   ├─ Reads intake-request.md from disk
    │                   ├─ Inspects the repository
    │                   └─ Returns structured YAML / JSON / Markdown
    │
    └─ Result → Claude saves output, produces final summary
```

---

## Responsibilities

### Claude's responsibilities
- Gather all context Codex cannot discover itself (tickets, logs, screenshots, business rules).
- Write a well-structured intake request file. See [docs/templates/intake-request.md](templates/intake-request.md).
- Call `code_intake` with the request file path and target repository `cwd`.
- Save the Codex output alongside the request file.
- Synthesize a final analysis summary from the Codex output.

### Codex's responsibilities
- Read the intake request file.
- Inspect the repository for relevant files, symbols, tests, configs, and evidence.
- Return structured output in the requested format.
- Call out contradictions between the request and the code.
- Separate facts (found in code) from assumptions (inferred).
- Report what is missing if the request is underspecified.

### MCP's responsibilities
- Validate all inputs before spawning any subprocess.
- Force `--sandbox read-only` on every intake call.
- Bound output, redact secrets, enforce timeouts.
- Write audit entries for every invocation and rejection.

---

## Request File Schema

See [docs/templates/intake-request.md](templates/intake-request.md) for a fully annotated template.

The intake request file is written by Claude and read by Codex. The MCP server itself never reads the file's contents — it only validates the path, extension, and size before passing the path to Codex in the prompt.

**Required sections (by convention, not enforced by the MCP):**
- Task ID
- Task Type
- Problem Statement
- What Codex Should Verify
- Output Format

**Recommended sections:**
- Actual Behavior / Expected Behavior
- Evidence (logs, traces, repro steps)
- Known Context (architecture, feature flags, constraints)
- Claude Draft Analysis (what Claude currently believes — Codex will verify or refute)
- Scope Boundaries (allowed and forbidden paths)

---

## Output Schema (YAML — default)

```yaml
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
```

The same keys apply for JSON output. For Markdown output, Codex uses equivalent section headings.

---

## Security Controls

| Control | Detail |
|---|---|
| Read-only sandbox | `--sandbox read-only` is always passed to `codex exec`. Passing `workspace-write` or `danger-full-access` to intake is rejected with a clear error. |
| Path validation | `cwd` is canonicalized and must exist. `requestFile` must be relative, must resolve inside `cwd` after canonicalization, and cannot escape via `../`. Symlink escapes are collapsed before the containment check. |
| Allowed roots | When `CODEX_MCP_ALLOWED_ROOTS` is set, `cwd` must be inside one of those roots. |
| Request file restrictions | Extension must be `.md`, `.txt`, `.yaml`, `.yml`, or `.json`. Size ≤ `CODEX_MCP_MAX_REQUEST_FILE_BYTES` (default 200 KB). Binary content is rejected. Sensitive filename patterns (`.env`, `secrets.*`, `*.key`, etc.) are blocked. |
| No file content in MCP | The MCP server never reads the intake request file's contents. Only the validated path is passed to Codex in the prompt. This prevents the server from logging, transmitting, or leaking request data. |
| Extra instructions bound | `extraInstructions` is bounded by `CODEX_MCP_MAX_EXTRA_INSTRUCTIONS_CHARS` (default 4 000). |
| Output redaction | All Codex stdout/stderr is scanned for secret patterns before being returned. |
| Output bounding | Output is capped at `CODEX_MCP_MAX_OUTPUT_CHARS`. |
| Timeout | Configurable via `timeoutMs` per call and `CODEX_MCP_DEFAULT_TIMEOUT_MS`. Default 15 min. |
| No shell execution | Codex is spawned via `ProcessBuilder` with an argument list, never via `sh`/`bash`. |
| Audit logging | Every intake call produces an `intake_invocation` or `intake_rejection` audit entry. The entry includes the request file PATH (not contents), prompt hash, cwd, outcome, and timing. |
| Environment isolation | Only variables in `CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST` are forwarded to the subprocess. The default allowlist is `PATH,HOME,USER,LOGNAME,SHELL,OPENAI_API_KEY,CODEX_HOME`. |

---

## How to Invoke Intake Mode

### Creating the request file

```bash
mkdir -p .agent-intake/TASK-123
cp docs/templates/intake-request.md .agent-intake/TASK-123/intake-request.md
# Edit the file with Claude or manually
```

### Calling from Claude Code

```
code_intake({
  "cwd": "/path/to/repository",
  "requestFile": ".agent-intake/TASK-123/intake-request.md",
  "outputFormat": "yaml",
  "timeoutMs": 900000,
  "taskId": "TASK-123"
})
```

### Saving the result

Claude should save the Codex output alongside the request file:

```
.agent-intake/TASK-123/intake-request.md      ← written by Claude
.agent-intake/TASK-123/codex-intake.yaml      ← Codex stdout saved here
.agent-intake/TASK-123/final-summary.md       ← Claude's analysis summary
```

---

## When to Use Intake Mode

Use intake mode when you need Codex to verify facts about the codebase before making decisions.

**Good uses:**
- Bug investigation — which files implement the broken behavior?
- Technical discovery — which areas are affected by a proposed change?
- Test gap analysis — what tests are missing for this flow?
- Implementation risk analysis — what are the side effects of changing this module?
- Validating Claude's draft analysis against the actual code

**Not suitable for:**
- Product decisions (intake is read-only and produces facts, not decisions)
- Ambiguous requirements without enough evidence for Codex to work with
- Tasks requiring file writes (use `execute_codex` with `workspace-write` instead)
- Secret or credential inspection (sensitive files are blocked by policy)

---

## AGENTS.md — Repository-Level Codex Instructions

Create an `AGENTS.md` file at the root of any target repository to give Codex
project-specific guidance that applies to all intake calls.

See [docs/AGENTS.example.md](AGENTS.example.md) for a generic starting template.

The file is read by Codex automatically when present. The MCP server does not
process or validate `AGENTS.md`.

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `CODEX_MCP_ALLOWED_ROOTS` | *(none)* | Colon-separated list of canonical absolute paths. When set, `cwd` must be inside one of these roots. Unset = no restriction (existing behavior). |
| `CODEX_MCP_MAX_REQUEST_FILE_BYTES` | `204800` (200 KB) | Maximum intake request file size in bytes. |
| `CODEX_MCP_MAX_EXTRA_INSTRUCTIONS_CHARS` | `4000` | Maximum length of the `extraInstructions` field. |
| `CODEX_MCP_DEFAULT_TIMEOUT_MS` | `900000` (15 min) | Default timeout for intake calls. |
| `CODEX_MCP_MAX_TIMEOUT_MS` | `1800000` (30 min) | Maximum allowed timeout for intake calls. |
| `CODEX_MCP_MAX_OUTPUT_CHARS` | `60000` | Shared output cap with `execute_codex`. |
| `CODEX_MCP_AUDIT_LOG_PATH` | stderr | Shared audit log path with `execute_codex`. |
| `CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST` | `PATH,HOME,USER,LOGNAME,SHELL,OPENAI_API_KEY,CODEX_HOME` | Shared env allowlist. |

---

## Checkout-Resolver Example

This is an example of how to use intake mode for a checkout bug workflow.
The tool is generic — this pattern applies to any codebase and bug type.

### 1. Claude writes the intake request file

```
.agent-intake/CHECKOUT-123/intake-request.md
```

Claude fills in:
- Task ID: `CHECKOUT-123`
- Task Type: `Bug`
- Problem Statement: what the checkout bug is
- Actual / Expected Behavior: what happens vs. what should happen
- Evidence: logs, stack traces, reproduction steps
- Known Context: relevant feature flags, architecture notes
- Claude Draft Analysis: which files Claude suspects are involved
- What Codex Should Verify: specific questions about the codebase

### 2. Claude calls code_intake

```
code_intake({
  "cwd": "/path/to/android-checkout-repo",
  "requestFile": ".agent-intake/CHECKOUT-123/intake-request.md",
  "outputFormat": "yaml",
  "timeoutMs": 900000,
  "taskId": "CHECKOUT-123"
})
```

### 3. Codex inspects the repository

Codex reads the intake request file and inspects:
- Which files implement the broken behavior
- Existing tests covering this flow
- Missing tests
- Feature flags or config controlling the behavior
- Evidence supporting or contradicting Claude's draft analysis
- Implementation risks

### 4. Claude saves the result

```
.agent-intake/CHECKOUT-123/codex-intake.yaml
```

### 5. Claude produces the final summary

```
.agent-intake/CHECKOUT-123/final-analysis-summary.md
```

Claude synthesizes:
- Confirmed findings from Codex
- Proposed fix locations
- Risk assessment
- Recommended next step

---

## Troubleshooting

### `requestFile not found`
The file path is resolved relative to `cwd`. Make sure the file exists before calling the tool.

### `requestFile resolves outside cwd`
A `../` segment escaped the cwd. Use only relative paths that stay inside the repository root.

### `requestFile extension 'X' is not allowed`
Only `.md`, `.txt`, `.yaml`, `.yml`, `.json` are accepted. Save your intake file with one of these extensions.

### `requestFile name is denied by the sensitive-file pattern`
The filename matches a sensitive pattern (`.env`, `secrets.*`, `*.key`, etc.). Rename the file.

### `cwd is not inside any configured allowed root`
`CODEX_MCP_ALLOWED_ROOTS` is set and the `cwd` you passed is outside those roots. Either add the path to `CODEX_MCP_ALLOWED_ROOTS` or set `cwd` to an allowed directory.

### `Generated intake prompt length N exceeds CODEX_MCP_MAX_PROMPT_CHARS`
The combination of the built-in prompt template and your `extraInstructions` is too long. Either shorten `extraInstructions` or raise `CODEX_MCP_MAX_PROMPT_CHARS`.

### Codex returns empty output
The intake prompt reached Codex but Codex produced no output. This usually means:
- The request file is ambiguous or empty.
- Codex timed out (check `timedOut` in the result).
- The `cwd` is a directory Codex cannot usefully traverse.

---

## FAQ

**Does the MCP give Codex Claude's hidden context?**
No. Claude must materialize all relevant context into the request file or `extraInstructions`. Codex only sees the prompt the MCP builds, the request file on disk, and the repository.

**Is this different from running `codex exec` in a terminal?**
The Codex execution is equivalent if `cwd`, environment, and prompt are the same. The MCP layer adds: input validation, path safety checks, forced read-only sandbox flag, output redaction, output bounding, timeout enforcement, audit logging, and a structured tool interface.

**Can intake mode modify files?**
No. Intake mode always runs with `--sandbox read-only`. Passing `workspace-write` or `danger-full-access` is rejected before any subprocess is started.

**What if the generic output schema is too broad for my workflow?**
The MCP core stays generic by design. Add project-specific guidance to your repository's `AGENTS.md` to focus Codex on the areas that matter for your workflow. Use the `extraInstructions` field for per-call additions. If you need a heavily specialized template, add a project-specific intake request template under your repository's documentation — the generic template remains in `docs/templates/intake-request.md` and the tool schema stays unchanged.

**Does the MCP read the request file contents?**
No. The MCP validates the path (existence, extension, size, sensitivity) but never reads the file contents. Only the validated path is placed in the Codex prompt. The file contents travel from disk to Codex directly.
