# Security

This document describes the threat model, security controls, and disclosure process for
`claude-codex-mcp`.

## Threat model

The MCP server sits between an AI orchestrator (Claude) and the Codex CLI running on the
user's local machine. The primary threats are:

1. **Prompt injection** — a malicious document or tool result that causes Claude to issue
   a Codex call designed to exfiltrate secrets or escape the sandbox.
2. **Secret leakage** — API keys or tokens appearing in Codex stdout/stderr that are
   then returned to the caller.
3. **Over-privileged subprocess** — Codex inheriting a full environment that includes
   credentials it doesn't need.
4. **Resource exhaustion** — unbounded output or an infinite-running subprocess.

## Security controls

### 1. Working directory validation

`execute_codex` — `cwd` is canonicalized and must exist as a directory
(`security/SecurityPolicy.kt`). No allowed-root restriction is enforced by default —
`cwd` only sets the subprocess starting directory. OS user permissions govern actual file access.

`code_intake` — additionally validates allowed roots when `CODEX_MCP_ALLOWED_ROOTS` is set
(`security/PathPolicy.kt`).

### 2. Prompt heuristics (`security/InputValidator.kt`)

Defense-in-depth regex patterns reject prompts that contain obvious exfiltration intent:
`cat *.ssh*`, `cat *.gnupg*`, `print env`, `dump secrets`, `exfiltrate`, `curl|sh`,
`wget|bash`, and similar. The environment allowlist and output redactor are the primary controls.

### 3. Environment allowlist (`security/EnvironmentPolicy.kt`)

The Codex subprocess environment is **rebuilt from scratch** — `environment().clear()` is
called on the `ProcessBuilder` before any variables are added. Only variables explicitly in
`CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST` are forwarded (default: `PATH`, `HOME`, `USER`,
`LOGNAME`, `SHELL`, `OPENAI_API_KEY`, `CODEX_HOME`).

### 4. Output redaction (`security/OutputRedactor.kt`)

All stdout and stderr is scanned for secret patterns before being returned:
- OpenAI API keys (`sk-...`)
- GitHub tokens (`ghp_`, `gho_`, `ghs_`, `ghr_`, `github_pat_`)
- Bearer tokens
- AWS access keys (`AKIA...`)
- Generic `TOKEN=`, `SECRET=`, `PASSWORD=`, `API_KEY=`, `ACCESS_KEY=`, `PRIVATE_KEY=` patterns

Secrets are replaced with `[REDACTED]`. Redaction happens **before** truncation so that a
secret is never silently cut in half.

### 5. Sandbox modes (`codex/SandboxMode.kt`)

| Mode | Description |
|---|---|
| `read-only` (default) | Codex may not write files |
| `workspace-write` | Codex may write files |
| `danger-full-access` | All restrictions removed — **requires** `CODEX_MCP_ALLOW_DANGER_FULL_ACCESS=true` |

### 6. Output bounding (`security/OutputRedactor.kt`)

Stdout and stderr are each capped at `CODEX_MCP_MAX_OUTPUT_CHARS` (default 60 000) to
prevent memory exhaustion and excessively large MCP responses. Truncation flags
`stdoutTruncated` / `stderrTruncated` in the result tell the caller when data was cut.

### 7. Timeout enforcement (`codex/CodexExecutor.kt`)

A configurable wall-clock timeout is enforced via `Process.waitFor(timeout, MILLISECONDS)`.
On timeout, the process receives `SIGTERM` (graceful), then after 3 seconds `SIGKILL`
(forceful). `timedOut=true` and `exitCode=-1` appear in the result.

### 8. No shell execution

Codex is spawned via `ProcessBuilder` with an explicit argument list. The prompt is the
**last single argument** in `argv` — it is never interpolated into a shell command string.
Shell metacharacters in the prompt have no effect.

### 9. Audit logging (`logging/AuditLogger.kt`)

Every approved invocation and every security rejection produces a structured `AUDIT` line on
stderr (or a configured file). The line contains:
- A SHA-256 hex prefix of the prompt (16 chars) and its length — **never the raw prompt**.
- The resolved sandbox mode, taskId, and timing fields.

### 10. Prompt length limit

Prompts exceeding `CODEX_MCP_MAX_PROMPT_CHARS` (default 8 000) are rejected before any
subprocess is spawned.

### 11. Intake mode — additional controls (`code_intake` tool)

- Sandbox is **forced to `read-only`**. Passing `workspace-write` or `danger-full-access` is rejected.
- `requestFile` must be relative, must resolve inside `cwd` after canonicalization (defeats `../` escapes), must have an allowed extension (`.md`, `.txt`, `.yaml`, `.yml`, `.json`), must be ≤ `CODEX_MCP_MAX_REQUEST_FILE_BYTES`, must not be binary, and must not match a sensitive filename pattern.
- MCP server **never reads** the request file contents — only the validated path is passed to Codex.
- `--sandbox read-only` flag is passed explicitly to `codex exec`.
- `extraInstructions` is bounded by `CODEX_MCP_MAX_EXTRA_INSTRUCTIONS_CHARS`.
- Audit entries use `event: intake_invocation` / `intake_rejection` and record the file path (not contents).

## What this server does NOT protect against

- A Codex version that ignores the `--sandbox` flag.
- A compromised or malicious `codex` binary on `PATH`.
- Side-channel attacks via timing or process metadata.
- Attacks that require physical access to the machine.

## Responsible disclosure

If you discover a security issue, please report it privately before public disclosure.
Open a GitHub Security Advisory on this repository, or email the maintainer directly.
Do not open a public issue for security vulnerabilities.
