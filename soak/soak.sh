#!/usr/bin/env bash
# Run one sava-rpc soak: build the harness, start the controlled peer JVM, start the client JVM
# (the subject) under a continuous flight recording and native-memory tracking, sample it, dump
# the recording at the phase boundaries that matter, post-process it, and write summary.md with
# a verdict and every number behind it.
#
#   soak.sh <validate|pilot|campaign|live> [--duration N] [--jfr-maxsize S] [--jfr-maxage S]
#                                          [--seed X] [--heap-mb N] [--control <id>]
#   soak.sh --controls          run every row of controls.tsv and write controls.md
#   soak.sh --selftest          run the attribution and detector self-test
#   soak.sh --report <runDir>   re-run SoakReport and the verdict over an existing run
#   soak.sh --help
#
# This harness is first-party and defensive: it drives THIS library's own websocket and HTTP
# clients - code that parses what untrusted nodes send - against local peers that inject faults
# on a recorded schedule, so that a defect is found here before a bad peer finds it in
# production. A finding becomes a deterministic regression test in sava-rpc's own test source
# set, never a change to this harness to make a run pass (../AGENTS.md).
#
# Results land in soak/build/runs/<profile>-<yyyyMMdd-HHmmss>/; README.md has the artifact map,
# the verdict rules and the exit codes. SOAK_DRY_RUN=1 resolves the configuration, prints the
# run.env it would write and the two launch lines, and starts nothing.
set -euo pipefail

# ---------------------------------------------------------------------------- constants

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
MODULE=software.sava.rpc.soak
CLIENT_MAIN=$MODULE/software.sava.rpc.soak.Main
PEER_MAIN=$MODULE/software.sava.rpc.soak.peer.PeerMain
REPORT_MAIN=$MODULE/software.sava.rpc.soak.report.SoakReport
SELFTEST_MAIN=$MODULE/software.sava.rpc.soak.selftest.SelfTest

# Bounds (judged design section 12). Every one of them is a stage that can hang.
BUILD_BOUND=600            # gradle jar + soakModulePath          -> INCOMPLETE
PEER_READY_BOUND=120       # PeerMain prints its ports            -> INCOMPLETE
CLIENT_READY_BOUND=120     # Main prints READY                    -> INCOMPLETE
FILTER_CHECK_BOUND=30      # every filter entry resolves          -> FAIL
JFR_DUMP_BOUND=600         # JFR.dump path-to-gc-roots=true       -> NOTE, exit dump is the fallback
CLIENT_TERM_BOUND=120      # SIGTERM the client, then SIGKILL
PEER_TERM_BOUND=60         # SIGTERM the peer, then SIGKILL
JFR_SUMMARY_BOUND=120
REPORT_BOUND=900           # SoakReport                           -> FAIL
JCMD_BOUND=60              # any single jcmd
SELFTEST_BOUND=300         # soak.sh --selftest as a controls row  -> that row misses
VIEW_INTERVAL=600          # jcmd JFR.view snapshot every 10 min
WATCHDOG_SLACK=1800        # whole-runner watchdog is duration + this

# Exit codes / status words, in the order they win when more than one applies.
STATUS_PASS=0; STATUS_FAIL=1; STATUS_FINDING=2; STATUS_INCOMPLETE=3; STATUS_INVALID=4

# The method-timing filter. Every name is verified in the sources; the grammar has no
# wildcards, silently ignores a typo, and never instruments a lambda body - which is why the
# runner gates on the -Xlog:jfr+methodtrace log rather than trusting the filter.
# WebSocketManager::checkConnection is deliberately ABSENT: it is an interface default method
# and the entry would be accepted and then silently do nothing.
METHOD_FILTER_ENTRIES=(
  software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket::connect
  software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket::checkCycle
  software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket::adopt
  software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket::retireConnection
  software.sava.rpc.json.http.client.JsonHttpClient::sendPostRequest
  software.sava.rpc.json.http.client.JsonHttpClient::readBody
  software.sava.services.solana.websocket.WebSocketManagerImpl::ensureWebSocket
  software.sava.services.solana.websocket.WebSocketManagerImpl::beginFailure
  jdk.internal.net.http.HttpClientImpl::sendAsync
)
# validate adds one hot method, once, to measure its call rate. Timing a 3-million-calls/second
# method cost ~12 % of execution samples when it was measured, so it never joins a campaign.
VALIDATE_EXTRA_FILTER=software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket::onText

# What control D14 reports as the subject's code source: deliberately outside sava-rpc/build, so
# the client's subject gate refuses it (exit 4). Mirrors HarnessControls.D14_CODE_SOURCE.
D14_CODE_SOURCE='file:///nonexistent/fake-subject/sava-rpc.jar'

# Views taken from the live JVM every VIEW_INTERVAL seconds, and from the recording at the end.
LIVE_VIEWS=(hot-methods contention-by-site pinned-threads thread-count native-memory-committed exception-count)
FINAL_VIEWS=(events-by-count hot-methods method-timing thread-count thread-cpu-load pinned-threads
             contention-by-site socket-reads-by-host socket-writes-by-host exception-count
             exception-by-type memory-leaks-by-class memory-leaks-by-site gc-pauses
             native-memory-committed latencies-by-type)

# Native-memory categories broken out into nmt.csv, and the two subtracted to give the gated
# total the slope is fitted over: Arena Chunk moves by megabytes between samples with nothing
# leaked, and Tracing is the recorder's own buffers, which grow with JFR's activity.
NMT_CATEGORIES='Java Heap|Class|Thread|Code|GC|Metaspace|Internal|Other|Arena Chunk|Tracing'
NMT_TRANSIENT='Arena Chunk|Tracing'

PEER_PID=""
CLIENT_PID=""
WATCHDOG_PID=""
LOCK_DIR=""
RUN_DIR=""

# ---------------------------------------------------------------------------- helpers

log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die() { printf 'soak.sh: %s\n' "$*" >&2; exit 2; }

usage() {
  cat >&2 <<'USAGE'
usage: soak.sh <validate|pilot|campaign|live> [options]
       soak.sh --controls | --controls-rescore <controlsDir> | --selftest | --report <runDir> | --help

profiles
  validate   240 s, short timings, per-frame socket events, the instrumentation shake-out
  pilot      3600 s at production timings
  campaign   28800 s; needs a green controls run newer than the sava revision under test
  live       against a real provider; every SOAK_LIVE_* value is required and has no default,
             no local peer is started, and no X-Soak-* header is ever sent

options
  --duration N        whole run in seconds, warm-up included, quiesce and drain excluded
  --jfr-maxsize S     recording ring, e.g. 256m or 1g
  --jfr-maxage S      recording age bound, e.g. 2h
  --seed X            long, decimal or 0x hex; the fault schedule and every workload derive
                      from it, so a run is repeatable
  --heap-mb N         -Xms and -Xmx together
  --control <id>      run one row of controls.tsv on its own
  --controls-rescore <controlsDir>
                      re-derive every row's verdict of an existing sheet from its recorded
                      artifacts under the current runner (soak.sh --report per row) and re-judge
                      the sheet; nothing is re-run. For a verdict rule that changed, not for code.

environment
  Any SOAK_* key from DESIGN.md section 5 overrides its profile default; the resolved value is
  written to <run>/run.env and both JVMs read it there. An unknown SOAK_* key is a startup
  error, so the runner writes only the documented keys.
  SOAK_DRY_RUN=1      resolve everything, print run.env and the launch lines, start nothing
  SOAK_SKIP_BUILD=1   use build/soak/{java,module-path}.txt as they are (no Gradle)

exit codes
  0 PASS   1 FAIL   2 PASS-WITH-FINDING   3 INCOMPLETE   4 INVALID
USAGE
  exit 2
}

# stop_pid <pid> <signal> <label> [bound=30]: signal, wait up to the bound, then SIGKILL.
stop_pid() {
  local pid=$1 signal=$2 label=$3 bound=${4:-30} waited=0
  [ -n "$pid" ] || return 0
  kill -0 "$pid" 2>/dev/null || return 0
  kill "-$signal" "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt $((bound * 2)) ]; do
    sleep 0.5
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    log "$label (pid $pid) ignored SIG$signal for $bound s, killing"
    kill -KILL "$pid" 2>/dev/null || true
  fi
}

# run_bounded <bound> <label> <command...>: the command's status, or 124 when it was killed.
run_bounded() {
  local bound=$1 label=$2 waited=0 pid
  shift 2
  "$@" &
  pid=$!
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt "$bound" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    log "$label did not finish within $bound s, abandoning it"
    kill -TERM "$pid" 2>/dev/null || true
    sleep 1
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    return 124
  fi
  wait "$pid"
}

# Invoked by the EXIT trap below, not by name.
# shellcheck disable=SC2329
cleanup() {
  stop_pid "$WATCHDOG_PID" TERM "watchdog" 5
  # SIGTERM runs the client's shutdown hook and the recording's dumponexit, so even an
  # interrupted run leaves soak-exit.jfr and the counters behind.
  stop_pid "$CLIENT_PID" TERM "client" "$CLIENT_TERM_BOUND"
  stop_pid "$PEER_PID" TERM "peer" "$PEER_TERM_BOUND"
  # take_lock writes the pid file INSIDE the lock directory, so an rmdir of it always failed
  # with ENOTEMPTY and the lock outlived every run: exclusion then degraded to 'is the recorded
  # pid still alive', and every start took the non-atomic stale-recovery branch. Only the owner
  # releases it, so a cleanup in a child can never drop the parent's lock.
  if [ -n "$LOCK_DIR" ] && [ "$(cat "$LOCK_DIR/pid" 2>/dev/null || true)" = "$$" ]; then
    rm -rf "$LOCK_DIR" 2>/dev/null || true
  fi
  [ -z "$RUN_DIR" ] || rm -f "$RUN_DIR/.running" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# One Gradle invocation per repository root at a time, and one soak at a time: the two JVMs,
# the jcmd attaches and the recording repository all assume they own build/.
take_lock() {
  local lock=$SCRIPT_DIR/build/soak/.lock owner
  [ "${SOAK_NO_LOCK:-0}" != 1 ] || return 0
  mkdir -p "$SCRIPT_DIR/build/soak"
  if ! mkdir "$lock" 2>/dev/null; then
    owner=$(cat "$lock/pid" 2>/dev/null || true)
    if [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null; then
      die "another soak is running (pid $owner); its lock is $lock"
    fi
    log "removing the stale lock of pid ${owner:-unknown} at $lock"
    rm -rf "$lock"
    mkdir "$lock" || die "cannot take the lock at $lock"
  fi
  echo "$$" > "$lock/pid"
  LOCK_DIR=$lock
}

# ---------------------------------------------------------------------------- arguments

PROFILE=""
MODE=run
REPORT_ONLY_DIR=""
OPT_DURATION=""; OPT_MAXSIZE=""; OPT_MAXAGE=""; OPT_SEED=""; OPT_HEAP=""; OPT_CONTROL=""
# Captured before the option loop consumes them: run.env's first line is the exact command that
# produced the run, which is what makes a run reproducible from its own artifacts.
RAW_ARGS=("$@")

[ $# -ge 1 ] || usage
case "$1" in
  --help|-h) usage ;;
  --controls) MODE=controls; shift ;;
  --selftest) MODE=selftest; shift ;;
  --report) MODE=report; shift; [ $# -ge 1 ] || die "--report needs a run directory"
            REPORT_ONLY_DIR=$1; shift ;;
  --controls-rescore) MODE=controls_rescore; shift
            [ $# -ge 1 ] || die "--controls-rescore needs a controls run directory"
            RESCORE_DIR=$1; shift ;;
  validate|pilot|campaign|live|control) PROFILE=$1; shift ;;
  *) die "unknown profile or mode '$1'; try --help" ;;
esac
while [ $# -gt 0 ]; do
  case "$1" in
    --duration) OPT_DURATION=${2:-}; shift 2 ;;
    --jfr-maxsize) OPT_MAXSIZE=${2:-}; shift 2 ;;
    --jfr-maxage) OPT_MAXAGE=${2:-}; shift 2 ;;
    --seed) OPT_SEED=${2:-}; shift 2 ;;
    --heap-mb) OPT_HEAP=${2:-}; shift 2 ;;
    --control) OPT_CONTROL=${2:-}; shift 2 ;;
    --help|-h) usage ;;
    *) die "unknown option '$1'; try --help" ;;
  esac
done
for n in "$OPT_DURATION" "$OPT_HEAP"; do
  [ -z "$n" ] || case "$n" in *[!0-9]*) die "expected a whole number, got '$n'" ;; esac
done
for s in "$OPT_MAXSIZE" "$OPT_MAXAGE"; do
  [ -z "$s" ] || case "$s" in ''|*[!0-9dhkmsgKMG]*) die "expected a JFR size or duration, got '$s'" ;; esac
done

DRY_RUN=${SOAK_DRY_RUN:-0}

# ---------------------------------------------------------------------------- toolchain

resolve_toolchain() {
  MODULE_PATH_FILE=$SCRIPT_DIR/build/soak/module-path.txt
  JAVA_FILE=$SCRIPT_DIR/build/soak/java.txt
  [ -s "$MODULE_PATH_FILE" ] || die "no $MODULE_PATH_FILE; run '../gradlew soakModulePath' first"
  MODULE_PATH=$(cat "$MODULE_PATH_FILE")
  JAVA=java
  [ ! -s "$JAVA_FILE" ] || JAVA=$(cat "$JAVA_FILE")
  # jcmd and jfr must come from beside the launcher the module was compiled for, never a PATH
  # JDK of another release: the .jfc, the method-filter grammar and the dump options are all
  # release-specific.
  JAVA_BIN=$(cd "$(dirname "$(command -v "$JAVA")")" && pwd)
  JCMD=$JAVA_BIN/jcmd
  JFR=$JAVA_BIN/jfr
  for tool in "$JCMD" "$JFR"; do
    [ -x "$tool" ] || die "missing JDK tool beside $JAVA: $tool"
  done
}

build_harness() {
  if [ "${SOAK_SKIP_BUILD:-0}" = 1 ] || [ "$DRY_RUN" = 1 ]; then
    log "skipping the build"
    return 0
  fi
  log "building the harness (bound ${BUILD_BOUND}s)"
  local status=0
  run_bounded "$BUILD_BOUND" "gradle jar soakModulePath" \
    "$ROOT/gradlew" -p "$SCRIPT_DIR" jar soakModulePath -q || status=$?
  [ "$status" -eq 0 ] || { BUILD_FAILED="gradle jar soakModulePath exited $status"; return 1; }
  return 0
}

# ---------------------------------------------------------------------------- configuration

# set_default <KEY> <value>: a profile default, applied only when neither the environment nor a
# command-line option already decided. Resolution order is option, then environment, then this.
set_default() {
  local key=$1 value=$2
  if [ -z "${!key+set}" ]; then
    printf -v "$key" '%s' "$value"
    export "${key?}"
  fi
}

# by_profile <KEY> <validate> <pilot> <campaign>: the same three-column table SoakConfig.defaults
# resolves through Profile.pick, and it has to agree with it key for key - control reads the
# validate column and live reads pilot's, exactly as Profile.pick does.
#
# A control is the negative of the validation it guards: it exists to show that the verdict a
# validate run produces could have seen a defect, so it has to be measured under the knobs that
# run uses. Mapping control to pilot's column meant every row of the sheet subscribed, paced,
# faulted and drained unlike any run it was evidence about, and the gates a validate run is
# judged by had never been exercised by a control at all.
by_profile() {
  local key=$1 v=$2 p=$3 c=$4
  case "$RESOLVED_PROFILE" in
    validate|control) set_default "$key" "$v" ;;
    campaign)         set_default "$key" "$c" ;;
    *)                set_default "$key" "$p" ;;
  esac
}

