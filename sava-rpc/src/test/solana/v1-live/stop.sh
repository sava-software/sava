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

# start.sh recorded the process identity ps reported at launch — the pid, then start time and
# executable as one ps line, then the ledger. Only a pid that still reports that exact line is
# ours; a recycled pid, or another project's validator, cannot share a start time to the second.
# Exact string comparison of one ps observation, never a substring of a flattened command line,
# so a ledger or executable whose name merely extends ours is refused too.
{ read -r pid; read -r identity; read -r ledger; } < "$PID_FILE" || true
if [[ -z "${pid:-}" || -z "${identity:-}" || -z "${ledger:-}" ]]; then
    echo "$PID_FILE does not hold pid, identity and ledger lines; refusing to guess" >&2
    echo "which process to kill — remove it by hand if the validator is already gone" >&2
    exit 1
fi
if ! kill -0 "$pid" 2> /dev/null; then
    echo "pid $pid is not running; removing stale $PID_FILE"
    rm -f "$PID_FILE"
    exit 0
fi

# True while the pid still reports the identity start.sh recorded. Start time and executable
# are read in ONE ps call and compared as one string: read separately, a pid recycled between
# the two reads could pass with the old start time and a same-named replacement's executable.
# Re-evaluated before every signal and on every poll; the window between a check and the
# signal that follows it is that single ps call, which is why this is "the recorded validator,
# as far as ps can tell" rather than a guarantee.
observe() {
    ps -p "$pid" -o lstart= -o comm= 2> /dev/null | sed 's/^ *//; s/ *$//'
}
is_ours() {
    [[ "$(observe)" == "$identity" ]]
}

if ! is_ours; then
    echo "pid $pid is now '$(observe)', not the '$identity' on ledger $ledger that start.sh launched;" >&2
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
