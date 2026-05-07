#!/usr/bin/env bash
# fake-codex.sh — Test fixture that simulates the Codex CLI for integration tests.
#
# Usage: fake-codex exec [--sandbox <mode>] [flags...] "<prompt>"
#
# Behavior is controlled by the FAKE_CODEX_MODE environment variable:
#   success      (default) — Print success output and exit 0.
#   stderr       — Print to both stdout and stderr, exit 0.
#   error        — Write error to stderr, exit 1.
#   exit42       — Exit with code 42.
#   timeout      — Sleep for 300 seconds (triggers timeout handling).
#   large-stdout — Write ~120,000 characters to stdout (exceeds default 60,000 limit).
#   large-stderr — Write ~120,000 characters to stderr.
#   secrets      — Output lines containing secret-looking patterns.
#   echo-args    — Print all arguments received (to verify command construction).
#   echo-env     — Print all environment variables (to verify allowlist enforcement).
#   echo-cwd     — Print the current working directory.

MODE="${FAKE_CODEX_MODE:-success}"

case "$MODE" in
  success)
    echo "Codex executed successfully."
    echo "Prompt received: ${*: -1}"
    exit 0
    ;;

  stderr)
    echo "stdout output from codex"
    echo "stderr warning from codex" >&2
    exit 0
    ;;

  error)
    echo "Codex encountered an error." >&2
    exit 1
    ;;

  exit42)
    echo "Exiting with code 42"
    exit 42
    ;;

  timeout)
    # Sleep longer than any reasonable test timeout.
    sleep 300
    echo "Should never reach here"
    exit 0
    ;;

  large-stdout)
    # Write approximately 120,000 characters — more than the default 60,000 limit.
    python3 -c "import sys; sys.stdout.write('X' * 120000); sys.stdout.write('\n')" 2>/dev/null \
      || printf '%0.s-' {1..2000} | xargs -I{} sh -c 'printf "%s" "$(head -c 60 /dev/urandom | tr -dc A-Za-z0-9 | head -c 60)"' 2>/dev/null \
      || for i in $(seq 1 2400); do printf '%060d' "$i"; done
    exit 0
    ;;

  large-stderr)
    # Write approximately 120,000 characters to stderr.
    # Note: no 2>/dev/null here — we need the stderr to actually reach the caller.
    python3 -c "import sys; sys.stderr.write('E' * 120000); sys.stderr.write('\n')" \
      || for i in $(seq 1 2400); do printf '%060d' "$i" >&2; done
    exit 0
    ;;

  secrets)
    echo "sk-abcdefghijklmnopqrstuvwxyz1234567890abcde"
    echo "GITHUB_TOKEN=ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ1234567890abcd"
    echo "Result: analysis completed"
    exit 0
    ;;

  echo-args)
    echo "ARGS_COUNT=$#"
    for i in "$@"; do
      echo "ARG: $i"
    done
    exit 0
    ;;

  echo-env)
    env | sort
    exit 0
    ;;

  echo-cwd)
    pwd
    exit 0
    ;;

  *)
    echo "fake-codex: unknown mode: $MODE" >&2
    exit 127
    ;;
esac