resolve_profile() {
  RESOLVED_PROFILE=$1
  # A 30-minute pilot protects the campaign from a harness regression under campaign knobs, and
  # nothing a pilot found in the first week needed its second half hour. The campaign's length is
  # the leak floor of its after-GC gate (an 8 MiB rise): four hours resolve about 2 MiB/h and are
  # the iteration length; the release check runs the same profile with
  # SOAK_DURATION_SECONDS=28800 for the 1 MiB/h floor. Decided 2026-09-23.
  by_profile SOAK_DURATION_SECONDS   240   1800  14400
  by_profile SOAK_WARMUP_SECONDS     30    360   900
  by_profile SOAK_QUIESCE_SECONDS    30    120   300
  by_profile SOAK_DRAIN_SECONDS      30    60    60
  set_default SOAK_SEED              0x5A7A50A40001
  set_default SOAK_WS_ENGINES        4
  by_profile SOAK_WS_SUBSCRIPTIONS   24    64    128
  by_profile SOAK_WS_CHURN_PER_MINUTE 120  60    60
  by_profile SOAK_WS_NOTIFY_RPS      200   200   400
  by_profile SOAK_HTTP_CONCURRENCY   8     16    16
  set_default SOAK_HTTP_INFLIGHT     4
  by_profile SOAK_HTTP_RPS           20    40    40
  by_profile SOAK_LARGE_FRACTION     0.10  0.05  0.05
  set_default SOAK_NOWRAP_FRACTION   0.05
  set_default SOAK_CANCEL_FRACTION   0.02
  by_profile SOAK_FAULT_RATE         20    120   300
  by_profile SOAK_FAULT_MIN_PER_KIND 1     3     8
  # Twin of SoakConfig.defaults' SoakConfig.DESTRUCTIVE_PER_HOUR triple. The runner exports its
  # value before the client JVM starts, so this column is the one every ./soak.sh run uses and the
  # compiled one only covers a JVM started by hand: while they disagreed, the retirement budget the
  # design states was not the budget any soak ran under.
  by_profile SOAK_DESTRUCTIVE_PER_HOUR 240 60    30
  by_profile SOAK_STORM_SECONDS      20    60    60
  by_profile SOAK_CHURN_PERIOD_SECONDS 15  15    30
  by_profile SOAK_HEAP_MB            512   1024  1024
  # Appended verbatim to the client JVM's own command line, split on whitespace. It is a
  # measurement aid for a single row that has to shape the JVM rather than the workload (D10 needs
  # more collections than its heap gives in a short run), so it stays empty everywhere else: a
  # campaign that tuned the subject's GC would be measuring a JVM nobody deploys.
  set_default SOAK_JVM_EXTRA         ""
  # The ring must outlast the run: 1g/4h covered 94 % of an 8-h campaign and had lost its first
  # half hour by the end (measured 2026-09-23). Default age = the run's hours + 1, size = 256m per
  # hour of run, so a release-length 28800 s campaign gets 2g/9h; explicit values win.
  local ring_hours=$(( (SOAK_DURATION_SECONDS + 3599) / 3600 ))
  [ "$ring_hours" -ge 1 ] || ring_hours=1
  set_default SOAK_JFR_MAXSIZE       "$(( ring_hours * 256 ))m"
  set_default SOAK_JFR_MAXAGE        "$(( ring_hours + 1 ))h"
  by_profile SOAK_NMT_INTERVAL_SECONDS 60  600   1800
  by_profile SOAK_SAMPLE_SECONDS     10    30    30
  set_default SOAK_GAUGE_SECONDS     10
  by_profile SOAK_THREAD_MARGIN      24    16    16
  by_profile SOAK_FD_MARGIN          96    64    64
  by_profile SOAK_VTHREAD_MARGIN     64    32    32
  by_profile SOAK_SKIP_LIMIT_PCT     10    5     5
  by_profile SOAK_RSS_SLOPE_KIB_PER_HOUR 4096 2048 2048
  # The validate floor is what a JVM's first minutes drift by with nothing retained: measured
  # 2026-09-22 over three validate runs, RSS grew 24-54 MiB in the window while NMT's committed
  # total was flat or falling and the after-GC heap floor fell (JIT, class data, lazily touched
  # GC structures). A 16 MiB floor made the gate a coin toss on every green run; the pilot and
  # campaign windows are long enough for a real slope to show against their tighter floors.
  by_profile SOAK_RSS_NOISE_FLOOR_KIB 65536 8192 8192
  # The after-GC heap floor has its own floor: it does not carry the JIT/class-data drift RSS
  # does, and D10 (96 MiB/min retained up to a quarter of the heap) must be able to beat it.
  by_profile SOAK_HEAP_FLOOR_NOISE_KIB 16384 8192 8192
  set_default SOAK_HEAP_FLOOR_SLOPE_KIB_PER_HOUR 1024
  by_profile SOAK_PING_DELAY_MS      4000  15000 15000
  by_profile SOAK_CHECK_DELAY_MS     1000  2000  2000
  set_default SOAK_HTTP_CLIENT_EXECUTOR platform
  by_profile SOAK_SOCKET_THRESHOLD_MS 0    20    20
  set_default SOAK_CONTROL           ""
  set_default SOAK_MANAGER_REFLECT   0
  set_default SOAK_LIVE              0
  # Peer-side knobs, read by PeerMain from the same file.
  set_default SOAK_PEER_HTTP_THREADS 16
  set_default SOAK_BLOCK_TXS         400
  set_default SOAK_PGA_ACCOUNTS      500
  set_default SOAK_BURST_FRAMES      256
  set_default SOAK_BURST_PERIOD_SECONDS 120
  set_default SOAK_LARGE_PERIOD_SECONDS 300
  set_default SOAK_LARGE_BYTES       8388608
  set_default SOAK_FRAGMENTS         37
  set_default SOAK_REFUSE_SECONDS    20
  set_default SOAK_SLOW_CONSUMER_MICROS 2000
  set_default SOAK_THROW_EVERY       7
  set_default SOAK_WS_PORT_COUNT     5

  # Command-line options win over everything.
  [ -z "$OPT_DURATION" ] || SOAK_DURATION_SECONDS=$OPT_DURATION
  [ -z "$OPT_MAXSIZE" ] || SOAK_JFR_MAXSIZE=$OPT_MAXSIZE
  [ -z "$OPT_MAXAGE" ] || SOAK_JFR_MAXAGE=$OPT_MAXAGE
  [ -z "$OPT_SEED" ] || SOAK_SEED=$OPT_SEED
  [ -z "$OPT_HEAP" ] || SOAK_HEAP_MB=$OPT_HEAP
  [ -z "$OPT_CONTROL" ] || SOAK_CONTROL=$OPT_CONTROL
  SOAK_PROFILE=$RESOLVED_PROFILE

  # No control-specific shortening lives here any more: a control row is a validate run plus the
  # row's own overrides, and nothing else. The phases it shortened are the phases several gates are
  # read at - a 15 s drain is not the drain a validate run gives its in-flight requests, so a
  # control that passed or failed at the end of one said nothing about the run it guards.

  if [ "$RESOLVED_PROFILE" = live ]; then
    SOAK_LIVE=1
    # No defaults are compiled in for a run against somebody else's node: every value is an
    # explicit decision, and mainnet needs its own confirmation.
    local key
    for key in SOAK_LIVE_HTTP_URL SOAK_LIVE_WS_URL SOAK_LIVE_CLUSTER SOAK_LIVE_RPS \
               SOAK_LIVE_CONCURRENCY SOAK_LIVE_ACCOUNTS; do
      [ -n "${!key:-}" ] || die "$key is required for the live profile and has no default"
    done
    case "$SOAK_LIVE_CLUSTER" in
      local|devnet|testnet) ;;
      mainnet) [ "${SOAK_LIVE_CONFIRM:-}" = mainnet ] ||
                 die "SOAK_LIVE_CONFIRM must equal 'mainnet' to soak against mainnet" ;;
      *) die "SOAK_LIVE_CLUSTER must be local|devnet|testnet|mainnet, got '$SOAK_LIVE_CLUSTER'" ;;
    esac
    [ -s "${SOAK_LIVE_ACCOUNTS}" ] || die "SOAK_LIVE_ACCOUNTS is not a readable file: $SOAK_LIVE_ACCOUNTS"
  fi
}

# Every key written to run.env, in the order DESIGN.md section 5 lists them. An unknown SOAK_*
# key is a startup error in both JVMs, so this list is the contract and nothing else is emitted.
RUN_ENV_KEYS=(
  SOAK_PROFILE SOAK_RUN_DIR SOAK_DURATION_SECONDS SOAK_WARMUP_SECONDS SOAK_QUIESCE_SECONDS
  SOAK_DRAIN_SECONDS SOAK_SEED SOAK_WS_ENGINES SOAK_WS_SUBSCRIPTIONS SOAK_WS_CHURN_PER_MINUTE
  SOAK_WS_NOTIFY_RPS SOAK_HTTP_CONCURRENCY SOAK_HTTP_INFLIGHT SOAK_HTTP_RPS SOAK_LARGE_FRACTION
  SOAK_NOWRAP_FRACTION SOAK_CANCEL_FRACTION SOAK_FAULT_RATE SOAK_FAULT_MIN_PER_KIND
  SOAK_DESTRUCTIVE_PER_HOUR SOAK_STORM_SECONDS SOAK_CHURN_PERIOD_SECONDS SOAK_HEAP_MB
  SOAK_JVM_EXTRA SOAK_JFR_MAXSIZE SOAK_JFR_MAXAGE SOAK_NMT_INTERVAL_SECONDS SOAK_SAMPLE_SECONDS
  SOAK_GAUGE_SECONDS SOAK_THREAD_MARGIN SOAK_FD_MARGIN SOAK_VTHREAD_MARGIN SOAK_SKIP_LIMIT_PCT
  SOAK_RSS_SLOPE_KIB_PER_HOUR SOAK_RSS_NOISE_FLOOR_KIB SOAK_HEAP_FLOOR_SLOPE_KIB_PER_HOUR
  SOAK_HEAP_FLOOR_NOISE_KIB
  SOAK_PING_DELAY_MS SOAK_CHECK_DELAY_MS SOAK_HTTP_CLIENT_EXECUTOR SOAK_SOCKET_THRESHOLD_MS
  SOAK_CONTROL SOAK_MANAGER_REFLECT SOAK_LIVE
  SOAK_PEER_HTTP_THREADS SOAK_BLOCK_TXS SOAK_PGA_ACCOUNTS SOAK_BURST_FRAMES
  SOAK_BURST_PERIOD_SECONDS SOAK_LARGE_PERIOD_SECONDS SOAK_LARGE_BYTES SOAK_FRAGMENTS
  SOAK_REFUSE_SECONDS SOAK_SLOW_CONSUMER_MICROS SOAK_THROW_EVERY SOAK_WS_PORT_COUNT
)
LIVE_KEYS=(SOAK_LIVE_HTTP_URL SOAK_LIVE_WS_URL SOAK_LIVE_CLUSTER SOAK_LIVE_CONFIRM SOAK_LIVE_RPS
           SOAK_LIVE_CONCURRENCY SOAK_LIVE_ACCOUNTS)

# write_run_env <file>: the exact command line first, then every resolved value. SOAK_WS_PORTS
# and SOAK_HTTP_PORT are appended after the peer prints them - the peer reads this same file, so
# it cannot be written with ports it has not chosen yet.
write_run_env() {
  local file=$1 key
  {
    printf '# %s\n' "$COMMAND_LINE"
    for key in "${RUN_ENV_KEYS[@]}"; do
      printf '%s=%s\n' "$key" "${!key}"
    done
    if [ "${SOAK_LIVE}" = 1 ]; then
      for key in "${LIVE_KEYS[@]}"; do
        [ -z "${!key:-}" ] || printf '%s=%s\n' "$key" "${!key}"
      done
    fi
  } > "$file"
}

# ---------------------------------------------------------------------------- launch lines

# The client JVM's line, verified end to end on openjdk-25.0.2:
#  - G1 is this machine's ergonomic default and is named anyway so the run record is
#    self-describing; -Xms = -Xmx so the heap-floor slope is not a heap-growth ramp, and
#    AlwaysPreTouch so the RSS slope is not a heap-paging ramp either: without it a 512m heap
#    is committed at start but its pages are first touched as allocation reaches them, which
#    read as +287 MiB of RSS over the first three minutes of a validate run.
#  - old-object-queue-size belongs on -XX:FlightRecorderOptions. On -XX:StartFlightRecording it
#    is WARNED about and ignored; FlightRecorderOptions fails the JVM on an unknown key while
#    StartFlightRecording only warns, which is why the launch gate greps for '[warning][jfr,'.
#  - stackdepth 96 because sava's callback stacks pass through JDK websocket, SequentialScheduler
#    and CompletableFuture frames and the default 64 truncates them.
#  - filename requires dumponexit=true, and maxsize must be explicit or the JVM silently uses
#    250 MB.
#  - -Xlog:jfr+methodtrace to a FILE: it is startup chatter, and the only evidence a filter took.
build_client_command() {
  local run=$1 filter=$2 opens_ravina=$3
  CLIENT_CMD=(
    "$JAVA"
    -XX:+UseG1GC -XX:+AlwaysPreTouch
    "-Xms${SOAK_HEAP_MB}m" "-Xmx${SOAK_HEAP_MB}m"
    -XX:NativeMemoryTracking=summary
    -XX:+HeapDumpOnOutOfMemoryError "-XX:HeapDumpPath=$run/heap"
    "-XX:FlightRecorderOptions:repository=$run/jfr-repo,stackdepth=96,old-object-queue-size=4096"
    "-XX:StartFlightRecording:name=soak,settings=$run/config/sava-soak.jfc,disk=true,maxsize=$SOAK_JFR_MAXSIZE,maxage=$SOAK_JFR_MAXAGE,dumponexit=true,filename=$run/jfr/soak-exit.jfr,method-timing=$filter,report-on-exit=thread-count,report-on-exit=native-memory-committed,report-on-exit=exception-count"
    "-Xlog:jfr+methodtrace=debug:file=$run/logs/jfr-methodtrace.log"
    "-Djava.util.logging.config.file=$run/config/soak-logging.properties"
    --add-opens "software.sava.rpc/software.sava.rpc.json.http.ws=$MODULE"
  )
  # The ravina open is the self-test's reflective oracle only; a soak never reads the manager's
  # private state, so the flag is absent unless SOAK_MANAGER_REFLECT=1 asks for it.
  if [ "$opens_ravina" = 1 ]; then
    CLIENT_CMD+=(--add-opens "software.sava.ravina_solana/software.sava.services.solana.websocket=$MODULE")
  fi
  # Control D14: the subject gate runs before any workload exists, so the fake code source it
  # must refuse can only arrive on the launch line. The value is HarnessControls.D14_CODE_SOURCE.
  if [ "$SOAK_CONTROL" = D14 ]; then
    CLIENT_CMD+=("-Dsoak.subject.codeSource=$D14_CODE_SOURCE")
  fi
  # SOAK_JVM_EXTRA goes last among the VM options, so a row can override anything set above it,
  # and before -p/-m, where it would be an argument to Main instead. It is split on whitespace
  # like a shell command line rather than passed as one word, and it is in run.env and in the
  # printed launch line because a JVM flag that shaped a measurement has to be recoverable from
  # the run directory alone.
  if [ -n "${SOAK_JVM_EXTRA:-}" ]; then
    # commas separate arguments as well as whitespace: a controls.tsv override is one token
    local -a jvm_extra=(); read -r -a jvm_extra <<< "${SOAK_JVM_EXTRA//,/ }"
    CLIENT_CMD+=("${jvm_extra[@]}")
  fi
  CLIENT_CMD+=(-p "$MODULE_PATH" -m "$CLIENT_MAIN" "$run")
}

build_peer_command() {
  local run=$1
  # The peer carries no JFR and no NMT: the retention evidence is process-wide, and a client OOM
  # must not take the oracle down with it.
  PEER_CMD=("$JAVA" "-Xmx512m" -p "$MODULE_PATH" -m "$PEER_MAIN" "$run")
}

join_filter() {
  local out="" e
  for e in "$@"; do
    [ -z "$out" ] && out=$e || out="$out;$e"
  done
  printf '%s' "$out"
}

quoted() { local out="" a; for a in "$@"; do out="$out${out:+ }$(printf '%q' "$a")"; done; printf '%s' "$out"; }

# ---------------------------------------------------------------------------- sampling

sample_rss() {
  local rss
  rss=$(ps -o rss= -p "$CLIENT_PID" 2>/dev/null | tr -d ' ' || true)
  [ -z "$rss" ] || echo "$(date +%s),$rss" >> "$RUN_DIR/rss.csv"
}

