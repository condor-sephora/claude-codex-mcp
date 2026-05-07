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

## Delegation model and limitations

This server is designed for **pure coding delegation** — Claude gathers all the context it
needs, then hands off a self-contained task to Codex via a prompt string. Codex runs as an
isolated subprocess with no connection back to Claude's tool ecosystem.

**What this means in practice:**

- Codex cannot call MCP tools. Tools registered in Claude's session (Figma, GitHub, browser,
  databases, etc.) are not available to the Codex subprocess. If a task requires data from
  an external tool, Claude must fetch it first and pass the result as context in the prompt.
- There is no interactive approval loop. If Codex needs to ask a question or request a
  permission mid-task, there is no channel to route that back to Claude. Tasks must be
  scoped so that Codex can complete them autonomously with the information provided.
- The sandbox label controls what Codex permits itself to do internally, not what external
  services it can reach. Even `danger-full-access` only removes Codex's own file/command
  restrictions — it does not grant access to Claude's MCP registry.

**The right pattern for complex tasks:**

```
1. Claude uses its own MCP tools to gather context
   (read Figma designs, fetch API specs, read GitHub issues, etc.)
2. Claude composes a rich prompt that includes all necessary context
3. Claude calls execute_codex with that prompt and a cwd pointing at the repository
4. Codex implements the task autonomously using only the filesystem and its own tools
5. Claude receives the result and can act on it (review, commit, open a PR, etc.)
```

Tasks that fit this pattern — code generation, refactoring, adding tests, analysis,
running builds — work well. Tasks that require Codex to independently call external
services mid-execution do not fit this architecture.

---

## Requirements

| Dependency | Version | Notes |
|---|---|---|
| JDK | 11+ | Tested with JDK 21. Only needed to run the JAR. |
| `codex` CLI | any | Must be on `PATH`. Needs its own auth configured. |
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

Run this **once**.

