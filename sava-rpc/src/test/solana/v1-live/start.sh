#!/usr/bin/env bash
# Starts a local solana-test-validator for LiveV1ValidatorCheck.
#
# solana-test-validator activates every feature at genesis, so `enable_tx_v1` is live
# from slot 0 on any binary that knows the gate. The 4.2 line is the first that does,
# and `LiveV1ValidatorCheck` was verified against 4.2.1, so anything older is refused
# up front rather than failing later with UnsupportedVersion on every send.
#
# Modelled on scripts/validator.sh in solana-foundation/transaction-v1-examples, minus
# the geyser plugin and the Token-2022 override sava does not need.
#
# Environment:
#   SOLANA_TEST_VALIDATOR  validator binary; default: solana-test-validator on PATH
#   LEDGER_DIR             ledger location; default: ./test-ledger next to this script
#   RPC_PORT               JSON-RPC port; default 8899 (LiveV1ValidatorCheck's default)
#   VALIDATOR_ARGS         extra flags, e.g. alternate ports when another validator holds
#                          the defaults: "--faucet-port 9901 --gossip-port 8101
#                          --dynamic-port-range 8102-8140"
#
# validator.pid records the launched process's identity as ps reported it right after the
# start — pid, start time and executable, one per line, then the ledger for the message —
# and stop.sh kills only a pid that still reports the same start time and executable. A
# recycled pid, another project's validator, or one on a ledger with a similar name all
# differ in start time, so none of them can be the one it kills; nothing is matched by
# substring or by splitting a flattened command line.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VALIDATOR="${SOLANA_TEST_VALIDATOR:-solana-test-validator}"
LEDGER_DIR="${LEDGER_DIR:-$HERE/test-ledger}"
RPC_PORT="${RPC_PORT:-8899}"
RPC_URL="http://127.0.0.1:$RPC_PORT"
PID_FILE="$HERE/validator.pid"
LOG_FILE="$HERE/validator.log"
MIN_MAJOR=4
MIN_MINOR=2

# The CLI next to a path-qualified validator is the matching version; otherwise
# whatever `solana` is on PATH answers `cluster-version` against any node.
if [[ "$VALIDATOR" == */* && -x "$(dirname "$VALIDATOR")/solana" ]]; then
    SOLANA_CLI="$(dirname "$VALIDATOR")/solana"
else
    SOLANA_CLI="solana"
fi

if ! command -v "$VALIDATOR" > /dev/null 2>&1; then
    echo "validator binary not found: $VALIDATOR (set SOLANA_TEST_VALIDATOR)" >&2
    exit 1
fi

# "solana-test-validator 4.2.1 (src:c4b48df9; feat:21b0d33a, client:Agave)"
version="$("$VALIDATOR" --version | awk '{ print $2 }')"
major="${version%%.*}"
rest="${version#*.}"
minor="${rest%%.*}"
if ! [[ "$major" =~ ^[0-9]+$ && "$minor" =~ ^[0-9]+$ ]]; then
    echo "could not parse the validator version from: $("$VALIDATOR" --version)" >&2
    exit 1
fi
if (( major < MIN_MAJOR || (major == MIN_MAJOR && minor < MIN_MINOR) )); then
    echo "$VALIDATOR is $version; transaction v1 needs $MIN_MAJOR.$MIN_MINOR or later" >&2
    echo "(the enable_tx_v1 gate does not exist below it). Point SOLANA_TEST_VALIDATOR at" >&2
    echo "an Anza 4.2.1+ release: https://github.com/anza-xyz/agave/releases" >&2
    exit 1
fi

is_running() {
    if command -v "$SOLANA_CLI" > /dev/null 2>&1; then
        "$SOLANA_CLI" --url "$RPC_URL" cluster-version > /dev/null 2>&1
    else
        curl -sf "$RPC_URL" -H 'Content-Type: application/json' \
            -d '{"jsonrpc":"2.0","id":1,"method":"getVersion"}' > /dev/null 2>&1
    fi
}

cluster_version() {
    if command -v "$SOLANA_CLI" > /dev/null 2>&1; then
        "$SOLANA_CLI" --url "$RPC_URL" cluster-version
    else
        curl -s "$RPC_URL" -H 'Content-Type: application/json' \
            -d '{"jsonrpc":"2.0","id":1,"method":"getVersion"}'
    fi
}

# Without this guard a second --reset validator would delete the ledger the running one
# has open, while the readiness check below still passed because the original is the one
# answering on the port.
if is_running; then
    echo "a validator is already answering on $RPC_URL: $(cluster_version)" >&2
    echo "it was not started by this script; stop it yourself or pick another RPC_PORT" >&2
    exit 1
fi
if [[ -f "$PID_FILE" ]]; then
    read -r recorded_pid _ < "$PID_FILE"
    if kill -0 "$recorded_pid" 2> /dev/null; then
        echo "pid $recorded_pid from $PID_FILE is still alive; run stop.sh first" >&2
        exit 1
    fi
fi

# Optional flags, word-split on purpose; an unset VALIDATOR_ARGS must expand to nothing
# rather than trip `set -u` inside the child before the validator even starts.
extra_args=()
if [[ -n "${VALIDATOR_ARGS:-}" ]]; then
    read -r -a extra_args <<< "$VALIDATOR_ARGS"
fi

mkdir -p "$LEDGER_DIR"
# `${extra_args[@]+...}` keeps an empty array expansion legal under `set -u` on bash 3.2.
"$VALIDATOR" \
    --reset \
    --quiet \
    --ledger "$LEDGER_DIR" \
    --rpc-port "$RPC_PORT" \
    ${extra_args[@]+"${extra_args[@]}"} \
    > "$LOG_FILE" 2>&1 &
validator_pid=$!
# Observed through ps so stop.sh compares like with like on every platform: lstart is exact
# to the second and comm is whatever this ps prints (a full path on macOS, a 15-character
# name on Linux), which is all that matters when the same command reads it back.
start_time="$(ps -p "$validator_pid" -o lstart= | sed 's/^ *//; s/ *$//')"
executable="$(ps -p "$validator_pid" -o comm= | sed 's/^ *//; s/ *$//')"
if [[ -z "$start_time" || -z "$executable" ]]; then
    echo "validator pid $validator_pid vanished before its identity could be recorded; see $LOG_FILE" >&2
    tail -50 "$LOG_FILE" >&2
    exit 1
fi
printf '%s\n%s\n%s\n%s\n' "$validator_pid" "$start_time" "$executable" "$LEDGER_DIR" > "$PID_FILE"
echo "started $VALIDATOR $version, pid $validator_pid, ledger $LEDGER_DIR, log $LOG_FILE"

for _ in $(seq 1 90); do
    if is_running; then
        echo "validator ready on $RPC_URL: $(cluster_version)"
        exit 0
    fi
    sleep 1
done
echo "validator did not become ready; see $LOG_FILE" >&2
tail -50 "$LOG_FILE" >&2
exit 1