# nmt_row <epoch> <label> <elapsed>: one 'VM.native_memory summary.diff' on stdin -> one csv row.
# NMT prints a diff only when it is non-zero and omits a category under 1 KB, so both default 0.
nmt_row() {
  awk -v epoch="$1" -v label="$2" -v elapsed="$3" -v categories="$NMT_CATEGORIES" -v transient="$NMT_TRANSIENT" '
    function committed(line) {
      C = 0; D = 0
      if (match(line, /committed=[0-9]+KB( [+-][0-9]+KB)?/)) {
        field = substr(line, RSTART + 10, RLENGTH - 10)
        C = field + 0
        if (match(field, / [+-][0-9]+KB/)) D = substr(field, RSTART + 1, RLENGTH - 3) + 0
      }
    }
    /^Total:/ {
      if (match($0, /reserved=[0-9]+KB/)) total_r = substr($0, RSTART + 9, RLENGTH - 11) + 0
      committed($0); total_c = C; total_d = D
    }
    /^- / {
      name = $0; sub(/^-[ ]+/, "", name); sub(/ \(reserved=.*/, "", name)
      committed($0); cat_c[name] = C; cat_d[name] = D
    }
    END {
      printf "%s,%s,%s,%d,%d,%d", epoch, label, elapsed, total_r + 0, total_c + 0, total_d + 0
      n = split(categories, cats, "|")
      for (i = 1; i <= n; i++) printf ",%d,%d", cat_c[cats[i]] + 0, cat_d[cats[i]] + 0
      gated = total_c + 0
      t = split(transient, skip, "|")
      for (i = 1; i <= t; i++) gated -= cat_c[skip[i]] + 0
      printf ",%d\n", gated
    }'
}

nmt_header() {
  local header="epoch_s,label,elapsed_s,total_reserved_kb,total_committed_kb,total_committed_diff_kb" category column
  local -a cats
  IFS='|' read -r -a cats <<< "$NMT_CATEGORIES"
  for category in "${cats[@]}"; do
    column=$(printf '%s' "$category" | tr '[:upper:] ' '[:lower:]_')
    header="$header,${column}_committed_kb,${column}_diff_kb"
  done
  printf '%s,gated_committed_kb\n' "$header"
}

nmt_diff() {
  local label=$1 now output status=0
  now=$(date +%s)
  output=$(run_bounded "$JCMD_BOUND" "jcmd VM.native_memory summary.diff ($label)" \
             "$JCMD" "$CLIENT_PID" VM.native_memory summary.diff 2>&1) || status=$?
  {
    echo "=== $(date -r "$now" '+%Y-%m-%d %H:%M:%S') $label (elapsed $((now - RUN_START)) s, jcmd exit $status) ==="
    printf '%s\n\n' "$output"
  } >> "$RUN_DIR/nmt/nmt.log"
  if [ "$status" -eq 0 ] && printf '%s\n' "$output" | grep -q '^Total:'; then
    printf '%s\n' "$output" | nmt_row "$now" "$label" "$((now - RUN_START))" >> "$RUN_DIR/nmt.csv"
  else
    log "VM.native_memory summary.diff ($label) failed (exit $status); see nmt/nmt.log"
  fi
}

take_views() {
  local elapsed=$1 view file=$RUN_DIR/views/$1.txt
  : > "$file"
  for view in "${LIVE_VIEWS[@]}"; do
    {
      echo "=== JFR.view $view at ${elapsed}s ==="
      run_bounded "$JCMD_BOUND" "jcmd JFR.view $view" "$JCMD" "$CLIENT_PID" JFR.view "$view" 2>&1 || true
      echo
    } >> "$file"
  done
}

# phase_reached <name>: phases.tsv is 'epochMillis\tname\tindex', appended by Main at each
# boundary. The steady-end dump fires when QUIESCE opens, the root-walk dump when DRAIN does.
phase_reached() {
  [ -s "$RUN_DIR/phases.tsv" ] || return 1
  awk -F'\t' -v want="$1" '$2 == want { found = 1 } END { exit !found }' "$RUN_DIR/phases.tsv"
}

# ---------------------------------------------------------------------------- the run

run_profile() {
  local profile=$1 run_parent=${2:-} run_name=${3:-}
  resolve_profile "$profile"

  local stamp; stamp=$(date +%Y%m%d-%H%M%S)
  if [ -n "$run_parent" ]; then
    RUN_DIR=$run_parent/$run_name
  else
    RUN_DIR=$SCRIPT_DIR/build/runs/$profile-$stamp
  fi
  # Read back indirectly through RUN_ENV_KEYS when run.env is written.
  # shellcheck disable=SC2034
  SOAK_RUN_DIR=$RUN_DIR

  local jfc=$SCRIPT_DIR/config/sava-soak.jfc jfc_name=sava-soak.jfc
  local logging=$SCRIPT_DIR/config/soak-logging.properties logging_name=soak-logging.properties
  if [ "$profile" = validate ]; then
    jfc=$SCRIPT_DIR/config/sava-soak-validate.jfc; jfc_name=sava-soak-validate.jfc
    logging=$SCRIPT_DIR/config/soak-logging-validate.properties
    logging_name=soak-logging-validate.properties
  fi
  [ -s "$jfc" ] || die "missing recording settings: $jfc"
  [ -s "$logging" ] || die "missing logging settings: $logging"

  local -a filter_entries=("${METHOD_FILTER_ENTRIES[@]}")
  if [ "$profile" = validate ]; then
    filter_entries+=("$VALIDATE_EXTRA_FILTER")
  fi
  if [ "$SOAK_CONTROL" = D11 ]; then
    # D11 suppresses the websocket driver, so the ravina manager is never constructed and
    # WebSocketManagerImpl is never loaded - an entry naming it can never resolve. Without this
    # carve-out the launch gate below kills the client after three seconds and the one control
    # that proves an unexercised property is a FAIL never evaluates anything. The gate itself
    # stays an exact equality; only the requested set shrinks, and run.json records the shrunk
    # count because write_run_json is passed this array.
    local -a d11_entries=() e
    for e in "${filter_entries[@]}"; do
      case "$e" in software.sava.services.solana.websocket.WebSocketManagerImpl::*) continue ;; esac
      d11_entries+=("$e")
    done
    filter_entries=("${d11_entries[@]}")
  fi
  if [ "$SOAK_CONTROL" = filter-typo ]; then
    # The one control the runner injects itself. A method-filter entry that matches nothing is
    # accepted in silence on this JDK, so the only way to know the launch gate works is to feed
    # it a name that cannot resolve and require the gate to say so.
    filter_entries+=("software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket::noSuchMethod")
  fi
  local filter; filter=$(join_filter "${filter_entries[@]}")
  build_peer_command "$RUN_DIR"
  build_client_command "$RUN_DIR" "$filter" "${SOAK_MANAGER_REFLECT}"

  if [ "$DRY_RUN" = 1 ]; then
    echo "# soak.sh dry run: profile $profile"
    echo "# run directory (not created): $RUN_DIR"
    echo "# recording settings: $jfc_name   logging: $logging_name"
    echo "# method-timing entries: ${#filter_entries[@]}"
    echo
    echo "# ---- run.env ----"
    write_run_env /dev/stdout
    if [ "$SOAK_LIVE" = 1 ]; then
      echo "# (live: no peer is started, so no SOAK_WS_PORTS / SOAK_HTTP_PORT line is appended)"
    else
      echo "# (SOAK_WS_PORTS and SOAK_HTTP_PORT are appended once PeerMain prints them)"
    fi
    echo
    echo "# ---- peer launch ----"
    if [ "$SOAK_LIVE" = 1 ]; then echo "# (none: the live profile never starts a peer)"
    else quoted "${PEER_CMD[@]}"; echo; fi
    echo
    echo "# ---- client launch ----"
    quoted "${CLIENT_CMD[@]}"; echo
    return 0
  fi

  mkdir -p "$RUN_DIR"/{config,logs,jfr,views,nmt,heap,findings,issue52}
  : > "$RUN_DIR/.running"
  echo RUNNING > "$RUN_DIR/status"
  cp "$jfc" "$RUN_DIR/config/sava-soak.jfc"
  cp "$logging" "$RUN_DIR/config/soak-logging.properties"
  write_run_env "$RUN_DIR/run.env"
  nmt_header > "$RUN_DIR/nmt.csv"
  : > "$RUN_DIR/rss.csv"
  log "run directory: $RUN_DIR"

  RUN_START=$(date +%s)
  STARTED_AT=$(date '+%Y-%m-%d %H:%M:%S')
  REASONS=""; NOTES=""; INVALIDS=""; INCOMPLETES=""

  WATCHDOG_BOUND=$((SOAK_DURATION_SECONDS + WATCHDOG_SLACK))

  # ---- peer ----
  if [ "$SOAK_LIVE" = 1 ]; then
    log "live profile: no peer is started, and no X-Soak-* header is ever sent"
  else
    log "starting the peer (bound ${PEER_READY_BOUND}s)"
    "${PEER_CMD[@]}" > "$RUN_DIR/logs/peer.log" 2>&1 &
    PEER_PID=$!
    local waited=0 ready=""
    while [ "$waited" -lt $((PEER_READY_BOUND * 2)) ]; do
      if grep -q '^PEER_READY' "$RUN_DIR/logs/peer.log" 2>/dev/null; then ready=1; break; fi
      if ! kill -0 "$PEER_PID" 2>/dev/null; then break; fi
      sleep 0.5; waited=$((waited + 1))
    done
    if [ -z "$ready" ]; then
      if grep -q 'PEER_NONDETERMINISTIC' "$RUN_DIR/logs/peer.log" 2>/dev/null; then
        INVALIDS="$INVALIDS; the peer's determinism self-check failed (PEER_NONDETERMINISTIC)"
      else
        INCOMPLETES="$INCOMPLETES; the peer did not print PEER_READY within ${PEER_READY_BOUND}s"
      fi
      finish_run 0 -1
      return
    fi
    SOAK_WS_PORTS=$(sed -n 's/^WS_PORTS=//p' "$RUN_DIR/logs/peer.log" | head -n 1)
    SOAK_HTTP_PORT=$(sed -n 's/^HTTP_PORT=//p' "$RUN_DIR/logs/peer.log" | head -n 1)
    [ -n "$SOAK_WS_PORTS" ] && [ -n "$SOAK_HTTP_PORT" ] ||
      { INCOMPLETES="$INCOMPLETES; the peer printed PEER_READY without WS_PORTS/HTTP_PORT"; finish_run 0 -1; return; }
    printf 'SOAK_WS_PORTS=%s\nSOAK_HTTP_PORT=%s\n' "$SOAK_WS_PORTS" "$SOAK_HTTP_PORT" >> "$RUN_DIR/run.env"
    log "peer pid $PEER_PID, ws ports $SOAK_WS_PORTS, http port $SOAK_HTTP_PORT"
  fi

  write_run_json "$jfc_name" "${#filter_entries[@]}"

  # ---- client ----
  log "starting the client (subject) JVM"
  "${CLIENT_CMD[@]}" > "$RUN_DIR/logs/client.log" 2>&1 &
  CLIENT_PID=$!
  echo "$CLIENT_PID" > "$RUN_DIR/client.pid"

  # The whole-runner watchdog stops the JVMs and leaves a marker; it never signals this shell,
  # because a killed runner writes no verdict and a run with no verdict is the one outcome an
  # unattended campaign cannot recover from.
  # Its descriptors are detached from this shell's: a caller that pipes soak.sh's output would
  # otherwise wait on the watchdog's sleep after the run ended, because killing the subshell
  # does not kill the sleep it forked. The trap closes that gap from the inside as well.
  ( trap 'kill "$sleeper" 2>/dev/null; exit 0' TERM INT
    sleep "$WATCHDOG_BOUND" & sleeper=$!
    wait "$sleeper" || exit 0
    echo "$WATCHDOG_BOUND" > "$RUN_DIR/.watchdog-fired"
    kill -TERM "$CLIENT_PID" 2>/dev/null || true
    [ -z "$PEER_PID" ] || kill -TERM "$PEER_PID" 2>/dev/null || true ) </dev/null >/dev/null 2>&1 &
  WATCHDOG_PID=$!

  # Launch gate 1: a bad .jfc event setting only WARNS on -XX:StartFlightRecording, so a typo
  # would otherwise give a quiet, wrong recording for eight hours.
  local waited=0
  while [ "$waited" -lt 20 ] && kill -0 "$CLIENT_PID" 2>/dev/null; do
    [ -s "$RUN_DIR/logs/client.log" ] && break
    sleep 0.5; waited=$((waited + 1))
  done
  sleep 2
  head -n 200 "$RUN_DIR/logs/client.log" > "$RUN_DIR/logs/jvm-stdout.log" 2>/dev/null || true
  if grep -q '\[warning\]\[jfr,' "$RUN_DIR/logs/jvm-stdout.log" 2>/dev/null; then
    INVALIDS="$INVALIDS; the JVM warned about the recording options at launch ($(grep -m1 '\[warning\]\[jfr,' "$RUN_DIR/logs/jvm-stdout.log"))"
    finish_run 0 -1
    return
  fi

  # Launch gate 2: a method filter that matches nothing is silent. Both halves are checked -
  # 'New filter installed' proves the tracer initialised at all (a wildcard disables it outright),
  # and one 'Timing entry added for <entry>' line proves each entry resolved. Overloads produce
  # several lines for one entry, so entries are counted, never lines.
  #
  # Both halves are polled to the same bound, because they are not written at the same time: the
  # filter is installed during JFR startup, while an entry's line is written when the class it
  # names is loaded and retransformed - after RunIdentity's two bounded git calls and after the
  # first manager is built. Checking the entries once, the moment the filter appeared, made the
  # gate's margin a fixed 'sleep 2' and turned a slow load into a FAIL whose reason string is the
  # same one the filter-typo control asserts.
  local trace_log=$RUN_DIR/logs/jfr-methodtrace.log entry installed="" missing=""
  waited=0
  FILTERS_REQUESTED=${#filter_entries[@]}
  FILTERS_RESOLVED=0
  while :; do
    installed=""; missing=""; FILTERS_RESOLVED=0
    if grep -q 'New filter installed' "$trace_log" 2>/dev/null; then
      installed=1
      for entry in "${filter_entries[@]}"; do
        if grep -qF "Timing entry added for $entry " "$trace_log" 2>/dev/null; then
          FILTERS_RESOLVED=$((FILTERS_RESOLVED + 1))
        else
          missing="$missing; the method-timing entry '$entry' resolved to nothing"
        fi
      done
    fi
    [ "$FILTERS_RESOLVED" -lt "$FILTERS_REQUESTED" ] || break
    [ "$waited" -lt "$FILTER_CHECK_BOUND" ] || break
    kill -0 "$CLIENT_PID" 2>/dev/null || break
    sleep 1; waited=$((waited + 1))
  done
  if [ -z "$installed" ]; then
    REASONS="$REASONS; no 'New filter installed' line in jfr-methodtrace.log: the method tracer never initialised"
  fi
  REASONS="$REASONS$missing"
  if [ "$FILTERS_RESOLVED" -lt "$FILTERS_REQUESTED" ]; then
    REASONS="$REASONS; method_filters_resolved $FILTERS_RESOLVED < requested $FILTERS_REQUESTED"
    # A client that already exited took its own verdict with it - exit 4 means the run cannot be
    # judged at all and outranks this gate's FAIL - so its status is read rather than discarded.
    # Only a client still running is signalled, because then the 143 it reports says nothing.
    local gate_exit=-1
    if kill -0 "$CLIENT_PID" 2>/dev/null; then
      stop_pid "$CLIENT_PID" TERM "client" "$CLIENT_TERM_BOUND"
      wait "$CLIENT_PID" 2>/dev/null || true
    else
      set +e; wait "$CLIENT_PID"; gate_exit=$?; set -e
    fi
    CLIENT_PID=""
    finish_run 0 "$gate_exit"
    return
  fi
  log "method filters resolved: $FILTERS_RESOLVED of $FILTERS_REQUESTED"

  # ---- READY ----
  waited=0
  local ready=""
  while [ "$waited" -lt $((CLIENT_READY_BOUND * 2)) ]; do
    if grep -q '^READY' "$RUN_DIR/logs/client.log" 2>/dev/null; then ready=1; break; fi
    kill -0 "$CLIENT_PID" 2>/dev/null || break
    sleep 0.5; waited=$((waited + 1))
  done
  if [ -z "$ready" ]; then
    local early=0
    if ! kill -0 "$CLIENT_PID" 2>/dev/null; then set +e; wait "$CLIENT_PID"; early=$?; set -e; CLIENT_PID=""; fi
    # Exit 4 is graded once, by compute_verdict, for every path that can observe it - this one
    # and the launch gate above - so that a client which refused to start is INVALID wherever it
    # was noticed, never FAIL on whichever gate happened to speak first.
    if [ "$early" -ne 4 ]; then
      INCOMPLETES="$INCOMPLETES; the client did not print READY within ${CLIENT_READY_BOUND}s (exit $early)"
    fi
    finish_run 0 "$early"
    return
  fi
  log "client pid $CLIENT_PID READY; heap ${SOAK_HEAP_MB}m, ring $SOAK_JFR_MAXSIZE/$SOAK_JFR_MAXAGE"

  # ---- native-memory baseline, then the monitor loop ----
  local baseline status=0
  baseline=$(run_bounded "$JCMD_BOUND" "jcmd VM.native_memory baseline" \
               "$JCMD" "$CLIENT_PID" VM.native_memory baseline 2>&1) || status=$?
  { echo "=== $(date '+%Y-%m-%d %H:%M:%S') baseline (jcmd exit $status) ==="; printf '%s\n\n' "$baseline"; } \
    > "$RUN_DIR/nmt/nmt.log"
  nmt_diff baseline

  monitor_run

  # ---- stop ----
  if [ -s "$RUN_DIR/.watchdog-fired" ]; then
    INCOMPLETES="$INCOMPLETES; the whole-runner watchdog fired at ${WATCHDOG_BOUND}s (duration + ${WATCHDOG_SLACK}s) and stopped both JVMs"
    rm -f "$RUN_DIR/.watchdog-fired"
  fi
  stop_pid "$WATCHDOG_PID" TERM "watchdog" 5
  WATCHDOG_PID=""
  sample_rss
  if [ -z "${FINAL_NMT_TAKEN:-}" ] && [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
    nmt_diff final
  fi
  local client_exit=-1
  if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
    stop_pid "$CLIENT_PID" TERM "client" "$CLIENT_TERM_BOUND"
  fi
  if [ -n "$CLIENT_PID" ]; then
    set +e; wait "$CLIENT_PID"; client_exit=$?; set -e
    CLIENT_PID=""
  fi
  if [ -n "$PEER_PID" ]; then
    stop_pid "$PEER_PID" TERM "peer" "$PEER_TERM_BOUND"
    set +e; wait "$PEER_PID"; PEER_EXIT=$?; set -e
    PEER_PID=""
  fi
  FINISHED_AT=$(date '+%Y-%m-%d %H:%M:%S')
  # The facts a later `--report` cannot recompute: it re-runs the report and the verdict over
  # the artifacts, and without this file it printed a start time it did not know and a peer
  # exit of -1 as if the peer had failed.
  printf 'STARTED_AT=%s\nFINISHED_AT=%s\nCLIENT_EXIT=%s\nPEER_EXIT=%s\n' \
    "$STARTED_AT" "$FINISHED_AT" "$client_exit" "${PEER_EXIT:--1}" > "$RUN_DIR/run-exit.env"
  finish_run 1 "$client_exit"
}

# The one-second loop: cross-watch both JVMs, sample RSS and native memory on their own
# intervals, snapshot the live views, and take the two phase-boundary dumps. One loop rather
# than a background sampler, because the cross-watch and the dumps must not race each other.
monitor_run() {
  local next_rss=0 next_nmt=$SOAK_NMT_INTERVAL_SECONDS next_view=$VIEW_INTERVAL
  local warmup_taken="" steady_dumped="" final_dumped="" elapsed=0
  FINAL_NMT_TAKEN=""
  local in_run_bound=$((SOAK_DURATION_SECONDS + SOAK_QUIESCE_SECONDS + SOAK_DRAIN_SECONDS + 300))
  JFR_DUMP_EXIT=-1
  while :; do
    if ! kill -0 "$CLIENT_PID" 2>/dev/null; then break; fi
    if [ -n "$PEER_PID" ] && ! kill -0 "$PEER_PID" 2>/dev/null; then
      # A dead peer is never a client FAIL: the oracle went away, so nothing after this is
      # evidence about the subject.
      INCOMPLETES="$INCOMPLETES; the peer (pid $PEER_PID) exited ${elapsed}s into the run"
      PEER_PID=""
      break
    fi
    elapsed=$(( $(date +%s) - RUN_START ))
    if [ "$elapsed" -ge "$in_run_bound" ]; then
      INCOMPLETES="$INCOMPLETES; the run passed its ${in_run_bound}s bound (duration + quiesce + drain + 300s grace) without the client exiting"
      break
    fi
    if [ "$elapsed" -ge "$next_rss" ]; then sample_rss; next_rss=$((elapsed + SOAK_SAMPLE_SECONDS)); fi
    if [ -z "$warmup_taken" ] && [ "$elapsed" -ge "$SOAK_WARMUP_SECONDS" ]; then
      nmt_diff warmup; warmup_taken=1
    fi
    if [ "$elapsed" -ge "$next_nmt" ]; then nmt_diff periodic; next_nmt=$((elapsed + SOAK_NMT_INTERVAL_SECONDS)); fi
    if [ "$elapsed" -ge "$next_view" ]; then take_views "$elapsed"; next_view=$((elapsed + VIEW_INTERVAL)); fi

    if [ -z "$steady_dumped" ] && phase_reached QUIESCE; then
      log "STEADY ended: dumping the recording (no root walk)"
      run_bounded "$JFR_DUMP_BOUND" "jcmd JFR.dump (steady)" "$JCMD" "$CLIENT_PID" JFR.dump \
        name=soak "filename=$RUN_DIR/jfr/soak-steady.jfr" >> "$RUN_DIR/logs/jfr-dump.log" 2>&1 || true
      steady_dumped=1
    fi
    if [ -z "$final_dumped" ] && phase_reached DRAIN; then
      # The final native-memory reading is taken here, after quiescence and while the client is
      # still alive: a client that completes its phases exits on its own, and a jcmd attach after
      # that fails, which is what an earlier 'final' sample placed after the monitor loop did.
      nmt_diff final; FINAL_NMT_TAKEN=1
      # Two collections five seconds apart settle the after-GC floor, then the root walk: the
      # only way to get referrer chains, because jdk.OldObjectSample exists at dump time alone.
      log "QUIESCE ended: two GC.run 5 s apart, then the root-walk dump (bound ${JFR_DUMP_BOUND}s)"
      run_bounded "$JCMD_BOUND" "jcmd GC.run" "$JCMD" "$CLIENT_PID" GC.run >> "$RUN_DIR/logs/jfr-dump.log" 2>&1 || true
      sleep 5
      run_bounded "$JCMD_BOUND" "jcmd GC.run" "$JCMD" "$CLIENT_PID" GC.run >> "$RUN_DIR/logs/jfr-dump.log" 2>&1 || true
      set +e
      run_bounded "$JFR_DUMP_BOUND" "jcmd JFR.dump (final)" "$JCMD" "$CLIENT_PID" JFR.dump \
        name=soak "filename=$RUN_DIR/jfr/soak-final.jfr" path-to-gc-roots=true \
        >> "$RUN_DIR/logs/jfr-dump.log" 2>&1
      JFR_DUMP_EXIT=$?
      set -e
      final_dumped=1
    fi
    sleep 1
  done
}

# The subject as the tree holds it now: the commit time of the last change under
# sava-rpc/src/main and the newest file time under it, which is what a commit time cannot see
# (an uncommitted edit). Recorded into run.json at launch, so a report judges the subject the
# run SAW rather than the tree at report time.
subject_source_stamps() {
  local ct mt
  ct=$(git -C "$ROOT" log -1 --format=%ct -- sava-rpc/src/main 2>/dev/null || echo 0)
  mt=$(find "$ROOT/sava-rpc/src/main" -type f -exec stat -f %m {} + 2>/dev/null | sort -n | tail -n 1)
  [ -n "$mt" ] || mt=$(find "$ROOT/sava-rpc/src/main" -type f -exec stat -c %Y {} + 2>/dev/null | sort -n | tail -n 1)
  printf '%s %s\n' "${ct:-0}" "${mt:-0}"
}

# A numeric launch-time stamp from the run's run.json, or empty for a run from before it was
# recorded.
subject_stamp() {
  sed -n "s/^ *\"$1\": *\([0-9][0-9]*\).*/\1/p" "$RUN_DIR/run.json" 2>/dev/null | head -n 1
}

write_run_json() {
  local jfc_name=$1 filters=$2 rev dirty schedule_sha jfc_sha subject_ct subject_mtime
  rev=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)
  if [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]; then dirty=true; else dirty=false; fi
  read -r subject_ct subject_mtime <<< "$(subject_source_stamps)"
  jfc_sha=$(shasum -a 256 "$RUN_DIR/config/sava-soak.jfc" | cut -d' ' -f1)
  if [ -s "$RUN_DIR/fault-schedule.tsv" ]; then
    schedule_sha=$(shasum -a 256 "$RUN_DIR/fault-schedule.tsv" | cut -d' ' -f1)
  else
    schedule_sha=""
  fi
  # Written before the client starts; Main merges its own identity keys (subject code source,
  # timings actually used) into the same file.
  cat > "$RUN_DIR/run.json" <<JSON
{
  "profile": "$SOAK_PROFILE",
  "gitRev": "$rev",
  "gitDirty": $dirty,
  "subjectCommitTime": ${subject_ct:-0},
  "subjectNewestMtime": ${subject_mtime:-0},
  "java": "$JAVA",
  "javaVersion": "$("$JAVA" -version 2>&1 | head -n 1 | tr -d '"')",
  "gc": "G1",
  "jfcSource": "$jfc_name",
  "jfcSha256": "$jfc_sha",
  "faultScheduleSha256": "$schedule_sha",
  "methodFiltersRequested": $filters,
  "modulePath": "$MODULE_PATH",
  "controlsRunId": "$(controls_run_id)",
  "startedAt": "$STARTED_AT"
}
JSON
}

# The names are ours and a stamp begins with its year, so restricting the glob to the stamp form
# makes lexical order chronological order. The restriction is the point: a single run under
# 'controls-<anything else>' would sort after every stamp and capture the gate for good, which is
# what one manual --control row did - it has its own parent (manual-control) for the same reason.
newest_controls_dir() {
  find "$SCRIPT_DIR/build/runs" -maxdepth 1 -type d -name 'controls-[0-9]*' 2>/dev/null | sort | tail -n 1
}

controls_run_id() {
  local newest
  newest=$(newest_controls_dir)
  [ -n "$newest" ] && [ -s "$newest/controls.md" ] && basename "$newest" || echo ""
}

# ---------------------------------------------------------------------------- verdict

metric() { sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n 1 | tr -d '\r' || true; }
metric_or() { local v; v=$(metric "$1" "$2"); printf '%s\n' "${v:-$3}"; }
is_number() { case "$1" in ''|*[!0-9-]*) return 1 ;; *) return 0 ;; esac; }
# A metric may be a rate (0.973) as well as a count; 'n/a' and every other word is not a number.
is_decimal() { case "$1" in ''|.|-|*[!0-9.-]*) return 1 ;; *) return 0 ;; esac; }
# an exit code is a bare non-negative integer; a --report without run-exit.env carries '-'
is_exit_code() { case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
gt() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a + 0 > b + 0) }'; }