```bash
claude mcp add codex \
  -- java -jar /path/to/claude-codex-mcp/build/libs/claude-codex-mcp-all.jar
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
| `CODEX_MCP_TIMEOUT_MS` | `120000` | Server-wide default wall-clock deadline (ms) for each `codex exec` subprocess. If Codex does not finish in time it is killed and the result returns `timedOut: true`. Can be overridden per-call via the `timeoutMs` tool argument (clamped to [5000, 600000]). Omitting both the env var and the per-call field falls back to 120 s — the server never fails due to a missing timeout. |
| `CODEX_MCP_MAX_PROMPT_CHARS` | `8000` | Maximum length of the `prompt` string in characters. The server rejects the call before spawning any subprocess if the limit is exceeded. Only the literal prompt text counts — file paths referenced in the prompt are not read by the server, so pointing Codex at a large file (`"read /path/to/spec.md and implement…"`) does not consume this budget. Raise the limit at startup if your prompts are legitimately longer. |
| `CODEX_MCP_MAX_OUTPUT_CHARS` | `60000` | Maximum characters captured from stdout and stderr independently. If Codex produces more output than the limit the excess is dropped and the result sets `stdoutTruncated` or `stderrTruncated` to `true`. Redaction always runs before truncation so secrets near the end of large output are never silently half-cut. Raise this if Codex tasks return large diffs or logs; lower it for tighter memory bounds on the server. |
| `CODEX_MCP_ALLOW_DANGER_FULL_ACCESS` | `false` | Set `true` to enable `danger-full-access` sandbox mode. In this mode Codex auto-approves all its own internal actions (file writes, command execution) without prompting. Use only when `workspace-write` is insufficient — for example when Codex needs to operate outside the project directory. See **Delegation model and limitations** below. |
| `CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST` | `PATH,HOME,USER,LOGNAME,SHELL,OPENAI_API_KEY,CODEX_HOME` | Controls which environment variables are forwarded into the Codex subprocess. When the server spawns `codex exec` it **clears the subprocess environment completely** first, then copies in only the variables listed here. This is a deliberate security measure: a developer's local machine typically holds the most sensitive credentials (AWS keys, database passwords, GitHub tokens, corporate SSO tokens), and without this control every Codex subprocess would inherit all of them. If Codex is ever tricked into dumping its environment via prompt injection in untrusted code or documents, only the allowlisted variables are exposed — your production credentials were never there to leak. The output redactor adds a second layer by scrubbing known secret patterns from stdout/stderr, but the allowlist is the stronger control because it prevents secrets from entering the subprocess at all. If a task legitimately needs an additional variable (e.g. `GITHUB_TOKEN` to push commits), add it explicitly — but note that setting this variable **replaces** the entire default list, so include the defaults too. |
| `CODEX_MCP_AUDIT_LOG_PATH` | stderr | File path for the structured audit log. Each line is `AUDIT <json>` — one JSON object per event, appended in real time. When not set, audit lines go to stderr. Set this to a file when you want to feed the log to Claude or Codex for pattern analysis. See **Audit log format** below. |

> **Security note on env passthrough** — The subprocess environment is rebuilt from scratch
> on every call. Only variables explicitly listed in `CODEX_MCP_ENV_PASSTHROUGH_ALLOWLIST`
> are forwarded. The Codex process never sees session tokens or any other MCP server internals.

---

## Tool schema: `execute_codex`

| Field | Type | Required | Description |
|---|---|---|---|
| `prompt` | string | **yes** | The instruction to pass to Codex. Max `CODEX_MCP_MAX_PROMPT_CHARS` chars. |
| `cwd` | string | no | Working directory for the Codex subprocess. Defaults to server CWD. |
| `sandbox` | enum | no | `read-only` (default), `workspace-write`, `danger-full-access` |
| `timeoutMs` | number | no | Per-call timeout override in ms. Clamped to [5000, 600000]. Overrides `CODEX_MCP_TIMEOUT_MS` for this call only — useful when different tasks within the same session need different deadlines (e.g. a quick analysis vs. a long implementation). |
| `taskId` | string | no | Caller-provided traceability label. Max 128 printable ASCII chars. Echoed in the result and written to every audit log line for that call. Useful for correlating Codex activity back to any external reference — Jira tickets, GitHub issues, PR numbers, sprint goals, feature flags, or free-text session labels. |

---

## Example tool calls

### Analysis — read only, Jira ticket

```json
{
  "prompt": "List all Kotlin source files and summarize the package structure",
  "cwd": "/Users/me/my-project",
  "sandbox": "read-only",
  "taskId": "SHP-1234"
}
```

### Implementation — write enabled, longer timeout, Jira ticket

```json
{
  "prompt": "Add unit tests for the PaymentService class following the existing test conventions in src/test",
  "cwd": "/Users/me/my-project",
  "sandbox": "workspace-write",
  "taskId": "SHP-1234",
  "timeoutMs": 180000
}
```

### GitHub pull request or issue

```json
{
  "prompt": "Review the diff in the current branch and suggest improvements to error handling",
  "cwd": "/Users/me/my-project",
  "sandbox": "read-only",
  "taskId": "PR-789"
}
```

### Sprint goal or initiative label

```json
{
  "prompt": "Refactor the checkout module to remove the legacy payment adapter",
  "cwd": "/Users/me/my-project",
  "sandbox": "workspace-write",
  "taskId": "Q2-checkout-cleanup"
}
```

### Feature flag or experiment

```json
{
  "prompt": "Implement the new loyalty points display component behind the LOYALTY_V2 feature flag",
  "cwd": "/Users/me/my-project",
  "sandbox": "workspace-write",
  "taskId": "flag-LOYALTY_V2"
}
```

### Free-text session label (no external tracker)

```json
{
  "prompt": "Find all usages of the deprecated ApiClient and replace them with the new HttpClient",
  "cwd": "/Users/me/my-project",
  "sandbox": "workspace-write",
  "taskId": "migration-apiclient-2026-05-07"
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
  "taskId": "SHP-1234"
}
```

| Field | Meaning |
|---|---|
| `exitCode` | Codex process exit code. `0` = success, `-1` = timed out or failed to start |
| `timedOut` | `true` if the process was killed due to timeout |
| `durationMs` | Wall-clock time from subprocess spawn to completion |
| `stdout` / `stderr` | Redacted, bounded Codex output |
| `stdoutTruncated` / `stderrTruncated` | `true` if output was cut at `CODEX_MCP_MAX_OUTPUT_CHARS` |
| `commandPreview` | Safe command log — raw prompt is replaced with a hash |
| `sandbox` | Confirms the isolation level that was applied |
| `taskId` | Echoes the caller-provided traceability ID |

---

## Security rejections

The server rejects calls before spawning any subprocess in these cases:

| Condition | Error message |
|---|---|
| Empty or blank prompt | `Prompt must not be empty` |
| Prompt exceeds max length | `Prompt length N exceeds maximum M characters` |
| Prompt matches exfiltration pattern | `Prompt was rejected by the defense-in-depth heuristic filter` |
| `cwd` does not exist or is not a directory | `cwd does not exist or is not a directory: <path>` |
| `danger-full-access` without opt-in | `sandbox=danger-full-access is disabled. Set CODEX_MCP_ALLOW_DANGER_FULL_ACCESS=true` |

All rejections produce `isError: true` in the MCP response and write an `AUDIT` line to the audit log with `"event":"security_rejection"`.

---

## Audit log format

Every call — approved or rejected — produces one `AUDIT <json>` line. The file is designed
to be fed directly to Claude or Codex to identify patterns, tune parameters, and diagnose failures.

### Invocation entry (`event: codex_invocation`)

```json
{
  "timestamp": "2026-05-07T10:00:00Z",
  "event": "codex_invocation",
  "sessionId": "a3f2c1b4",
  "taskId": "SHP-1234",
  "outcome": "success",
  "exitCode": 0,
  "timedOut": false,
  "durationMs": 16573,
  "sandbox": "read-only",
  "cwd": "/Users/me/my-project",
  "promptHash": "723e8514a1b2c3d4",
  "promptLength": 352,
  "timeoutMs": 120000,
  "stdoutChars": 4800,
  "stderrChars": 0,
  "stdoutTruncated": false,
  "stderrTruncated": false,
  "stderrNonEmpty": false
}
```

### Rejection entry (`event: security_rejection`)

```json
{
  "timestamp": "2026-05-07T10:00:01Z",
  "event": "security_rejection",
  "sessionId": "a3f2c1b4",
  "taskId": "none",
  "rejectionCategory": "dangerous_pattern",
  "reason": "Prompt was rejected by the defense-in-depth heuristic filter"
}
```

### Key fields for pattern analysis

| Field | What to look for |
|---|---|
| `outcome` | `success`, `codex_error`, or `timeout` — filter and count to see failure rates |
| `sessionId` | Groups all calls from one server process — compare sessions to spot regressions |
| `durationMs` | Calls approaching `timeoutMs` are candidates for a higher per-call timeout |
| `stderrNonEmpty` | `true` even on `exitCode=0` often signals warnings worth investigating |
| `stdoutTruncated` / `stderrTruncated` | Frequent truncation means `CODEX_MCP_MAX_OUTPUT_CHARS` is too low |
| `rejectionCategory` | `dangerous_pattern` spikes indicate prompt injection attempts; `prompt_too_long` means raise `CODEX_MCP_MAX_PROMPT_CHARS` |
| `promptLength` | Distribution of lengths helps right-size `CODEX_MCP_MAX_PROMPT_CHARS` |

### Feeding the log to Claude for analysis

```
Read the audit log at /path/to/audit.log and identify:
- Which taskIds have the highest failure rates
- Whether timeouts cluster around specific cwd paths or sandbox modes
- Whether stdoutTruncated is frequent enough to warrant raising CODEX_MCP_MAX_OUTPUT_CHARS
- Any rejectionCategory patterns that suggest misconfiguration
```

---

## Running tests

```bash
# Unit tests (no codex CLI needed)
./gradlew test

# Integration tests (uses fake-codex.sh stub — no real Codex or API key needed)
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
│   └── SandboxMode.kt               # Enum: read-only / workspace-write / danger-full-access
├── config/
│   ├── AppConfig.kt                 # Immutable config data class
│   └── EnvConfigLoader.kt           # Parses all CODEX_MCP_* env vars at startup
├── security/
│   ├── SecurityPolicy.kt            # Orchestrates all checks; returns Approved or Rejected
│   ├── InputValidator.kt            # Prompt length, dangerous patterns, taskId validation
│   ├── OutputRedactor.kt            # Scrubs secrets from stdout/stderr; truncates output
│   └── EnvironmentPolicy.kt         # Builds subprocess env from passthrough allowlist
└── logging/
    └── AuditLogger.kt               # Structured AUDIT lines — prompt hash only, never raw
```

---

## Security

See [SECURITY.md](SECURITY.md) for a full description of the threat model, controls, and
responsible disclosure process.
