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

# start.sh recorded the process identity ps reported at launch: pid, start time, executable,
# then the ledger. Only a pid that still reports the same start time and executable is ours;
# a recycled pid, or another project's validator, cannot share a start time to the second.
# Exact string comparison against the same ps fields, never a substring of a flattened
# command line, so a ledger or executable whose name merely extends ours is refused too.
{ read -r pid; read -r start_time; read -r executable; read -r ledger; } < "$PID_FILE" || true
if [[ -z "${pid:-}" || -z "${start_time:-}" || -z "${executable:-}" || -z "${ledger:-}" ]]; then
    echo "$PID_FILE does not hold pid, start time, executable and ledger lines; refusing to guess" >&2
    echo "which process to kill — remove it by hand if the validator is already gone" >&2
    exit 1
fi
if ! kill -0 "$pid" 2> /dev/null; then
    echo "pid $pid is not running; removing stale $PID_FILE"
    rm -f "$PID_FILE"
    exit 0
fi

# True while the pid still reports the identity start.sh recorded. Re-evaluated before every
# signal and on every poll, because a pid can be recycled the moment the validator exits;
# between one check and the signal that follows it there is an unavoidable but tiny window,
# which is why this is "the recorded validator, as far as ps can tell" rather than a guarantee.
is_ours() {
    local current_start current_executable
    current_start="$(ps -p "$pid" -o lstart= 2> /dev/null | sed 's/^ *//; s/ *$//')"
    current_executable="$(ps -p "$pid" -o comm= 2> /dev/null | sed 's/^ *//; s/ *$//')"
    [[ "$current_start" == "$start_time" && "$current_executable" == "$executable" ]]
}

if ! is_ours; then
    echo "pid $pid is now '$(ps -p "$pid" -o comm= | sed 's/^ *//')' started '$(ps -p "$pid" -o lstart= | sed 's/^ *//; s/ *$//')'," >&2
    echo "not the '$executable' started '$start_time' on ledger $ledger that start.sh launched;" >&2
    echo "refusing to kill it — remove $PID_FILE by hand if that validator is already gone" >&2
    exit 1
fi

kill "$pid"
for _ in $(seq 1 30); do
    # Gone, or the pid already belongs to something else: either way ours has exited.
    if ! is_ours; then
        rm -f "$PID_FILE"
        echo "stopped validator pid $pid"
        exit 0
    fi
    sleep 1
done
if ! is_ours; then
    rm -f "$PID_FILE"
    echo "stopped validator pid $pid"
    exit 0
fi
echo "pid $pid did not exit within 30s; sending SIGKILL" >&2
kill -9 "$pid" 2> /dev/null || true
rm -f "$PID_FILE"