# slope_fit <csv> <time-col> <value-col> <skip-seconds> <limit> <floor>: least squares over the
# samples more than the warm-up window past the first, printed as 'n fit_n first last slope
# window applied'. The window dropped is max(<skip-seconds>, 60, span/10) - the same rule the
# report's Slopes applies - because the JIT and class loading keep the code cache and metaspace
# growing for the first minute or more whatever the profile says its warm-up is, and a fit that
# includes that ramp reports it as a slope. Fewer than three fittable samples is 'not measured',
# never a pass.
slope_fit() {
  awk -F, -v tc="$2" -v vc="$3" -v skip="$4" -v limit="$5" -v floor="$6" '
    /^[0-9]/ { n++; x[n] = $tc + 0; y[n] = $vc + 0 }
    END {
      if (n == 0) { print "0 0 0 0 n/a 0 n/a"; exit }
      span = x[n] - x[1]
      if (skip < 60) skip = 60
      if (skip < span / 10) skip = span / 10
      first = 1
      while (first <= n && x[first] < x[1] + skip) first++
      m = n - first + 1
      if (m < 3) { printf "%d %d %d %d n/a %d n/a\n", n, (m > 0 ? m : 0), y[1], y[n], (m > 0 ? x[n] - x[first] : 0); exit }
      x0 = x[first]; sx = 0; sy = 0; sxx = 0; sxy = 0
      for (i = first; i <= n; i++) { dx = x[i] - x0; sx += dx; sy += y[i]; sxx += dx * dx; sxy += dx * y[i] }
      den = m * sxx - sx * sx; window = x[n] - x0
      if (den == 0) { printf "%d %d %d %d n/a %d n/a\n", n, m, y[1], y[n], window; exit }
      slope = (m * sxy - sx * sy) / den * 3600
      hours = window / 3600; applied = limit
      if (hours > 0 && floor / hours > applied) applied = floor / hours
      printf "%d %d %d %d %.1f %d %.0f\n", n, m, y[1], y[n], slope, window, applied
    }' "$1"
}

# nmt_slope <skip-seconds> <limit> <floor>: the gated native-memory series is nmt.csv's last
# column and its own label column decides the window - the baseline row is a differently-taken
# measurement and is excluded, and the warm-up window is dropped from what remains. The window
# and the minimum of three fittable samples are slope_fit's, deliberately: fitting from the
# first diff row fitted the JIT's code-cache ramp and reported it as retention, which on a short
# control run was most of the run and failed every fault control that must PASS.
nmt_slope() {
  awk -F, -v skip="$1" -v limit="$2" -v floor="$3" '
    NR > 1 && $2 != "baseline" { n++; x[n] = $1 + 0; y[n] = $NF + 0 }
    NR > 1 && $2 == "baseline" && bg == "" { bg = $NF }
    NR > 1 && $2 == "final" { fg = $NF }
    END {
      base = (bg == "" ? "n/a" : bg); fin = (fg == "" ? "n/a" : fg)
      if (n == 0) { printf "0 n/a 0 n/a %s %s\n", base, fin; exit }
      span = x[n] - x[1]
      if (skip < 60) skip = 60
      if (skip < span / 10) skip = span / 10
      first = 1
      while (first <= n && x[first] < x[1] + skip) first++
      m = n - first + 1
      if (m < 3) { printf "%d n/a %d n/a %s %s\n", (m > 0 ? m : 0), (m > 0 ? x[n] - x[first] : 0), base, fin; exit }
      x0 = x[first]; sx = 0; sy = 0; sxx = 0; sxy = 0
      for (i = first; i <= n; i++) { dx = x[i] - x0; sx += dx; sy += y[i]; sxx += dx * dx; sxy += dx * y[i] }
      den = m * sxx - sx * sx; window = x[n] - x0
      if (den == 0 || window <= 0) { printf "%d n/a %d n/a %s %s\n", m, window, base, fin; exit }
      slope = (m * sxy - sx * sy) / den * 3600
      hours = window / 3600; applied = limit
      if (floor / hours > applied) applied = floor / hours
      printf "%d %.1f %d %.0f %s %s\n", m, slope, window, applied, base, fin
    }' "$RUN_DIR/nmt.csv"
}

# client.csv's baseline is the first gauge of STEADY, not the first of the run: warm-up commits
# the heap and starts the pools, and a baseline taken there would hide exactly that growth.
#
# The endpoint the thread and descriptor margins are judged against is the PEAK of the window that
# runs from that baseline row to the last row before SHUTDOWN, not the last row of the file.
# SHUTDOWN is excluded because Phases notifies every workload of the phase and then samples once
# more before the close loop: a driver that releases its threads and sockets on that notification
# would hand the margins back exactly the growth they exist to catch - D9's 256 unclosed sockets
# are closed by that very teardown. The peak is also strictly the harder test than any single
# endpoint, because a run that grew and then shed inside STEADY still has to answer for the growth.
# thr_last/fd_last are reported beside the peak so the pair says whether it came back down.
#
# Columns are read by the header's own names, never by position: GaugeSampler owns that header and
# a column inserted in the middle of it would otherwise silently re-point every field after it at
# its neighbour, which reads as a gate that still works. A name the header does not carry comes
# back empty, so a run written by an older harness is reported as unmeasured rather than as zero.
client_csv_stats() {
  awk -F, '
    function cell(name,   i) { i = col[name]; return (i == 0 ? "" : $i) }
    NR == 1 { for (i = 1; i <= NF; i++) col[$i] = i; next }
    {
      n++
      phase = cell("phase"); thr = cell("threads"); fd = cell("fds")
      if (phase == "SHUTDOWN") shutdown = 1
      if (steady == 0 && phase == "STEADY") { steady = 1; thr_base = thr; fd_base = fd; thr_peak = thr; fd_peak = fd }
      if (steady == 1 && shutdown == 0) {
        if (thr + 0 > thr_peak + 0) thr_peak = thr
        if (fd + 0 > fd_peak + 0) fd_peak = fd
      }
      if (phase == "QUIESCE") { q_regs = cell("retained_regs"); q_subs = cell("live_subs") }
      if (phase == "DRAIN") {
        d_regs = cell("retained_regs"); d_pending = cell("pending_confirm")
        d_inflight = cell("inflight_rpc"); d_overdue = cell("overdue_rpc")
      }
      thr_last = thr; fd_last = fd; vt_s = cell("vt_started"); vt_e = cell("vt_ended")
      delivered = cell("delivered"); rpc = cell("rpc_completed"); mismatch = cell("mismatches")
      pending = cell("pending_confirm"); inflight = cell("inflight_rpc")
    }
    END {
      if (n == 0) { print "0 -1 -1 -1 -1 -1 -1 -1 -1 n/a n/a n/a n/a n/a n/a 0 0 0 -1 -1"; exit }
      printf "%d %d %d %d %d %d %d %d %d %s %s %s %s %s %s %d %d %d %d %d\n",
        n, (steady ? thr_base : -1), thr_last, (steady ? thr_peak : -1),
        (steady ? fd_base : -1), fd_last, (steady ? fd_peak : -1), vt_s, vt_e,
        (q_regs == "" ? "n/a" : q_regs), (q_subs == "" ? "n/a" : q_subs),
        (d_regs == "" ? "n/a" : d_regs), (d_pending == "" ? "n/a" : d_pending),
        (d_inflight == "" ? "n/a" : d_inflight), (d_overdue == "" ? "n/a" : d_overdue),
        delivered + 0, rpc + 0, mismatch + 0, pending + 0, inflight + 0
    }' "$RUN_DIR/client.csv"
}

# The correctness ledger. Grades A, B and C are promises - a public contract, a documented
# invariant, or something the controlled peer establishes - so a failure or an unexplained
# non-evaluation is a FAIL. Grade D is measurement and never fails a run; NONE rows are the
# named non-properties and are informational.
property_verdicts() {
  [ -s "$RUN_DIR/properties.tsv" ] || return 0
  awk -F'\t' '
    NR == 1 && $1 == "id" { next }
    NF >= 6 {
      grade = $2; evaluated = $3 + 0; failed = $5 + 0; skip = $6
      if (grade ~ /^(A|B|C)_/) {
        if (failed > 0) printf "property %s (%s) failed %d of %d evaluation(s)\n", $1, grade, failed, evaluated
        else if (evaluated == 0 && skip == "") printf "property %s (%s) UNEXERCISED with no stated skip\n", $1, grade
      }
    }' "$RUN_DIR/properties.tsv"
}

