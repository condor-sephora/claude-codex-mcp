# fake-codex — Integration Test Fixture

This directory contains a fake Codex CLI executable used by the integration tests.

## Why a fake Codex?

Integration tests must be **deterministic** — they must not depend on:
- Having Codex CLI installed.
- Being authenticated with an OpenAI API key.
- Network access.
- Non-deterministic LLM output.

The fake Codex simulates the Codex CLI's subprocess interface so that the MCP server's
behavior can be tested mechanically and repeatedly.

## Usage

Set the `FAKE_CODEX_MODE` environment variable to control the behavior:

| Mode          | Description                                                  |
|---------------|--------------------------------------------------------------|
| `success`     | (default) Prints success output and exits 0                  |
| `stderr`      | Prints to both stdout and stderr, exits 0                    |
| `error`       | Writes error to stderr, exits 1                              |
| `exit42`      | Exits with code 42 (tests non-zero exit handling)            |
| `timeout`     | Sleeps 300 seconds (triggers timeout + kill handling)        |
| `large-stdout`| Writes ~120,000 chars to stdout (tests truncation)           |
| `large-stderr`| Writes ~120,000 chars to stderr (tests truncation)           |
| `secrets`     | Outputs secret-looking strings (tests redaction)             |
| `echo-args`   | Echoes all arguments (verifies command construction)         |
| `echo-env`    | Echoes all environment variables (verifies allowlist)        |
| `echo-cwd`    | Echoes the working directory (verifies cwd behavior)         |

## Files

- `fake-codex.sh` — Unix/macOS shell script.
- `fake-codex.cmd` — Windows batch script.

## Setup

The `makeTestFixturesExecutable` Gradle task runs automatically before integration tests
and marks `fake-codex.sh` as executable (`chmod +x`).

Set the `CODEX_PATH` environment variable to point to the appropriate script:

```
CODEX_PATH=/path/to/test-fixtures/fake-codex/fake-codex.sh
```

Integration tests configure this automatically via the `fake.codex.path` system property.
