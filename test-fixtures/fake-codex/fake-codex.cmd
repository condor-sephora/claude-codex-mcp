@echo off
REM fake-codex.cmd — Windows test fixture simulating the Codex CLI.
REM Controlled by FAKE_CODEX_MODE environment variable.

SET MODE=%FAKE_CODEX_MODE%
IF "%MODE%"=="" SET MODE=success

IF "%MODE%"=="success" (
    echo Codex executed successfully.
    EXIT /B 0
)
IF "%MODE%"=="stderr" (
    echo stdout output from codex
    echo stderr warning from codex 1>&2
    EXIT /B 0
)
IF "%MODE%"=="error" (
    echo Codex encountered an error. 1>&2
    EXIT /B 1
)
IF "%MODE%"=="exit42" (
    echo Exiting with code 42
    EXIT /B 42
)
IF "%MODE%"=="timeout" (
    ping -n 300 127.0.0.1 > nul
    EXIT /B 0
)
IF "%MODE%"=="echo-args" (
    echo ARGS: %*
    EXIT /B 0
)
IF "%MODE%"=="echo-cwd" (
    cd
    EXIT /B 0
)

echo fake-codex: unknown mode: %MODE% 1>&2
EXIT /B 127