finish_run() {
  local ran=$1 client_exit=$2
  : "${FINISHED_AT:=$(date '+%Y-%m-%d %H:%M:%S')}"
  : "${FILTERS_REQUESTED:=0}"; : "${FILTERS_RESOLVED:=0}"; : "${JFR_DUMP_EXIT:=-1}"
  : "${PEER_EXIT:=-1}"

  # ---- the recording ----
  JFR_FILE=""; JFR_SOURCE=""
  if [ -s "$RUN_DIR/jfr/soak-final.jfr" ]; then
    JFR_FILE=$RUN_DIR/jfr/soak-final.jfr; JFR_SOURCE="jcmd JFR.dump with path-to-gc-roots=true"
  elif [ -s "$RUN_DIR/jfr/soak-exit.jfr" ]; then
    JFR_FILE=$RUN_DIR/jfr/soak-exit.jfr; JFR_SOURCE="the JVM's dumponexit; no paths to GC roots"
  elif [ -s "$RUN_DIR/jfr/soak-steady.jfr" ]; then
    JFR_FILE=$RUN_DIR/jfr/soak-steady.jfr; JFR_SOURCE="the STEADY-end dump; the run did not reach its final dump"
  fi
  JFR_SUMMARY_EXIT=-1; REPORT_EXIT=-1
  if [ "$ran" = 1 ] && [ -n "$JFR_FILE" ]; then
    set +e
    run_bounded "$JFR_SUMMARY_BOUND" "jfr summary" "$JFR" summary "$JFR_FILE" > "$RUN_DIR/jfr-summary.txt" 2>&1
    JFR_SUMMARY_EXIT=$?
    set -e
    : > "$RUN_DIR/jfr-views.txt"
    local view
    for view in "${FINAL_VIEWS[@]}"; do
      { echo "=== jfr view $view ==="
        run_bounded "$JFR_SUMMARY_BOUND" "jfr view $view" "$JFR" view "$view" "$JFR_FILE" 2>&1 || true
        echo; } >> "$RUN_DIR/jfr-views.txt"
    done
  fi
  if [ "$ran" = 1 ]; then
    set +e
    run_bounded "$REPORT_BOUND" "SoakReport" "$JAVA" -p "$MODULE_PATH" -m "$REPORT_MAIN" \
      "$RUN_DIR" "${JFR_FILE:--}" > "$RUN_DIR/logs/soak-report.log" 2>&1
    REPORT_EXIT=$?
    set -e
    log "SoakReport exit $REPORT_EXIT"
  fi

  compute_verdict "$ran" "$client_exit"

  # ---- summary.md ----
  write_summary "$ran" "$client_exit" > "$RUN_DIR/summary.md"
  echo "$VERDICT" > "$RUN_DIR/status"
  rm -f "$RUN_DIR/.running"
  log "verdict: $VERDICT  ($RUN_DIR/summary.md)"
  RUN_EXIT=$EXIT_CODE
}

