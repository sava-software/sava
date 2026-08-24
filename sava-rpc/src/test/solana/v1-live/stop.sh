#!/usr/bin/env bash
# Stops the validator start.sh started — only that one. A blanket
# `pkill -f solana-test-validator` would take down validators this script never
# started, including ones belonging to other projects or other sessions.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$HERE/validator.pid"

if [[ ! -f "$PID_FILE" ]]; then
    echo "no pid file at $PID_FILE; leaving any running validator alone" >&2
    exit 0
fi

# start.sh writes "<pid> <ledger>": the ledger path is what makes the pid ours, since another
# project's validator, or a recycled pid, can carry the same executable name.
read -r pid ledger < "$PID_FILE"
if [[ -z "${pid:-}" || -z "${ledger:-}" ]]; then
    echo "$PID_FILE does not hold '<pid> <ledger>'; refusing to guess which process to kill" >&2
    exit 1
fi
if ! kill -0 "$pid" 2> /dev/null; then
    echo "pid $pid is not running; removing stale $PID_FILE"
    rm -f "$PID_FILE"
    exit 0
fi

# `args=` rather than `comm=`: Linux truncates comm to 15 characters ("solana-test-val"),
# which would make this guard refuse every validator it was asked to stop.
command_line="$(ps -p "$pid" -o args=)"
command_name="$(printf '%s\n' "$command_line" | awk '{ print $1 }' | xargs basename)"
if [[ "$command_name" != solana-test-validator* || "$command_line" != *"--ledger $ledger"* ]]; then
    echo "pid $pid is '$command_name' ($command_line)," >&2
    echo "not the solana-test-validator start.sh launched on ledger $ledger; refusing to kill it" >&2
    echo "remove $PID_FILE by hand if that validator is already gone" >&2
    exit 1
fi

kill "$pid"
for _ in $(seq 1 30); do
    if ! kill -0 "$pid" 2> /dev/null; then
        rm -f "$PID_FILE"
        echo "stopped validator pid $pid"
        exit 0
    fi
    sleep 1
done
echo "pid $pid did not exit within 30s; sending SIGKILL" >&2
kill -9 "$pid" 2> /dev/null || true
rm -f "$PID_FILE"
