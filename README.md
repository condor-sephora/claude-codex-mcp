# claude-codex-mcp

A production-quality [Model Context Protocol (MCP)](https://modelcontextprotocol.io) server
written in Kotlin that exposes the OpenAI Codex CLI as a single, tightly-sandboxed tool
named `execute_codex`.

Claude Code (or any MCP-compatible AI client) uses this server to delegate coding tasks to
Codex — with path isolation, secret redaction, output bounding, and full audit logging built in.

---

## How it works

```
Claude Code  ──stdio/JSON-RPC──►  claude-codex-mcp (this JAR)  ──subprocess──►  codex exec
     ▲                                                                                │
     └──────────────────── structured result (stdout, stderr, exitCode, ...) ◄───────┘
```

Claude Code launches the JAR automatically as a child process at session start. The JAR
sits idle until a tool call arrives, then spawns `codex exec` as a subprocess (never via a
shell), enforces all security policies, and returns a bounded, redacted result. The JAR is
**never** loaded into the AI context — only the tool schema (~200 tokens) is injected.

---

## Requirements

| Dependency | Version | Notes |
|---|---|---|
| JDK | 11+ | Tested with JDK 21. Only needed to run the JAR. |
| `codex` CLI | any | Must be on `PATH` or set via `CODEX_PATH`. Needs its own auth configured. |
| Claude Code | latest | The client that registers and calls this MCP server. |
| Gradle | 8.8+ | Only needed to build from source. |

> **Codex authentication** — `codex` reads its credentials from its own config file
> (`~/.config/codex/`) or keychain, not from environment variables. If `codex exec` works
> in your terminal, it will work here. No extra credential setup is needed.

---

## Installation

### 1. Clone and build

```bash
git clone <repo-url> claude-codex-mcp
cd claude-codex-mcp
./gradlew build
```

The fat JAR (self-contained, no classpath setup needed) is produced at:

```
build/libs/claude-codex-mcp-all.jar
```

### 2. Register with Claude Code

Run this **once**. Replace the `CODEX_MCP_ALLOWED_ROOTS` value with the directory (or
directories) you want Codex to be able to work in.

```bash
claude mcp add codex \
  --env CODEX_MCP_ALLOWED_ROOTS=/path/to/your/projects \
  --env CODEX_MCP_DEFAULT_TIMEOUT_MS=120000 \
  --env CODEX_MCP_MAX_OUTPUT_CHARS=60000 \
  -- java -jar /path/to/claude-codex-mcp/build/libs/claude-codex-mcp-all.jar
```

Multiple roots (colon-separated on macOS/Linux):

```bash
--env CODEX_MCP_ALLOWED_ROOTS=/Users/me/sephora:/Users/me/personal
```

### 3. Verify registration

```bash
claude mcp list
```

You should see `codex` with status `connected`. Claude Code will start the JAR
automatically on the next session — no manual process management needed.

### 4. Confirm it works

Open a new Claude Code session and ask:

```
Use execute_codex to list the Kotlin files in <your-project-dir> and summarize the package structure.
```

Or invoke the tool directly:

```
/mcp codex execute_codex '{"prompt":"what files are in this project?","cwd":"/path/to/project","sandbox":"read-only"}'
```

---

## Multiple terminals / sessions

Each Claude Code session spawns its own independent JAR process. They share nothing — each
has its own JVM, its own memory, and its own subprocess lifecycle. Opening 3 terminals
means 3 isolated JAR processes reading the same JAR file from disk (safe, read-only).

The only shared resource is the OpenAI API rate limit on the `codex` side — if multiple
sessions fire large tasks simultaneously, you may hit OpenAI throttling. That is outside
this server's control.

---

## Startup time note (JVM cold start)

The JAR takes 1–2 seconds to start when Claude Code opens a session. This is a one-time
JVM initialization cost. All subsequent `execute_codex` calls within that session are fast —
the JVM stays warm. The actual wait time per call is dominated by `codex` + OpenAI API
latency (seconds to tens of seconds), not the JVM.

---

## Configuration (environment variables)

Set these via `--env` flags in the `claude mcp add` command (see Installation above).

| Variable | Default | Description |
|---|---|---|
| `CODEX_PATH` | `codex` (on PATH) | Absolute path to the Codex executable |
| `CODEX_MCP_ALLOWED_ROOTS` | server CWD | Colon-separated list of directories `cwd` must be inside |
| `CODEX_MCP_DEFAULT_TIMEOUT_MS` | `120000` | Default subprocess timeout in milliseconds |
| `CODEX_MCP_MIN_TIMEOUT_MS` | `5000` | Minimum timeout callers may request |
| `CODEX_MCP_MAX_TIMEOUT_MS` | `600000` | Maximum timeout callers may request |
| `CODEX_MCP_MAX_PROMPT_CHARS` | `8000` | Maximum prompt length in characters |
| `CODEX_MCP_MAX_OUTPUT_CHARS` | `60000` | Maximum stdout/stderr characters captured per stream |
| `CODEX_MCP_ALLOW_DANGER_FULL_ACCESS` | `false` | Set `true` to enable `danger-full-access` sandbox mode |
| `CODEX_MCP_ALLOW_EXTRA_ARGS` | `false` | Set `true` to allow `extraArgs` in tool calls |
| `CODEX_MCP_EXTRA_ARGS_ALLOWLIST` | `--quiet,--verbose,--no-color` | Comma-separated list of allowed extra CLI flags |
| `CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST` | `PATH,HOME,USER,LOGNAME,SHELL,OPENAI_API_KEY,CODEX_HOME` | Env vars forwarded into the Codex subprocess |
| `CODEX_MCP_AUDIT_LOG_PATH` | stderr | File path for structured audit log output |
| `CODEX_MCP_CODEX_SUPPORTS_SANDBOX` | `false` | Set `true` to pass `--sandbox <mode>` to the Codex CLI |

> **Security note on env passthrough** — The subprocess environment is rebuilt from scratch
> on every call. Only variables explicitly listed in `CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST`
> are forwarded. The Codex process never sees `CODEX_MCP_ALLOWED_ROOTS`, session tokens, or
> any other MCP server internals.

---

## Tool schema: `execute_codex`

| Field | Type | Required | Description |
|---|---|---|---|
| `prompt` | string | **yes** | The instruction to pass to Codex. Max `CODEX_MCP_MAX_PROMPT_CHARS` chars. |
| `cwd` | string | no | Working directory. Must be inside an allowed root. Defaults to server CWD. |
| `sandbox` | enum | no | `read-only` (default), `workspace-write`, `danger-full-access` |
| `timeoutMs` | number | no | Wall-clock timeout in ms. Clamped to server min/max. |
| `approvalMode` | enum | no | `untrusted`, `on-request`, `never` — recorded for audit, not enforced by CLI |
| `extraArgs` | array | no | Additional Codex CLI flags. Disabled by default; requires opt-in. |
| `taskId` | string | no | Jira key or traceability ID. Max 128 printable ASCII chars. |
| `phase` | enum | no | `analysis`, `planning`, `implementation`, `review`, `pr-prep`, `other` |
| `metadata` | object | no | Caller-provided key/value pairs. Max 20 entries. Never include credentials. |

---

## Example tool calls

### Analysis — read only, with Jira traceability

```json
{
  "prompt": "List all Kotlin source files and summarize the package structure",
  "cwd": "/Users/me/my-project",
  "sandbox": "read-only",
  "taskId": "SHP-1234",
  "phase": "analysis"
}
```

### Implementation — write enabled, longer timeout

```json
{
  "prompt": "Add unit tests for the PaymentService class following the existing test conventions in src/test",
  "cwd": "/Users/me/my-project",
  "sandbox": "workspace-write",
  "taskId": "SHP-1234",
  "phase": "implementation",
  "timeoutMs": 180000
}
```

### Result shape

Every successful call returns a JSON object as the tool text content:

```json
{
  "exitCode": 0,
  "timedOut": false,
  "durationMs": 16573,
  "stdout": "fun isPalindrome(s: String): Boolean { ... }",
  "stderr": "",
  "stdoutTruncated": false,
  "stderrTruncated": false,
  "commandPreview": "codex exec [prompt:723e8514...352chars]",
  "workingDirectory": "/Users/me/my-project",
  "sandbox": "read-only",
  "approvalModeApplied": "untrusted",
  "approvalModeWarning": "approval mode is recorded for audit but not mapped to a Codex CLI flag",
  "taskId": "SHP-1234",
  "phase": "analysis",
  "securityWarnings": []
}
```

Key fields:

| Field | Meaning |
|---|---|
| `exitCode` | Codex process exit code. `0` = success, `-1` = timed out or failed to start |
| `timedOut` | `true` if the process was killed due to timeout |
| `durationMs` | Wall-clock time from subprocess spawn to completion |
| `stdoutTruncated` / `stderrTruncated` | `true` if output was cut at `CODEX_MCP_MAX_OUTPUT_CHARS` |
| `commandPreview` | Safe log of the command — raw prompt is replaced with a hash |

---

## Security rejections

The server rejects calls before spawning any subprocess in these cases:

| Condition | Error message |
|---|---|
| Empty or blank prompt | `Prompt must not be empty` |
| Prompt exceeds max length | `Prompt length N exceeds maximum M characters` |
| Prompt matches exfiltration pattern | `Prompt was rejected by the defense-in-depth heuristic filter` |
| `cwd` outside allowed roots | `Path is not inside any allowed root` |
| `cwd` is a blocked directory (`.ssh`, `/tmp`, etc.) | `Path is explicitly denied` |
| `danger-full-access` without opt-in | `sandbox=danger-full-access is disabled. Set CODEX_MCP_ALLOW_DANGER_FULL_ACCESS=true` |
| `extraArgs` without opt-in | `extraArgs are disabled. Set CODEX_MCP_ALLOW_EXTRA_ARGS=true` |

All rejections produce `isError: true` in the MCP response and write an `AUDIT event=security_rejection` line to the audit log.

---

## Running tests

```bash
# Unit tests (121 tests, no codex CLI needed)
./gradlew test

# Integration tests (27 tests, uses fake-codex.sh stub — no real Codex or API key needed)
./gradlew integrationTest

# Full build including both test suites + shadow JAR
./gradlew build

# Optional: smoke test against a real Codex installation
CODEX_MCP_RUN_REAL_CODEX_TESTS=true ./gradlew realCodexSmokeTest
```

---

## Project structure

```
src/main/kotlin/
├── Main.kt                          # Entry point — wires transport and starts server
├── mcp/
│   ├── CodexMcpServer.kt            # Builds the MCP Server instance
│   ├── ExecuteCodexTool.kt          # Registers execute_codex, parses args, calls SecurityPolicy
│   └── ToolSchemas.kt               # JSON Schema definition for the tool input
├── codex/
│   ├── CodexCommand.kt              # Builds the subprocess argument list (no shell)
│   ├── CodexExecutor.kt             # Spawns subprocess, enforces timeout, bounds output
│   ├── CodexResult.kt               # Serializable result returned to the caller
│   ├── CodexExecutionRequest.kt     # Validated request passed between layers
│   ├── SandboxMode.kt               # Enum: read-only / workspace-write / danger-full-access
│   ├── ApprovalMode.kt              # Enum: untrusted / on-request / never
│   └── ExecutionPhase.kt            # Enum: analysis / planning / implementation / ...
├── config/
│   ├── AppConfig.kt                 # Immutable config data class
│   └── EnvConfigLoader.kt           # Parses all CODEX_MCP_* env vars at startup
├── security/
│   ├── SecurityPolicy.kt            # Orchestrates all checks; returns Approved or Rejected
│   ├── InputValidator.kt            # Prompt length, dangerous patterns, taskId, metadata
│   ├── PathPolicy.kt                # Canonical path check against allowed roots
│   ├── OutputRedactor.kt            # Scrubs secrets from stdout/stderr; truncates output
│   └── EnvironmentPolicy.kt         # Builds subprocess env from passthrough allowlist
└── logging/
    └── AuditLogger.kt               # Structured AUDIT lines — prompt hash only, never raw
```

---

## Security

See [SECURITY.md](SECURITY.md) for a full description of the threat model, controls, and
responsible disclosure process.