compute_verdict() {
  local ran=$1 client_exit=$2
  local metrics=$RUN_DIR/jfr-metrics.properties counters=$RUN_DIR/counters.properties

  # ---- INVALID: the run cannot be judged at all ----
  # The client's own exit code is the one INVALID test that does not need the run to have
  # produced metrics: a client that exited 4 refused to start, and it can exit 4 under a launch
  # gate that never reached READY. Judging it only when ran=1 graded those runs FAIL on the
  # gate's own reason and lost both the taxonomy and the machine-readable status.
  # a --report over a run directory with no run-exit.env has no client exit to test
  if is_exit_code "$client_exit" && [ "$client_exit" -eq 4 ]; then
    INVALIDS="$INVALIDS; the client exited 4 (subject mismatch, schedule digest, redaction or detector self-test, or an unknown config key); see logs/client.log"
  fi
  if [ "$ran" = 1 ]; then
    local dropped peer_stats_unavailable http_attempted skipped_cap skipped_pct
    dropped=$(metric_or "$counters" harness.peerLog.dropped 0)
    peer_stats_unavailable=$(metric_or "$counters" harness.peerStats.unavailable 0)
    # The in-flight cap is refused by the HTTP driver alone, so its denominator is the HTTP
    # driver's own attempt count. harness.opsAttempted counts websocket plan steps too, and
    # dividing an HTTP-only numerator by that mixed population shrank the ratio by however much
    # websocket traffic the profile happened to carry - a starved HTTP driver read as healthy
    # because the websocket plan was busy. harness.opsAttempted stays the progress line's number.
    http_attempted=$(metric_or "$counters" http.ops.attempted "")
    skipped_cap=$(metric_or "$counters" harness.opsSkipped.inflightCap 0)
    if is_number "$dropped" && [ "$dropped" -gt 0 ]; then
      INVALIDS="$INVALIDS; the peer dropped $dropped log line(s): its record of what it did is incomplete"
    fi
    if is_number "$peer_stats_unavailable" && [ "$peer_stats_unavailable" -gt 0 ]; then
      INVALIDS="$INVALIDS; the peer's /__soak/stats was unreadable at shutdown: its logDropped is unknown, so the record cannot be certified complete"
    fi
    if ! is_number "$http_attempted"; then
      NOTES="$NOTES; the starvation ratio was not judged: counters.properties carries no http.ops.attempted, and $skipped_cap HTTP operation(s) were skipped at the in-flight cap"
    elif [ "$http_attempted" -gt 0 ]; then
      skipped_pct=$(awk -v s="$skipped_cap" -v a="$http_attempted" 'BEGIN { printf "%.2f", 100 * s / a }')
      if gt "$skipped_pct" "$SOAK_SKIP_LIMIT_PCT"; then
        INVALIDS="$INVALIDS; the harness skipped ${skipped_pct}% of its own HTTP operations (limit ${SOAK_SKIP_LIMIT_PCT}%): it was starved, so the subject was not driven"
      fi
    else
      NOTES="$NOTES; the starvation ratio was not judged: the HTTP driver attempted no operation (http.ops.attempted 0)"
    fi
    # harness.opsSkipped.deadline is reserved: no driver increments it, and it is no longer part
    # of any gate. A non-zero value therefore means a driver started using it without a
    # denominator being agreed, which is worth saying out loud rather than silently ignoring.
    local skipped_deadline; skipped_deadline=$(metric_or "$counters" harness.opsSkipped.deadline 0)
    is_number "$skipped_deadline" && [ "$skipped_deadline" -eq 0 ] ||
      NOTES="$NOTES; harness.opsSkipped.deadline is $skipped_deadline: the reserved counter moved, and no gate reads it"
    # Only a campaign is invalidated by stale controls. A pilot is exploratory; an eight-hour
    # campaign is evidence, and evidence produced by an oracle nobody re-checked after the
    # subject changed is not evidence.
    if [ "$SOAK_PROFILE" = campaign ]; then
      local controls_stale; controls_stale=$(stale_controls)
      [ -z "$controls_stale" ] || INVALIDS="$INVALIDS; $controls_stale"
      local controls_note; controls_note=$(stale_controls_note)
      [ -z "$controls_note" ] || NOTES="$NOTES; $controls_note"
    fi
  fi

  # ---- INCOMPLETE ----
  if [ "$ran" = 1 ]; then
    if is_exit_code "$client_exit" && [ "$client_exit" -eq 3 ]; then
      INCOMPLETES="$INCOMPLETES; the client exited 3: a bounded stage was breached"
    fi
  fi

  # ---- FAIL ----
  if [ "$ran" = 1 ]; then
    local line
    while IFS= read -r line; do
      [ -z "$line" ] || REASONS="$REASONS; $line"
    done < <(property_verdicts)

    local mismatch submit_failed errors_thrown unexplained
    mismatch=$(metric_or "$counters" rpc.completed.mismatch 0)
    submit_failed=$(metric_or "$counters" jfr.submitFailed 0)
    errors_thrown=$(metric_or "$counters" jfr.errorsThrown 0)
    unexplained=$(metric_or "$counters" ws.retirement.unexplained 0)
    is_number "$mismatch" && [ "$mismatch" -eq 0 ] || REASONS="$REASONS; $mismatch request/response mismatch(es)"
    is_number "$submit_failed" && [ "$submit_failed" -eq 0 ] || REASONS="$REASONS; $submit_failed jdk.VirtualThreadSubmitFailed event(s) over the run"
    is_number "$errors_thrown" && [ "$errors_thrown" -eq 0 ] || REASONS="$REASONS; $errors_thrown jdk.JavaErrorThrow event(s) over the run"
    is_number "$unexplained" && [ "$unexplained" -eq 0 ] || REASONS="$REASONS; $unexplained connection retirement(s) with no fault to explain them"
    # W3-B's bound is the client's own promise: a default route bounds the whole exchange at
    # 2 x requestTimeout through withResponseDeadline. An overshoot is the deadline not being
    # honoured, so the count belongs in the verdict rather than only in the report's latency
    # section, where a run could print it and still call itself green.
    DEADLINE_MISSED=$(metric_or "$counters" rpc.deadline.missed 0)
    is_number "$DEADLINE_MISSED" && [ "$DEADLINE_MISSED" -eq 0 ] ||
      REASONS="$REASONS; $DEADLINE_MISSED default-route exchange(s) settled past the exchange deadline"

    # Floors: a run that drove nothing proves nothing.
    local delivered rpc_ok churn_e churn_c
    delivered=$(metric_or "$counters" ws.notifications.delivered 0)
    rpc_ok=$(metric_or "$counters" rpc.completed.ok 0)
    churn_e=$(metric_or "$counters" churn.engine.cycles 0)
    churn_c=$(metric_or "$counters" churn.client.cycles 0)
    is_number "$delivered" && [ "$delivered" -gt 0 ] || REASONS="$REASONS; the websocket workload delivered no notification"
    is_number "$rpc_ok" && [ "$rpc_ok" -gt 0 ] || REASONS="$REASONS; the HTTP workload completed no request"
    is_number "$churn_e" && [ "$churn_e" -gt 0 ] || REASONS="$REASONS; the churn workload ran no engine cycle"
    is_number "$churn_c" && [ "$churn_c" -gt 0 ] || REASONS="$REASONS; the churn workload ran no client cycle"

    local oom hprof escaped
    oom=$(grep -c 'OutOfMemoryError' "$RUN_DIR/logs/client.log" 2>/dev/null || true)
    hprof=$(find "$RUN_DIR/heap" -name '*.hprof' 2>/dev/null | wc -l | tr -d ' ')
    escaped=$(grep -c 'Exception in thread' "$RUN_DIR/logs/client.log" 2>/dev/null || true)
    [ "${oom:-0}" -eq 0 ] || REASONS="$REASONS; ${oom} 'OutOfMemoryError' line(s) in client.log"
    [ "${hprof:-0}" -eq 0 ] || REASONS="$REASONS; ${hprof} heap dump(s) under heap/"
    [ "${escaped:-0}" -eq 0 ] || REASONS="$REASONS; ${escaped} 'Exception in thread' line(s) in client.log"

    # Faults: a schedule that was written and not applied means the run tested less than it says.
    FAULT_FIDELITY=$(metric_or "$metrics" fault_fidelity n/a)
    FAULT_NO_TARGET=$(metric_or "$metrics" fault_no_target 0)
    FAULT_UNAPPLIED=$(metric_or "$metrics" fault_kinds_unapplied "")
    RECOVERY_OVER=$(metric_or "$metrics" recovery_over_budget 0)
    if [ "$FAULT_FIDELITY" = n/a ]; then
      NOTES="$NOTES; fault fidelity not reported by SoakReport"
    elif awk -v f="$FAULT_FIDELITY" 'BEGIN { exit !(f + 0 < 0.90) }'; then
      REASONS="$REASONS; fault fidelity $FAULT_FIDELITY is below 0.90 ($FAULT_NO_TARGET scheduled fault(s) found no target)"
    fi
    if [ -n "$FAULT_UNAPPLIED" ]; then
      # One bracketed token per kind. A bare name is a substring of every line that merely PLANS
      # that kind, and the comma-joined order is the order the kinds first appear in the run's own
      # files, which is not stable between runs - so a control row that must name the kind it
      # suppressed (D12) can only match a delimited token. A value the report already bracketed is
      # left alone rather than bracketed twice.
      FAULT_UNAPPLIED_TOKENS=$(printf '%s' "$FAULT_UNAPPLIED" | awk -F, '{
        out = ""
        for (i = 1; i <= NF; i++) {
          t = $i
          if (t == "") continue
          if (t !~ /^\[.*\]$/) t = "[" t "]"
          out = out (out == "" ? "" : ",") t
        }
        print out
      }')
      REASONS="$REASONS; fault kind(s) scheduled and never applied: $FAULT_UNAPPLIED_TOKENS"
    fi
    is_number "$RECOVERY_OVER" && [ "$RECOVERY_OVER" -eq 0 ] || REASONS="$REASONS; $RECOVERY_OVER recovery record(s) over budget"

    # Resources.
    # shellcheck disable=SC2034  # the trailing fields are read positionally and reported below
    read -r CSV_N THR_BASE THR_LAST THR_PEAK FD_BASE FD_LAST FD_PEAK VT_STARTED VT_ENDED \
            Q_REGS Q_SUBS D_REGS D_PENDING D_INFLIGHT D_OVERDUE DELIVERED RPC_DONE MISMATCHES \
            PENDING_LAST INFLIGHT_LAST \
      <<< "$(client_csv_stats)"
    if [ "$CSV_N" -ge 2 ] && [ "$THR_BASE" -ge 0 ]; then
      [ "$THR_PEAK" -le $((THR_BASE + SOAK_THREAD_MARGIN)) ] ||
        REASONS="$REASONS; live threads grew from $THR_BASE at the start of STEADY to a peak of $THR_PEAK before SHUTDOWN (last row $THR_LAST, margin $SOAK_THREAD_MARGIN)"
      # A descriptor count of -1 is 'UnixOperatingSystemMXBean was unavailable', which every row
      # of a run carries or none does; judging the margin against it compared -1 with -1 and
      # passed without measuring anything, so the absence is stated instead.
      if [ "$FD_BASE" -ge 0 ]; then
        [ "$FD_PEAK" -le $((FD_BASE + SOAK_FD_MARGIN)) ] ||
          REASONS="$REASONS; open file descriptors grew from $FD_BASE at the start of STEADY to a peak of $FD_PEAK before SHUTDOWN (last row $FD_LAST, margin $SOAK_FD_MARGIN)"
      else
        NOTES="$NOTES; descriptors not judged: client.csv reports -1, so this JVM exposes no open-descriptor count"
      fi
    else
      NOTES="$NOTES; threads and descriptors not judged ($CSV_N client.csv row(s), no STEADY baseline)"
    fi
    if [ "$VT_STARTED" -ge 0 ] && [ "$VT_ENDED" -ge 0 ]; then
      local vt_live=$((VT_STARTED - VT_ENDED))
      [ "$vt_live" -le "$SOAK_VTHREAD_MARGIN" ] ||
        REASONS="$REASONS; $vt_live virtual thread(s) started and never ended after the drain (margin $SOAK_VTHREAD_MARGIN)"
    fi
    if [ "$D_REGS" != n/a ] && [ "$D_REGS" -ge 0 ]; then
      [ "$D_REGS" -eq 0 ] || REASONS="$REASONS; $D_REGS registration(s) retained by the engine at the end of DRAIN, expected 0"
    elif [ "$D_REGS" = "-1" ]; then
      NOTES="$NOTES; the engine's retained-registration probes were unavailable (no --add-opens), so the residual check is a stated skip"
    fi
    if [ "$Q_REGS" != n/a ] && [ "$Q_SUBS" != n/a ] && [ "$Q_REGS" -ge 0 ]; then
      local drift=$((Q_REGS - Q_SUBS)); [ "$drift" -ge 0 ] || drift=$((-drift))
      [ "$drift" -le 4 ] ||
        REASONS="$REASONS; the engine retained $Q_REGS registration(s) at the end of QUIESCE against $Q_SUBS live subscription(s) (tolerance 4)"
    fi
    # What the drain owes is that nothing is still running past its OWN bound, not that the last
    # gauge found the client idle: a getProgramAccounts launched near the end of STEADY is inside
    # the exchange deadline the client promises it, and a route the harness bounds itself is inside
    # that bound, so counting either as a leak fails a run for honouring its own contract.
    # overdue_rpc is that count - in flight past the bound the caller gave it - and it is the one
    # the verdict reads; inflight_rpc is reported beside it and judges nothing.
    if is_number "$D_PENDING" && is_number "$D_INFLIGHT"; then
      [ "$D_PENDING" -eq 0 ] || REASONS="$REASONS; $D_PENDING subscription(s) still awaiting confirmation at the end of DRAIN"
      if [ "$D_OVERDUE" = n/a ]; then
        NOTES="$NOTES; the overdue-request gate is a stated skip: client.csv carries no overdue_rpc column, so nothing says which in-flight requests were past their own bound"
      elif is_number "$D_OVERDUE"; then
        [ "$D_OVERDUE" -eq 0 ] || REASONS="$REASONS; $D_OVERDUE request(s) still in flight at the end of DRAIN"
      fi
      [ "$D_INFLIGHT" -eq 0 ] ||
        NOTES="$NOTES; $D_INFLIGHT request(s) still inside their own deadline at DRAIN end"
    fi

    # shellcheck disable=SC2034  # RSS_N is read positionally
    read -r RSS_N RSS_FIT_N RSS_FIRST RSS_LAST RSS_SLOPE RSS_WINDOW RSS_LIMIT \
      <<< "$(slope_fit "$RUN_DIR/rss.csv" 1 2 "$SOAK_WARMUP_SECONDS" "$SOAK_RSS_SLOPE_KIB_PER_HOUR" "$SOAK_RSS_NOISE_FLOOR_KIB")"
    # Resident size is not monotone under memory pressure: macOS compresses idle pages (a
    # pre-touched heap's zero pages first) and a JVM that then uses them reads as growth. A window
    # whose resident size fell below its first post-warm-up sample by more than the noise floor
    # carries that reclaim, so its slope is reported and not judged; the gated native-memory
    # slope and the after-GC heap floor are the retention gates that do not see the OS.
    # The reference is the run's FIRST sample, taken right after the heap was pre-touched: that is
    # the largest resident size a JVM retaining nothing will show, so any later sample more than
    # the floor below it is the OS having compressed pages (measured: 678 -> 411 MiB inside the
    # warm-up, then a climb back to 636 that fitted as 2.5 GiB/h), and a window that never rises
    # more than the floor above that first sample has grown nothing the OS did not first take.
    RSS_RECLAIM=$(awk -F, -v skip="$SOAK_WARMUP_SECONDS" -v floor="$SOAK_RSS_NOISE_FLOOR_KIB" '
      /^[0-9]/ { n++; if (n == 1) { t0 = $1; first = $2 } if ($1 - t0 < skip) next; if (min == "" || $2 < min) min = $2; last = $2 }
      END { if (first == "" || min == "") { print 0 } else if (first - min > floor) { printf "%d", first - min } else { print 0 } }' "$RUN_DIR/rss.csv")
    if [ "$RSS_SLOPE" = n/a ]; then
      NOTES="$NOTES; the RSS slope was not measured ($RSS_FIT_N sample(s) after the ${SOAK_WARMUP_SECONDS}s warm-up, three are needed)"
    elif [ "${RSS_RECLAIM:-0}" -gt 0 ]; then
      NOTES="$NOTES; the RSS slope ($RSS_SLOPE KiB/h) is not judged: resident size fell $RSS_RECLAIM KiB below the run's first (pre-touched) sample, which is the OS compressing pages rather than the JVM releasing them, and a climb back from there is not growth"
    elif gt "$RSS_SLOPE" "$RSS_LIMIT"; then
      REASONS="$REASONS; RSS slope $RSS_SLOPE KiB/h exceeds $RSS_LIMIT KiB/h over the ${RSS_WINDOW}s after warm-up"
    fi
    read -r NMT_FIT_N NMT_SLOPE NMT_WINDOW NMT_LIMIT NMT_BASE_G NMT_FINAL_G \
      <<< "$(nmt_slope "$SOAK_WARMUP_SECONDS" "$SOAK_RSS_SLOPE_KIB_PER_HOUR" "$SOAK_RSS_NOISE_FLOOR_KIB")"
    if [ "$NMT_SLOPE" = n/a ]; then
      NOTES="$NOTES; the gated native-memory slope was not measured ($NMT_FIT_N summary.diff row(s) after the warm-up window, three are needed: SOAK_NMT_INTERVAL_SECONDS is ${SOAK_NMT_INTERVAL_SECONDS}s against a ${SOAK_DURATION_SECONDS}s run)"
    elif gt "$NMT_SLOPE" "$NMT_LIMIT"; then
      REASONS="$REASONS; gated native memory (committed less Arena Chunk and Tracing) grew $NMT_SLOPE KiB/h, over $NMT_LIMIT KiB/h, across ${NMT_WINDOW}s"
    fi
    HEAP_SLOPE=$(metric_or "$metrics" heap_after_gc_slope_kib_per_hour n/a)
    # The heap floor is read after FULL collections only, and the harness forces one at each end
    # of STEADY; the gate is the difference between those two readings against the floor's own
    # noise allowance, not a slope over the run. A fitted slope over G1's young and mixed pauses
    # read the old generation's fill-up as retention (measured: +29 MiB/h on a pilot whose full
    # collection left 23.6 MiB), so the slope is reported and never judged.
    # first: the first floor reading inside STEADY (the boundary collection); last: the last
    # reading before SHUTDOWN, which the STEADY-end collection wrote (the gauge carries it into
    # QUIESCE/DRAIN rows).
    read -r HEAP_FIRST HEAP_LAST <<< "$(awk -F, '
      NR == 1 { for (i = 1; i <= NF; i++) { if ($i == "heap_after_gc") h = i; if ($i == "phase") ph = i } next }
      { v = $h + 0; p = $ph
        if (p == "SHUTDOWN") next
        if (v > 0 && first == "" && p != "STARTUP" && p != "WARMUP") first = v
        if (v > 0 && p != "STARTUP" && p != "WARMUP") last = v }
      END { printf "%s %s", (first == "" ? "n/a" : int(first / 1024)), (last == "" ? "n/a" : int(last / 1024)) }' "$RUN_DIR/client.csv")"
    HEAP_FLOOR_SAMPLES=$(metric_or "$counters" jfr.gc.floorSamples 0)
    if [ "$HEAP_FIRST" = n/a ] || [ "$HEAP_LAST" = n/a ] || [ "${HEAP_FLOOR_SAMPLES:-0}" -lt 2 ]; then
      NOTES="$NOTES; the after-GC heap floor is a stated skip: fewer than two full-collection readings inside STEADY (jfr.gc.floorSamples=$HEAP_FLOOR_SAMPLES), so there is no before and after to compare"
    else
      HEAP_DELTA=$((HEAP_LAST - HEAP_FIRST))
      if [ "$HEAP_DELTA" -gt "$SOAK_HEAP_FLOOR_NOISE_KIB" ]; then
        REASONS="$REASONS; the after-GC heap floor rose $HEAP_DELTA KiB over STEADY (first full collection $HEAP_FIRST KiB, last $HEAP_LAST KiB), above the $SOAK_HEAP_FLOOR_NOISE_KIB KiB floor"
      fi
    fi

    STUCK=$(metric_or "$metrics" thread_dump_stuck_threads 0)
    # 'stuck' is in the wording on purpose: controls.tsv's stall-callback row matches this reason
    # by substring, and the reason text is now the only place a control is scored against.
    is_number "$STUCK" && [ "$STUCK" -eq 0 ] ||
      REASONS="$REASONS; $STUCK stuck thread(s) showed the same top frame in three consecutive thread dumps"

    # The recording and the report themselves.
    [ -n "$JFR_FILE" ] ||
      REASONS="$REASONS; no flight recording was written (JFR.dump exit $JFR_DUMP_EXIT and no dumponexit file)"
    [ "$REPORT_EXIT" -eq 0 ] ||
      REASONS="$REASONS; SoakReport exited $REPORT_EXIT (see logs/soak-report.log)"
    # The launch gate counted these from jfr-methodtrace.log; a --report pass has only the
    # recording, so the recording's own numbers win when it has them.
    local resolved requested
    resolved=$(metric_or "$metrics" method_filters_resolved "$FILTERS_RESOLVED")
    requested=$(metric_or "$metrics" method_filters_requested "$FILTERS_REQUESTED")
    if is_number "$resolved" && is_number "$requested"; then
      FILTERS_RESOLVED=$resolved; FILTERS_REQUESTED=$requested
      [ "$resolved" -ge "$requested" ] ||
        REASONS="$REASONS; the recording resolved $resolved of $requested method-timing entries"
    fi

    # Issue #52 gates. #52 itself never contributes a FAIL; these three are harness-health
    # checks - a detector that cannot route its own evidence has not measured anything.
    I52_UNROUTED=$(metric_or "$metrics" i52_claims_unrouted_pct 0)
    I52_ORIGIN_UNKNOWN=$(metric_or "$metrics" i52_origin_unknown_pct 0)
    I52_UNEXPLAINED=$(metric_or "$metrics" i52_double_claims_unexplained 0)
    I52_UNOBSERVED=$(metric_or "$metrics" i52_unobserved_delivery -1)
    gt "$I52_UNROUTED" 5 && REASONS="$REASONS; ${I52_UNROUTED}% of manager claims could not be routed to a retirement (limit 5%)" || true
    gt "$I52_ORIGIN_UNKNOWN" 10 && REASONS="$REASONS; ${I52_ORIGIN_UNKNOWN}% of claims had an unknown origin attempt (limit 10%)" || true
    is_number "$I52_UNEXPLAINED" && [ "$I52_UNEXPLAINED" -eq 0 ] ||
      REASONS="$REASONS; $I52_UNEXPLAINED double claim(s) with no successor activity between them: a harness bug, not a library finding"
    if [ "$I52_UNOBSERVED" = "-1" ]; then
      NOTES="$NOTES; i52_unobserved_delivery not reported"
    elif is_number "$I52_UNOBSERVED" && [ "$I52_UNOBSERVED" -gt 0 ]; then
      REASONS="$REASONS; $I52_UNOBSERVED retirement(s) with an unobserved delivery"
    fi

    # ---- NOTES: everything that is reported and judges nothing ----
    PINNED=$(metric_or "$metrics" pinned 0); PINNED_MAX=$(metric_or "$metrics" pinned_max_ms 0)
    OLD_OBJECTS=$(metric_or "$metrics" old_object_samples 0)
    OLD_ROOTED=$(metric_or "$metrics" old_object_samples_with_root 0)
    OLD_TYPES=$(metric_or "$metrics" old_object_top_types "")
    RING_COVERAGE=$(metric_or "$metrics" ring_coverage_pct n/a)
    THROTTLE=$(metric_or "$metrics" exception_throttle_saturated 0)
    DOMINANT_FLAG=$(metric_or "$metrics" dominant_thread_flag 0)
    DOMINANT=$(metric_or "$metrics" dominant_thread "")
    DOMINANT_SHARE=$(metric_or "$metrics" dominant_thread_share_pct 0)
    SOCKET_SHARE=$(metric_or "$metrics" socket_event_share_pct n/a)
    I52_TRIGGER=$(metric_or "$metrics" i52_trigger_met 0)
    I52_VERDICT=$(metric_or "$metrics" i52_verdict INCONCLUSIVE)
    # A bounded TSV writer that dropped rows leaves the row-derived tables (retirements, claims,
    # the fault join) short by that many, so the count is stated rather than left to the report.
    # It is a NOTE and not an INVALID: the counters and the ledger are written straight through
    # and are unaffected, so the verdict's own inputs are still complete.
    TSV_DROPPED=$(metric_or "$counters" harness.tsv.dropped 0)
    # A close the PEER chose, because its own per-connection outbound queue filled: the subject was
    # reading more slowly than the peer was writing, which is a property of the two ends' relative
    # speed on this machine and not of the library's connection handling. It is reported so the
    # count is visible, and it is never a FAIL - the unexplained-retirement gate is the one that
    # judges retirements, and these are already subtracted from it because the peer, not the
    # subject, decided to close.
    PEER_OVERFLOW=$(metric_or "$counters" ws.retirement.peerOverflow 0)
    OVERFLOW_CAP=$(metric_or "$counters" ws.retirement.overflowCap 0)
    REJECT_ESCALATED=$(metric_or "$counters" ws.retirement.rejectEscalated 0)
    [ "$OVERFLOW_CAP" = 0 ] ||
      NOTES="$NOTES; $OVERFLOW_CAP connection(s) retired by the overflow engine's own message cap, tripped by a message that was not a scheduled MESSAGE_OVERFLOW (the peer's periodic large notification is above that engine's cap); the engine enforcing its configured cap, subtracted from the unexplained-retirement gate"
    [ "$REJECT_ESCALATED" = 0 ] ||
      NOTES="$NOTES; $REJECT_ESCALATED connection(s) replaced by the engine after a subscribe the peer refused with -32603 was escalated as unanswered: the refusal is the cause and the retirement is subtracted from the unexplained gate; whether a re-send should have preceded the escalation is an observation for the owner (see the REJECT_ESCALATED anomaly rows)"
    [ "$PINNED" = 0 ] || NOTES="$NOTES; $PINNED virtual thread(s) pinned, longest $PINNED_MAX ms - on this JDK monitors no longer pin, so each one is a native or VM frame worth reading"
    if [ "$OLD_OBJECTS" = 0 ]; then
      NOTES="$NOTES; jdk.OldObjectSample produced no sample: the retention lens is INCONCLUSIVE for this heap configuration, not clean"
    else
      NOTES="$NOTES; jdk.OldObjectSample $OLD_OBJECTS sample(s), $OLD_ROOTED with a root path, top types [$OLD_TYPES]"
    fi
    [ "$RING_COVERAGE" = n/a ] || NOTES="$NOTES; the recording ring covers ${RING_COVERAGE}% of the run"
    [ "$THROTTLE" = 0 ] || NOTES="$NOTES; the exception throttle saturated: jdk.JavaExceptionThrow is undercounting, read jdk.ExceptionStatistics for the totals"
    [ "$DOMINANT_FLAG" = 0 ] || NOTES="$NOTES; one thread dominates the execution samples ($DOMINANT, ${DOMINANT_SHARE}%)"
    [ "$SOCKET_SHARE" = n/a ] || NOTES="$NOTES; socket events are ${SOCKET_SHARE}% of the ring"
    [ "$JFR_DUMP_EXIT" -eq 0 ] || [ "$JFR_DUMP_EXIT" -eq -1 ] ||
      NOTES="$NOTES; the root-walk dump exited $JFR_DUMP_EXIT (124 = abandoned at ${JFR_DUMP_BOUND}s); the report fell back to $JFR_SOURCE"
    is_number "$TSV_DROPPED" && [ "$TSV_DROPPED" -eq 0 ] ||
      NOTES="$NOTES; a bounded TSV writer dropped $TSV_DROPPED row(s) (harness.tsv.dropped): every table derived from those rows is short by that many"
    is_number "$PEER_OVERFLOW" && [ "$PEER_OVERFLOW" -eq 0 ] ||
      NOTES="$NOTES; $PEER_OVERFLOW connection(s) closed by the peer's own outbound-queue overflow (the subject read slower than SOAK_WS_NOTIFY_RPS for the queue's depth); these are the peer's retirements, subtracted from the unexplained count"
    [ "$JFR_SUMMARY_EXIT" -le 0 ] || NOTES="$NOTES; 'jfr summary' exited $JFR_SUMMARY_EXIT"
    [ "$SOAK_LIVE" != 1 ] || NOTES="$NOTES; live profile: every peer-established (grade C) property is NOT_EVALUATED and no X-Soak-* header was sent"
  fi

  # ---- pick one ----
  if [ -n "$INVALIDS" ]; then
    VERDICT=INVALID; EXIT_CODE=$STATUS_INVALID
  elif [ -n "$INCOMPLETES" ]; then
    VERDICT=INCOMPLETE; EXIT_CODE=$STATUS_INCOMPLETE
  elif [ -n "$REASONS" ]; then
    VERDICT=FAIL; EXIT_CODE=$STATUS_FAIL
  elif [ "${I52_TRIGGER:-0}" = 1 ]; then
    # Every gate passed and the issue #52 trigger was met: a misattribution under a positive,
    # escalating backoff without harness forcing. That is a finding to take to the owner, not a
    # broken harness and not a run the library failed.
    VERDICT=PASS-WITH-FINDING; EXIT_CODE=$STATUS_FINDING
  else
    VERDICT=PASS; EXIT_CODE=$STATUS_PASS
  fi
}

# Everything that disqualifies the newest controls run from being cited, as one line. A controls
# run is only evidence about this subject when it was green AND it saw this code, so both halves
# are checked here: recency alone says nothing about whether the oracle could see a defect.
stale_controls() {
  local newest sava_ct controls_ct misses missed id out=""
  newest=$(newest_controls_dir)
  if [ -z "$newest" ] || [ ! -s "$newest/controls.md" ]; then
    printf 'no controls run has been recorded: run soak.sh --controls before a pilot or campaign\n'
    return
  fi
  id=$(basename "$newest")

  # Greenness, read from the machine-readable half of the sheet rather than from its prose: a
  # controls run in which a defect control missed proves the oracle can NOT see that defect,
  # which is the one thing citing a controls run is supposed to establish. do_controls wrote the
  # miss count and the ids; an absent file is a sheet from before this was recorded, and cannot
  # be assumed green.
  if [ ! -s "$newest/controls.env" ]; then
    out="$out; red controls: $id recorded no controls.env, so nothing says its controls were green"
  else
    misses=$(sed -n 's/^CONTROLS_MISSES=//p' "$newest/controls.env" | head -n 1)
    missed=$(sed -n 's/^CONTROLS_MISSED_IDS=//p' "$newest/controls.env" | head -n 1)
    case "$misses" in
      0) ;;
      ''|*[!0-9]*) out="$out; red controls: $id recorded no miss count" ;;
      *) out="$out; red controls: $id had $misses control(s) that did not produce their expected verdict (${missed:-unnamed})" ;;
    esac
  fi

  # Judged against the subject as the run SAW it - the stamps run.json recorded at launch - and
  # not against the tree at report time: a commit made while a campaign runs was not under
  # test. Measured 2026-09-23: four library commits landed during an eight-hour campaign that had
  # launched on the very revision its sheet was run on, and the finished campaign read INVALID.
  # Before a run exists (the launch gate) the live tree is the subject. A run from before the
  # stamps carries gitRev, which dates its committed half exactly; its uncommitted half cannot
  # be reconstructed, and stale_controls_note says so rather than inventing a verdict.
  local rev subject_mtime
  if [ -n "$RUN_DIR" ] && [ -s "$RUN_DIR/run.json" ]; then
    sava_ct=$(subject_stamp subjectCommitTime)
    if [ -z "$sava_ct" ]; then
      rev=$(sed -n 's/^ *"gitRev": *"\([0-9a-f]*\)".*/\1/p' "$RUN_DIR/run.json" 2>/dev/null | head -n 1)
      sava_ct=$(git -C "$ROOT" log -1 --format=%ct "${rev:-HEAD}" -- sava-rpc/src/main 2>/dev/null || echo 0)
    fi
    subject_mtime=$(subject_stamp subjectNewestMtime)
  else
    read -r sava_ct subject_mtime <<< "$(subject_source_stamps)"
  fi
  controls_ct=$(stat -f %m "$newest/controls.md" 2>/dev/null || stat -c %Y "$newest/controls.md" 2>/dev/null || echo 0)
  if [ "$controls_ct" -lt "${sava_ct:-0}" ]; then
    out="$out; stale controls: $id predates the sava revision under test"
  fi
  # A commit time cannot see an uncommitted edit, and the client's subject gate guarantees the
  # run soaked the working tree - so the subject's own file times decide too, or a campaign
  # against edited sources could cite an oracle that never met them.
  if [ -n "$subject_mtime" ] && [ "$subject_mtime" -gt "$controls_ct" ]; then
    out="$out; stale controls: a change under sava-rpc/src/main is newer than $id"
  fi

  [ -z "$out" ] || printf '%s\n' "${out#; }"
}

# The half of the staleness question a run from before the launch-time stamps cannot answer.
stale_controls_note() {
  [ -n "$RUN_DIR" ] && [ -s "$RUN_DIR/run.json" ] || return 0
  [ -z "$(subject_stamp subjectNewestMtime)" ] || return 0
  printf 'this run predates the launch-time source stamps: gitRev dates its committed subject, but an uncommitted edit under sava-rpc/src/main at launch cannot be excluded\n'
}

write_summary() {
  local ran=$1 client_exit=$2
  echo "# sava RPC soak: $SOAK_PROFILE"
  echo
  echo "$VERDICT"
  [ "${I52_VERDICT:-}" = "" ] || echo "ISSUE52: ${I52_VERDICT}"
  echo
  if [ -n "$INVALIDS" ]; then echo "INVALID because${INVALIDS#;}"; echo; fi
  if [ -n "$INCOMPLETES" ]; then echo "INCOMPLETE because${INCOMPLETES#;}"; echo; fi
  if [ -n "$REASONS" ]; then echo "FAIL reasons:${REASONS#;}"; echo; fi
  if [ -n "$NOTES" ]; then echo "Notes (nothing here judges the run):${NOTES#;}"; echo; fi
  echo "## Run"
  echo
  echo "- run directory: \`$RUN_DIR\`"
  echo "- command: \`$COMMAND_LINE\`"
  echo "- started $STARTED_AT, finished $FINISHED_AT; client exit $client_exit, peer exit ${PEER_EXIT:--}"
  echo "- duration ${SOAK_DURATION_SECONDS}s (warm-up ${SOAK_WARMUP_SECONDS}s, quiesce ${SOAK_QUIESCE_SECONDS}s, drain ${SOAK_DRAIN_SECONDS}s), seed $SOAK_SEED"
  echo "- heap ${SOAK_HEAP_MB}m (-Xms = -Xmx), G1; recording ring $SOAK_JFR_MAXSIZE, max age $SOAK_JFR_MAXAGE"
  # Only when a row asked for them: a flag that shaped what this run measured has to be visible
  # beside the numbers it shaped, not only in run.env. Read defensively because --report over an
  # older run directory has no such key in its run.env.
  [ -z "${SOAK_JVM_EXTRA:-}" ] || echo "- extra client JVM arguments: \`${SOAK_JVM_EXTRA}\`"
  echo "- method-timing entries resolved: ${FILTERS_RESOLVED}/${FILTERS_REQUESTED}"
  echo "- recording used by the report: ${JFR_FILE:-none} (${JFR_SOURCE:-none})"
  echo
  if [ "$ran" = 1 ]; then
    echo "## Numbers behind the verdict"
    echo
    echo "| number | value | gate |"
    echo "|---|---|---|"
    echo "| notifications delivered | ${DELIVERED:-n/a} | > 0 |"
    echo "| RPC responses completed | ${RPC_DONE:-n/a} | > 0 |"
    echo "| request/response mismatches | ${MISMATCHES:-n/a} | 0 |"
    echo "| fault fidelity | ${FAULT_FIDELITY:-n/a} | >= 0.90 |"
    echo "| faults with no target | ${FAULT_NO_TARGET:-n/a} | reported |"
    echo "| recovery records over budget | ${RECOVERY_OVER:-n/a} | 0 |"
    echo "| exchanges settled past the deadline | ${DEADLINE_MISSED:-n/a} | 0 |"
    echo "| live threads, STEADY baseline -> pre-SHUTDOWN peak (last row) | ${THR_BASE:-n/a} -> ${THR_PEAK:-n/a} (${THR_LAST:-n/a}) | peak <= baseline + ${SOAK_THREAD_MARGIN} |"
    echo "| open descriptors, STEADY baseline -> pre-SHUTDOWN peak (last row) | ${FD_BASE:-n/a} -> ${FD_PEAK:-n/a} (${FD_LAST:-n/a}) | peak <= baseline + ${SOAK_FD_MARGIN} |"
    echo "| virtual threads started - ended | $(( ${VT_STARTED:-0} - ${VT_ENDED:-0} )) | <= ${SOAK_VTHREAD_MARGIN} |"
    echo "| RSS KiB first -> last | ${RSS_FIRST:-n/a} -> ${RSS_LAST:-n/a} | |"
    echo "| RSS slope after warm-up | ${RSS_SLOPE:-n/a} KiB/h over ${RSS_WINDOW:-0}s | <= ${RSS_LIMIT:-n/a} |"
    echo "| gated native memory KiB, baseline -> final | ${NMT_BASE_G:-n/a} -> ${NMT_FINAL_G:-n/a} | |"
    echo "| gated native memory slope | ${NMT_SLOPE:-n/a} KiB/h over ${NMT_WINDOW:-0}s | <= ${NMT_LIMIT:-n/a} |"
    echo "| after-GC heap floor, first -> last full collection in STEADY | ${HEAP_FIRST:-n/a} -> ${HEAP_LAST:-n/a} KiB | rise <= $SOAK_HEAP_FLOOR_NOISE_KIB KiB |"
    echo "| after-GC heap floor slope (informational) | ${HEAP_SLOPE:-n/a} KiB/h | not judged |"
    echo "| registrations retained at DRAIN end | ${D_REGS:-n/a} | 0 |"
    echo "| requests in flight at DRAIN end (of them, past their own bound) | ${D_INFLIGHT:-n/a} (${D_OVERDUE:-n/a}) | overdue 0 |"
    echo "| stuck threads | ${STUCK:-n/a} | 0 |"
    echo "| claims unrouted | ${I52_UNROUTED:-n/a}% | <= 5% |"
    echo "| claims with unknown origin | ${I52_ORIGIN_UNKNOWN:-n/a}% | <= 10% |"
    echo "| unexplained double claims | ${I52_UNEXPLAINED:-n/a} | 0 |"
    echo "| issue #52 trigger met | ${I52_TRIGGER:-0} | a finding, never a FAIL |"
    echo
    echo "The verdict above reads only \`jfr-metrics.properties\`, \`counters.properties\`,"
    echo "\`properties.tsv\`, \`client.csv\`, \`rss.csv\` and \`nmt.csv\`, plus the launch gates and"
    echo "the process exit codes. It never parses a Markdown report."
    echo
    echo "## Correctness ledger"
    echo
    if [ -s "$RUN_DIR/properties.tsv" ]; then
      echo '```'
      awk -F'\t' '
        NR == 1 && $1 == "id" { next }
        NF >= 6 { printf "%-8s %-24s evaluated=%-7s passed=%-7s failed=%-5s %s\n", $1, $2, $3, $4, $5, $6 }
      ' "$RUN_DIR/properties.tsv"
      echo '```'
    else
      echo "(no properties.tsv was written)"
    fi
    echo
    echo "## Report sections"
    echo
    if [ -s "$RUN_DIR/jfr-report.md" ]; then
      cat "$RUN_DIR/jfr-report.md"
    else
      echo "(no jfr-report.md; see logs/soak-report.log)"
    fi
    if [ -s "$RUN_DIR/issue52.md" ]; then
      echo
      echo "## Issue #52"
      echo
      cat "$RUN_DIR/issue52.md"
    fi
  else
    echo "## client.log (last 40 lines)"
    echo
    echo '```'
    tail -n 40 "$RUN_DIR/logs/client.log" 2>/dev/null || true
    echo '```'
    echo
    echo "## peer.log (last 20 lines)"
    echo
    echo '```'
    tail -n 20 "$RUN_DIR/logs/peer.log" 2>/dev/null || true
    echo '```'
  fi
}

# ---------------------------------------------------------------------------- modes

# control_hit <run dir> <needle>: did this run's verdict actually turn on what the control row
# names? Three sources, never the summary as a whole - write_summary prints the correctness ledger
# and the report's property table on EVERY run, so a needle matched against the file was
# satisfied by construction and a defect control that failed for an unrelated reason scored as a
# hit. For a property id the ledger decides, on exactly the conditions property_verdicts turns
# into a FAIL. A needle of the form key=value is also looked up in jfr-metrics.properties, which
# is what lets a PASS row name a number it must have produced - a PASSing run has no verdict prose
# at all, so a needle it could only match there would be unassertable. Otherwise only the
# verdict-bearing prose counts, which is why a FAIL reason's wording is an interface.
control_hit() {
  local dir=$1 needle=$2
  local tsv=$dir/properties.tsv
  if [ -s "$tsv" ] &&
     awk -F'\t' -v want="$needle" 'NR > 1 && $1 == want { found = 1 } END { exit !found }' "$tsv"; then
    awk -F'\t' -v want="$needle" '
      NR == 1 && $1 == "id" { next }
      NF >= 6 && $1 == want && $2 ~ /^(A|B|C)_/ && ($5 + 0 > 0 || ($3 + 0 == 0 && $6 == "")) { hit = 1 }
      END { exit !hit }' "$tsv"
    return
  fi
  case "$needle" in
    *=*)
      [ "$(metric "$dir/jfr-metrics.properties" "${needle%%=*}")" != "${needle#*=}" ] || return 0 ;;
  esac
  [ -s "$dir/summary.md" ] || return 1
  grep -E '^(INVALID because|INCOMPLETE because|FAIL reasons:)' "$dir/summary.md" 2>/dev/null |
    grep -qF -- "$needle"
}

# metric_expr <run dir> <expression>: the controls.tsv `metric` column, evaluated against the
# run's own machine-readable artifacts. It exists because some rows assert a number rather than a
# verdict - a forced #52 reproduction must show a misattribution AND show that it did not claim
# the revisit trigger - and a substring of a Markdown file cannot say that. Terms are space
# separated and every one of them must hold:
#
#   <key><op><value>  from jfr-metrics.properties, op in >= <= != == = > < ; numeric when both
#                     sides are numbers, an exact string comparison otherwise (so `= n/a` works,
#                     while an ordering over a non-number is always a miss)
#   present:<key>     jfr-metrics.properties carries <key> with a value that is neither empty nor
#                     n/a: 'this run measured it', which is not the same as any particular value
#   schedule:<KIND>   the run's fault-schedule.tsv plans at least one fault of that kind, so a row
#                     whose injection was never even scheduled fails loudly instead of passing on
#                     some other kind's behaviour
#   peer:<LINE>       logs/peer.log carries exactly that line, for the facts the peer prints once
#                     at startup (SCHEDULE_PLANNED=<n> and the schedule digests)
#
# A missing file or an unreadable key is a miss, never a pass: the row claimed a measurement, and
# the absence of one is exactly the case this column exists to catch.
metric_expr() {
  local dir=$1 expression=$2 term key op want got candidate
  local metrics=$dir/jfr-metrics.properties
  # Split on whitespace without letting a term glob against the working directory.
  local -a terms=(); read -r -a terms <<< "$expression"
  for term in "${terms[@]}"; do
    case "$term" in
      -|'') continue ;;
      present:*)
        got=$(metric "$metrics" "${term#present:}")
        [ -n "$got" ] && [ "$got" != n/a ] || return 1
        continue ;;
      schedule:*)
        awk -F'\t' -v k="${term#schedule:}" 'NR > 1 && $2 == k { found = 1 } END { exit !found }' \
          "$dir/fault-schedule.tsv" 2>/dev/null || return 1
        continue ;;
      peer:*)
        grep -qxF -- "${term#peer:}" "$dir/logs/peer.log" 2>/dev/null || return 1
        continue ;;
    esac
    key=""; op=""; want=""
    for candidate in '>=' '<=' '!=' '==' '>' '<' '='; do
      case "$term" in
        *"$candidate"*) key=${term%%"$candidate"*}; want=${term#*"$candidate"}; op=$candidate; break ;;
      esac
    done
    [ -n "$op" ] && [ -n "$key" ] || die "controls.tsv: cannot parse the metric term '$term'"
    got=$(metric "$metrics" "$key")
    [ -n "$got" ] || return 1
    if is_decimal "$got" && is_decimal "$want"; then
      awk -v a="$got" -v b="$want" -v op="$op" 'BEGIN {
        a += 0; b += 0
        if (op == "=" || op == "==") ok = (a == b)
        else if (op == "!=") ok = (a != b)
        else if (op == ">=") ok = (a >= b)
        else if (op == "<=") ok = (a <= b)
        else if (op == ">") ok = (a > b)
        else ok = (a < b)
        exit !ok
      }' || return 1
    else
      case "$op" in
        '='|'==') [ "$got" = "$want" ] || return 1 ;;
        '!=') [ "$got" != "$want" ] || return 1 ;;
        *) return 1 ;;
      esac
    fi
  done
  return 0
}

# Judges one row of a sheet from `id kind expected property metric_check mechanism actual` (the
# caller's variables) and appends its line to `$md`, tallying `misses`/`missed_ids`. Shared by the
# sheet that runs rows and the re-score that re-reads them, so the two cannot judge differently.
score_control_row() {
  # Evidence, scored on every row that names any - a PASS row included. A run that passed
  # because its injection never happened passes the verdict test and nothing else, so the
  # property needle and the metric expression are what tells the two apart. A selftest row has
  # no run directory, so it carries neither.
  local observed=- met=- ok=no
  if [ "$kind" != selftest ]; then
    if [ -n "$property" ] && [ "$property" != - ]; then
      if control_hit "$parent/$id" "$property"; then observed=yes; else observed=no; fi
    fi
    if [ -n "$metric_check" ] && [ "$metric_check" != - ]; then
      if metric_expr "$parent/$id" "$metric_check"; then met=yes; else met=no; fi
    fi
  fi
  # A defect control must FAIL, and must FAIL on the property it was designed to break: a run
  # that failed for an unrelated reason is not evidence that the oracle can see this defect. A
  # PASS row is held to the same standard from the other side - the evidence it names must hold
  # - because a PASS is the cheapest thing a broken injection can produce.
  local evidence=yes
  { [ "$observed" != no ] && [ "$met" != no ]; } || evidence=no
  case "$expected" in
    FAIL|INVALID)
      if [ "$actual" = "$expected" ] && [ "$evidence" = yes ]; then ok=yes; fi ;;
    PASS)
      # A fault control may legitimately end PASS-WITH-FINDING: the issue #52 trigger is a
      # finding to take to the owner, never a run the library failed.
      if { [ "$actual" = PASS ] || [ "$actual" = PASS-WITH-FINDING ]; } &&
         [ "$evidence" = yes ]; then ok=yes; fi ;;
    *)
      if [ "$actual" = "$expected" ] && [ "$evidence" = yes ]; then ok=yes; fi ;;
  esac
  if [ "$ok" != yes ]; then
    misses=$((misses + 1))
    missed_ids="$missed_ids,$id"
  fi
  printf '| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n' \
    "$id" "$kind" "${mechanism:-}" "$expected" "$actual" "${property:--}" "$observed" \
    "${metric_check:--}" "$met" "$ok" >> "$md"
}

write_controls_env() {
  # The machine-readable half of the sheet. stale_controls reads this, not the paragraph below:
  # a campaign that cites a controls run must be able to tell a green one from a red one without
  # parsing display text, and the paragraph is display text.
  printf 'CONTROLS_RUN=%s\nCONTROLS_MISSES=%d\nCONTROLS_MISSED_IDS=%s\n' \
    "controls-$stamp" "$misses" "${missed_ids#,}" > "$parent/controls.env"
  [ -z "${RESCORED_AT:-}" ] || printf 'CONTROLS_RESCORED_AT=%s\n' "$RESCORED_AT" >> "$parent/controls.env"
}

write_controls_tail() {

  {
    echo
    if [ "$misses" -eq 0 ]; then
      echo "Every control produced its expected verdict. A pilot or campaign started before the"
      echo "next change under \`sava-rpc/src/main\` may cite this run."
    else
      echo "$misses control(s) did not produce their expected verdict. The oracle cannot be"
      echo "trusted until each one is explained; a pilot or campaign started now is not evidence."
    fi
  } >> "$md"
}

# --controls-rescore: the same sheet, re-judged. A run directory is the evidence and a verdict is
# the runner's reading of it, so a verdict rule that changed (a gate's floor, a note that used to
# be a FAIL) is re-applied to the recorded artifacts instead of measuring everything again - a
# three-hour sheet re-run to move one row's reading was the alternative. Every row's verdict is
# re-derived through `soak.sh --report` (SoakReport plus the verdict, from that row's own run.env),
# the rows are re-judged by the same scoring the sheet used, and the sheet says it was re-scored
# and when; the original exit codes are kept beside the new ones. Nothing here can change what a
# row MEASURED: a code change under sava-rpc still needs a fresh sheet, and stale_controls keeps
# checking that.
do_controls_rescore() {
  local parent
  parent=$(cd "$RESCORE_DIR" && pwd) || die "no such controls directory: $RESCORE_DIR"
  local tsv=$parent/controls.tsv
  [ -s "$tsv" ] || die "$parent has no controls.tsv snapshot; only a sheet written by --controls can be re-scored"
  local stamp=${parent##*/controls-}
  local md=$parent/controls.md
  local misses=0 missed_ids="" kept_ids=""
  RESCORED_AT=$(date '+%Y-%m-%d %H:%M:%S')
  resolve_toolchain
  {
    echo "# Negative controls: $stamp (re-scored $RESCORED_AT)"
    echo
    echo "Defect controls must FAIL on the named property and fault controls must PASS; a"
    echo "control that does not produce its expected verdict blocks the pilot and the campaign."
    echo "That is what stops a green campaign that was green because nothing was watching."
    echo
    echo "This sheet was RE-SCORED: every row's verdict was re-derived from the artifacts the row"
    echo "recorded, under the runner's current verdict rules (\`soak.sh --report\` per row), and"
    echo "the rows were re-judged by the sheet's own scoring. No row was re-run, so nothing a row"
    echo "measured has changed; a selftest row keeps the exit code it recorded, and each row's"
    echo "original exit code is kept as \`<id>.exit.original\`."
    echo
    echo "| id | kind | injection | expected | actual | property | seen | metric | met | ok |"
    echo "|---|---|---|---|---|---|---|---|---|---|"
  } > "$md"
  local id kind expected property overrides metric_check mechanism actual code
  while IFS=$'\t' read -r id kind expected property overrides metric_check mechanism; do
    case "$id" in ''|'#'*|id) continue ;; esac
    if [ "$kind" = selftest ]; then
      code=$(cat "$parent/$id.exit" 2>/dev/null || echo 99)
      case "$code" in
        0) actual=PASS ;;
        124) actual="selftest abandoned at ${SELFTEST_BOUND}s" ;;
        *) actual="selftest exit $code" ;;
      esac
    elif [ ! -s "$parent/$id/run.env" ]; then
      actual="no run directory"
    elif [ ! -s "$parent/$id/run-exit.env" ]; then
      # The run never reached its normal end: a launch gate stopped it (a subject that refused to
      # start, a method-filter entry that resolved to nothing) and the verdict that gate wrote is
      # the run's, not a rule the report could re-derive from artifacts the run never produced.
      # Kept at its recorded exit code, and said so.
      code=$(cat "$parent/$id.exit.original" 2>/dev/null || cat "$parent/$id.exit" 2>/dev/null || echo 99)
      kept_ids="$kept_ids,$id"
      case "$code" in
        0) actual=PASS ;; 1) actual=FAIL ;; 2) actual=PASS-WITH-FINDING ;;
        3) actual=INCOMPLETE ;; 4) actual=INVALID ;; *) actual="runner exit $code" ;;
      esac
    else
      log "re-scoring $id"
      [ -f "$parent/$id.exit.original" ] || cp "$parent/$id.exit" "$parent/$id.exit.original" 2>/dev/null || true
      (
        set +e
        # shellcheck disable=SC2030,SC2031  # each row's exports live in its own subshell
        export SOAK_NO_LOCK=1
        "$SCRIPT_DIR/soak.sh" --report "$parent/$id" > "$parent/$id.rescore.log" 2>&1
        echo $? > "$parent/$id.exit"
      ) || true
      code=$(cat "$parent/$id.exit" 2>/dev/null || echo 99)
      case "$code" in
        0) actual=PASS ;; 1) actual=FAIL ;; 2) actual=PASS-WITH-FINDING ;;
        3) actual=INCOMPLETE ;; 4) actual=INVALID ;; *) actual="runner exit $code" ;;
      esac
    fi
    score_control_row
  done < "$tsv"
  write_controls_env
  [ -z "$kept_ids" ] || {
    echo
    echo "Rows kept at their recorded verdict because a launch gate ended the run before it wrote"
    echo "\`run-exit.env\` (nothing to re-derive from): ${kept_ids#,}."
  } >> "$md"
  write_controls_tail
  log "re-scored: $md"
  [ "$misses" -eq 0 ] && exit 0 || exit 1
}

do_controls() {
  local tsv=$SCRIPT_DIR/controls.tsv
  [ -s "$tsv" ] || die "missing $tsv"
  local stamp; stamp=$(date +%Y%m%d-%H%M%S)
  local parent=$SCRIPT_DIR/build/runs/controls-$stamp
  mkdir -p "$parent"
  local md=$parent/controls.md misses=0 missed_ids=""
  {
    echo "# Negative controls: $stamp"
    echo
    echo "Defect controls must FAIL on the named property and fault controls must PASS; a"
    echo "control that does not produce its expected verdict blocks the pilot and the campaign."
    echo "That is what stops a green campaign that was green because nothing was watching."
    echo
    echo "\`seen\` is the property needle in this run's ledger or verdict prose, \`met\` the row's"
    echo "metric expression over its own jfr-metrics.properties, fault-schedule.tsv and peer.log;"
    echo "both are scored on PASS rows too, so an injection that never happened cannot pass by"
    echo "producing a quiet run. A \`selftest\` row runs \`soak.sh --selftest\` instead of a soak and"
    echo "is scored on its exit code alone."
    echo
    echo "| id | kind | injection | expected | actual | property | seen | metric | met | ok |"
    echo "|---|---|---|---|---|---|---|---|---|---|"
  } > "$md"

  # The sheet runs over its own copy of the rows: an edit to controls.tsv during a three-hour
  # campaign would otherwise be read mid-file by this loop (a truncating rewrite tears the line
  # being read into a phantom row, measured 2026-09-22), and the copy is the provenance of what
  # the sheet actually ran.
  cp "$tsv" "$parent/controls.tsv"
  tsv=$parent/controls.tsv
  local id kind expected property overrides metric_check mechanism
  while IFS=$'\t' read -r id kind expected property overrides metric_check mechanism; do
    case "$id" in ''|'#'*|id) continue ;; esac
    log "control $id ($kind, expecting $expected)"
    local actual ok=no code
    if [ "$kind" = selftest ]; then
      # A selftest row asserts the harness's own detector and attribution cases rather than a
      # soak: there is no subject JVM, no run directory and no verdict to read, only the exit
      # code of soak.sh --selftest, which is 0 only when every case printed PASS. It is bounded
      # like every other stage here, and an abandoned self-test is a miss, never a silent skip.
      (
        set +e
        # shellcheck disable=SC2030,SC2031  # each row's exports live in its own subshell
        export SOAK_SKIP_BUILD=1 SOAK_NO_LOCK=1
        run_bounded "$SELFTEST_BOUND" "selftest ($id)" "$SCRIPT_DIR/soak.sh" --selftest \
          > "$parent/$id.log" 2>&1
        echo $? > "$parent/$id.exit"
      ) || true
      code=$(cat "$parent/$id.exit" 2>/dev/null || echo 99)
      case "$code" in
        0) actual=PASS ;;
        124) actual="selftest abandoned at ${SELFTEST_BOUND}s" ;;
        *) actual="selftest exit $code" ;;
      esac
    else
      # Each control is its own bounded run under the 'control' profile, with the row's overrides
      # exported and SOAK_CONTROL naming the row so the peer and the harness know what to inject.
      #
      # The sheet sets no duration of its own any more. A control row is a validate run plus its
      # overrides, so it runs for the validate profile's SOAK_DURATION_SECONDS unless the row's own
      # overrides column says otherwise, and the sheet costs that much - plus each row's peer and
      # client start, final JFR dump and SoakReport pass - times the number of rows in
      # controls.tsv. That is the honest price of measuring a control under the knobs it guards;
      # the shorter row it replaces was cheap and was evidence about a run nobody performs.
      (
        set +e
        kv=""
        for kv in $overrides; do
          case "$kv" in -|'') continue ;; esac
          export "${kv?}"
        done
        export SOAK_CONTROL=$id
        # The parent built once and holds the lock for the whole sheet; a child that rebuilt would
        # run Gradle once per control row, and a child that took the lock would die against its
        # own parent.
        # shellcheck disable=SC2030,SC2031  # each row's exports live in its own subshell
        export SOAK_SKIP_BUILD=1 SOAK_NO_LOCK=1 CONTROLS_PARENT="$parent"
        "$SCRIPT_DIR/soak.sh" control --control "$id" > "$parent/$id.log" 2>&1
        echo $? > "$parent/$id.exit"
      ) || true
      code=$(cat "$parent/$id.exit" 2>/dev/null || echo 99)
      case "$code" in
        0) actual=PASS ;; 1) actual=FAIL ;; 2) actual=PASS-WITH-FINDING ;;
        3) actual=INCOMPLETE ;; 4) actual=INVALID ;; *) actual="runner exit $code" ;;
      esac
    fi
    score_control_row
  done < "$tsv"

  write_controls_env
  write_controls_tail
  cat "$md"
  log "controls: $md"
  [ "$misses" -eq 0 ] || exit 1
}

do_selftest() {
  # The self-test builds and runs a JVM of its own, so it takes the lock like a soak does.
  [ "$DRY_RUN" = 1 ] || take_lock
  resolve_toolchain
  local -a selftest_cmd=(
    "$JAVA"
    --add-opens "software.sava.rpc/software.sava.rpc.json.http.ws=$MODULE"
    --add-opens "software.sava.ravina_solana/software.sava.services.solana.websocket=$MODULE"
    -p "$MODULE_PATH" -m "$SELFTEST_MAIN"
  )
  # SOAK_DRY_RUN starts nothing, here too: controls.tsv's selftest rows run this mode, so a dry
  # controls sheet would otherwise launch a JVM per selftest row while claiming it started none.
  if [ "$DRY_RUN" = 1 ]; then
    echo "# soak.sh dry run: selftest"
    echo
    echo "# ---- selftest launch ----"
    quoted "${selftest_cmd[@]}"; echo
    exit 0
  fi
  build_harness || die "${BUILD_FAILED:-build failed}"
  log "running the attribution and detector self-test"
  set +e
  "${selftest_cmd[@]}"
  local status=$?
  set -e
  exit "$status"
}

do_report() {
  RUN_DIR=$(cd "$REPORT_ONLY_DIR" && pwd) || die "no such run directory: $REPORT_ONLY_DIR"
  [ -s "$RUN_DIR/run.env" ] || die "$RUN_DIR has no run.env; it is not a soak run directory"
  # The previous reading is kept: a re-report replaces summary.md, and for a run a launch gate
  # ended that file is the only record of the gate's verdict (a re-score once overwrote two such
  # rows and lost the evidence their control needles matched).
  [ ! -s "$RUN_DIR/summary.md" ] || cp "$RUN_DIR/summary.md" "$RUN_DIR/summary.md.before-report"
  resolve_toolchain
  # Re-read the run's own resolved configuration: the verdict's margins and slopes belong to the
  # run that produced the artifacts, never to this shell's defaults.
  local line key value
  while IFS= read -r line; do
    case "$line" in '#'*|'') continue ;; esac
    key=${line%%=*}; value=${line#*=}
    case "$key" in SOAK_*) printf -v "$key" '%s' "$value"; export "${key?}" ;; esac
  done < "$RUN_DIR/run.env"
  COMMAND_LINE=$(head -n 1 "$RUN_DIR/run.env"); COMMAND_LINE=${COMMAND_LINE#\# }
  # The run's own start, finish and exit codes, from the file the run wrote; an older run
  # directory without it reports them as unknown rather than as a made-up number.
  local report_client_exit="-"
  STARTED_AT="(unknown: no run-exit.env)"; FINISHED_AT="(unknown: no run-exit.env)"; PEER_EXIT="-"
  if [ -f "$RUN_DIR/run-exit.env" ]; then
    while IFS= read -r line; do
      key=${line%%=*}; value=${line#*=}
      case "$key" in
        STARTED_AT) STARTED_AT=$value ;;
        FINISHED_AT) FINISHED_AT=$value ;;
        CLIENT_EXIT) report_client_exit=$value ;;
        PEER_EXIT) PEER_EXIT=$value ;;
      esac
    done < "$RUN_DIR/run-exit.env"
  fi
  REASONS=""; NOTES=""; INVALIDS=""; INCOMPLETES=""
  finish_run 1 "$report_client_exit"
  exit "$RUN_EXIT"
}

# ---------------------------------------------------------------------------- main

if [ ${#RAW_ARGS[@]} -eq 0 ]; then
  COMMAND_LINE=$0
else
  COMMAND_LINE="$0 $(quoted "${RAW_ARGS[@]}")"
fi

case "$MODE" in
  selftest) do_selftest ;;
  report) do_report ;;
  controls_rescore) do_controls_rescore ;;
  controls)
    # --controls runs Gradle and then a whole campaign of subject JVMs, so it holds the lock for
    # all of it; its children are given SOAK_NO_LOCK=1 because this is the process that holds it.
    # --report is deliberately not here: it starts no JVM and re-reporting an old run while a
    # campaign is in flight must keep working.
    [ "$DRY_RUN" = 1 ] || take_lock
    resolve_toolchain
    build_harness || die "${BUILD_FAILED:-build failed}"
    do_controls
    exit 0
    ;;
esac

if [ "$DRY_RUN" != 1 ]; then
  take_lock
  resolve_toolchain
  if ! build_harness; then
    log "${BUILD_FAILED:-build failed}"
    exit "$STATUS_INCOMPLETE"
  fi
else
  # A dry run resolves the same toolchain files but never builds and never takes the lock.
  resolve_toolchain
fi

RUN_EXIT=$STATUS_PASS
if [ -n "$OPT_CONTROL" ] && [ "$PROFILE" = control ]; then
  # shellcheck disable=SC2031  # set by the --controls parent in this child's environment
  # Not under 'controls-<something>': newest_controls_dir must never mistake a hand-run single
  # control for the sheet a pilot or campaign cites.
  CONTROLS_PARENT=${CONTROLS_PARENT:-$SCRIPT_DIR/build/runs/manual-control}
  mkdir -p "$CONTROLS_PARENT"
  # The row's overrides column applies here too, so a hand-run row is the run the sheet performs
  # (the sheet's parent exports the same values before it launches this child; a key already in
  # the environment wins, which is how an operator's own override on the command line survives).
  while IFS=$'\t' read -r row_id _row_kind _row_expected _row_property row_overrides _row_rest; do
    [ "$row_id" = "$OPT_CONTROL" ] || continue
    for kv in $row_overrides; do
      case "$kv" in -|'') continue ;; esac
      key=${kv%%=*}
      env | grep -q "^$key=" || export "${kv?}"
    done
    break
  done < <(grep -v '^#' "$SCRIPT_DIR/controls.tsv")
  run_profile control "$CONTROLS_PARENT" "$OPT_CONTROL"
else
  run_profile "$PROFILE"
fi
exit "${RUN_EXIT:-0}"
