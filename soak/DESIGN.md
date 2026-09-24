# sava RPC soak harness — design and implementation contract

Status: implementation contract for the first cut (2026-09-21), brought back in line with the
code after the harness was integrated and its first validate runs read, and again after each
round of review fixes (all the same day). The code is
authoritative; this file records the shape agreed before it was written, marks where the
integration deviated and why, and must be updated by whoever changes a format below. Long-form rationale (verified JDK 25 facts, the issue #52
attribution rule, the judged design) lives in the session scratchpad and is summarised in
`README.md`; the owner-facing plan is `../JFR_SOAK_PLAN.local.md` (git-ignored).

## 1. Purpose and rules

The harness drives sava-rpc's own HTTP and websocket clients — code that parses what untrusted
nodes send — over sustained workloads against controlled local peers that inject faults on a
recorded schedule, under Java Flight Recorder, with explicit correctness counters, a Markdown
report and a verdict. It also collects the decision evidence GitHub issue #52 asks for
(websocket attempt attribution) by exercising ravina's `WebSocketManagerImpl` around sava.

Rules the code must keep:

- No change to sava-rpc / sava-core / sava-vanity. Harness-only JFR events; reflection into
  package-private engine probes only under `--add-opens` and only for grade-D numbers.
- Never create `soak/src/`: the root build's gradlex scan auto-includes any
  `src/*/java/module-info.java` as a root subproject and then fails configuration. Sources
  live in `soak/src-main/java` (verified).
- Never run two Gradle invocations in one root concurrently; the runner takes a lock file.
- Assert only guarantees a public contract, a documented invariant, or the controlled peer
  establishes; measurements are grade D and never fail a run.
- A finding becomes a deterministic regression test in sava-rpc's own test source set, never
  a change here.

## 2. Build, module, launch

- `soak/settings.gradle.kts`, `soak/build.gradle.kts`: as written by the wiring probe (plain
  `java` + `application`, vendor-pinned toolchain 25, `includeBuild("..")`, platform
  `solana-version-catalog`, deps `sava-rpc` (local via composite) + `ravina-solana` 25.6.1).
  Add a task `assertNoSrcDir` that `soakModulePath` depends on and that fails if `soak/src`
  exists.
- Module `software.sava.rpc.soak` (`src-main/java/module-info.java`): requires
  `software.sava.rpc`, `software.sava.ravina_solana`, `jdk.jfr`, `jdk.httpserver`,
  `java.net.http`, `java.management`, `jdk.management`, `java.logging`. No exports needed
  (verified for `-m` launch and for JFR events).
- No test source set. The attribution self-test is a `main` program in the main source set
  (`software.sava.rpc.soak.selftest.SelfTest`), run by `soak.sh --selftest` with the same
  launch line as a soak (so it has `--add-opens`).
- Entry points (all in the main module, launched with `-p <module-path> -m <module>/<class>`):
  - `software.sava.rpc.soak.peer.PeerMain <runDir>` — the peer JVM.
  - `software.sava.rpc.soak.Main <runDir>` — the client JVM (the subject lives here).
  - `software.sava.rpc.soak.report.SoakReport <runDir> <recording.jfr|->` — post-processing.
  - `software.sava.rpc.soak.selftest.SelfTest` — attribution and detector self-test.
- Both JVMs read `<runDir>/run.env` (`KEY=VALUE` lines, `SOAK_*` keys only; an unknown
  `SOAK_*` key is a startup error in the client, which also overlays the process environment
  and rejects unknown `SOAK_*` keys there — except the runner's own three switches
  `SOAK_DRY_RUN`, `SOAK_SKIP_BUILD` and `SOAK_NO_LOCK`, which `soak.sh --controls` exports into
  every child and which are skipped rather than rejected). The peer reads the same file with its
  own parser and ignores keys it does not use. `soak.sh` writes `run.env` with every resolved
  value before starting the peer.
- `assertNoSrcDir` exists and `soakModulePath` depends on it.

## 3. Run directory and artifacts (`soak/build/runs/<profile>-<yyyyMMdd-HHmmss>/`)

| path | writer | format |
|---|---|---|
| `run.env` | soak.sh | `KEY=VALUE`, first line `# <exact command line>` |
| `run-exit.env` | soak.sh | `STARTED_AT`, `FINISHED_AT`, `CLIENT_EXIT`, `PEER_EXIT`, written when the run's processes have exited, so `--report` reprints the run's own facts instead of inventing them |
| `run.json` | soak.sh | identity: git rev + dirty flag, JDK path/version, GC, jfc SHA-256, schedule SHA-256, module path, controls run id |
| `run-client.json` | Main | the client's half: subject/consumer code sources, subject validity, JDK, GC, pid, the timings actually used, every resolved config key (written beside `run.json` rather than merged into a file another process owns) |
| `status` | soak.sh | one word: `RUNNING`, `PASS`, `PASS-WITH-FINDING`, `FAIL`, `INCOMPLETE`, `INVALID` |
| `.running` | soak.sh | present until `summary.md` is complete |
| `config/sava-soak.jfc`, `config/soak-logging.properties` | soak.sh (copied) | the exact settings used |
| `logs/peer.log`, `logs/client.log` | JVM stdout+stderr | text |
| `logs/jvm-stdout.log` | first 200 lines of the client JVM's stdout, grepped for `[warning][jfr,` | text |
| `logs/jfr-methodtrace.log` | `-Xlog:jfr+methodtrace=debug:file=` | text |
| `jfr-repo/` | JVM | chunk repository |
| `jfr/soak-steady.jfr`, `jfr/soak-final.jfr`, `jfr/soak-exit.jfr` | jcmd / dumponexit | recordings |
| `views/<elapsed-s>.txt` | soak.sh (`jcmd JFR.view`) | text |
| `fault-schedule.tsv` | PeerMain (written before `PEER_READY` is printed) | §6, header row |
| `faults-applied.tsv` | PeerMain | §6, header row |
| `peer-ws.tsv`, `peer-http.tsv` | PeerMain | §7, no header: the first line is the `anchor` row |
| `peer-summary.json` | PeerMain at shutdown | totals |
| `client.csv` | Main (GaugeSampler) | §9 |
| `counters.properties` | Main (end + shutdown hook) | `key=value` |
| `properties.tsv` | Main (each phase boundary + end) | §8 |
| `phases.tsv` | Main | `epochMillis\tname\tindex`, headerless; the terminal row is `SHUTDOWN` or `ABORTED` |
| `issue52/retirements.tsv`, `issue52/claims.tsv` | Main | §10 |
| `findings/finding-NN/note.md` | Main | one per failed property, with examples |
| `rss.csv`, `nmt.csv`, `nmt/*.log` | soak.sh | as http-servers-soak |
| `jfr-summary.txt`, `jfr-views.txt` | soak.sh (`jfr summary`, `jfr view`) | text |
| `jfr-report.md`, `jfr-metrics.properties`, `issue52.md` | SoakReport | §11 |
| `summary.md` | soak.sh | verdict + numbers |

## 4. Packages and ownership

| package (under `software.sava.rpc.soak`) | owner stage | contents |
|---|---|---|
| `` (root) | core | `Main`, `SoakConfig`, `Profile`, `Phases`, `Phase`, `Seeds`, `Counters`, `LatencyHistogram`, `JfrCounters`, `GaugeSampler`, `RunIdentity`, `Redaction`, `PeerAdmin` (harness-side client for `/__soak/*`), `Tsv` (append-only writers), plus the driver seam: `Workload` (the interface every driver implements), `Workloads` (the one place `Main` learns which drivers a run has) and `SoakContext` (everything a driver needs and nothing it should own) |
| `events` | core | `SoakEvents` — every `sava.soak.*` event class except the issue52 ones (§12) |
| `oracle` | core | `Grade`, `Property`, `PropertyLedger`, `SequenceOracle`, `RecoveryLedger`, `NonAssertions`, `Properties` (the fixed property table §8) |
| `peer` | peer | `PeerMain`, `WsPeer`, `WsFrames`, `WsConnection`, `RpcPeer`, `FaultKind`, `Fault`, `FaultSchedule`, `PeerLog`, `Payloads`, `Stamp`, `Dialect`, `IdPolicy` |
| `issue52` | issue52 | `AttemptTracker`, `HarnessWebSocketBuilder`, `HarnessBuildFuture`, `HarnessSocket`, `HarnessListener`, `DeliveryFrames`, `RecordingBackoff`, `ManagerLogCapture`, `RetirementRecord`, `ClaimRecord`, `RetirementDetector`, `Issue52Summary`, `BackoffClass`, `HarnessBackoffs`, `Issue52Events` |
| `selftest` | issue52 | `FakeWebSocketBuilder`, `FakeWebSocket`, `SelfTest` |
| `ws` | workloads | `WsWorkload`, `EngineProfile`, `SubscriptionPlan`, `ConsumerFactory`, `InternalProbes`, `EngineHandle` |
| `http` | workloads | `HttpWorkload`, `RpcOracle`, `SoakRawHttpClient` |
| `churn` | workloads | `ChurnWorkload` |
| `control` | workloads | `HarnessControls` (D8–D11, D13, D14 injections) |
| `report` | report | `SoakReport`, `JfrPass`, `Issue52Report`, `Slopes`, `Metrics`, `TsvReader` |

Files outside Java: `soak.sh`, `controls.tsv`, `config/sava-soak.jfc`,
`config/sava-soak-validate.jfc`, `config/soak-logging.properties`,
`config/soak-logging-validate.properties` (the validate profile logs the engine at FINE),
`README.md`, this file.

Wiring, as `Workloads.create(ctx)` performs it and in the order `Phases` quiesces and drains:
`control.HarnessControls.of(ctx)` first when `SOAK_CONTROL` names a harness-side control
(D8–D11, D13, D14; null otherwise), then `ws.WsWorkload` unless D11 suppresses it, then
`http.HttpWorkload.create(ctx)`, then `churn.ChurnWorkload`. Each driver registers its own
`GaugeSampler.GaugeSource` inside `start`. `Main` installs `issue52.ManagerLogCapture` before
any driver is built and runs `issue52.RetirementDetector.selfTest()` as a STARTUP gate.

## 5. Configuration (`SoakConfig`)

Loaded from `<runDir>/run.env`, then overridden by the process environment (same names). Every
value is a typed field with a per-profile default; `Profile` ∈ {`validate`, `pilot`,
`campaign`, `control`, `live`}. `Profile.pick` resolves `control` to the **validate** column and
`live` to pilot's, and `soak.sh`'s `by_profile` table does the same, key for key: a control is the
negative of the validation it guards, so it is evidence about that run only when it was measured
under that run's knobs. The runner applies no control-specific shortening of its own either — a
control row is a validate run plus the row's own overrides column (§14). The full knob table with
the validate/pilot/campaign defaults:

```
SOAK_PROFILE                      validate | pilot | campaign | control | live
SOAK_RUN_DIR                      absolute path (soak.sh sets it)
SOAK_DURATION_SECONDS             240 / 1800 / 14400        whole run incl. warm-up, excl. quiesce/drain;
                                                            the release check is the campaign profile at
                                                            28800 (the after-GC gate's leak floor halves)
SOAK_WARMUP_SECONDS               30 / 360 / 900
SOAK_QUIESCE_SECONDS              30 / 120 / 300
SOAK_DRAIN_SECONDS                30 / 60 / 60
SOAK_SEED                         0x5A7A50A40001 (long, hex or decimal)
SOAK_WS_ENGINES                   4 (fixed set: EXPONENTIAL, ZERO_INITIAL_ESCALATING, BARE, OVERFLOW)
SOAK_WS_SUBSCRIPTIONS             24 / 64 / 128             per engine cap
SOAK_WS_CHURN_PER_MINUTE          120 / 60 / 60
SOAK_WS_NOTIFY_RPS                200 / 200 / 400           per connection, enforced by the peer
SOAK_HTTP_CONCURRENCY             8 / 16 / 16               platform worker threads
SOAK_HTTP_INFLIGHT                4                         futures per worker (semaphore)
SOAK_HTTP_RPS                     20 / 40 / 40              token bucket, whole workload
SOAK_LARGE_FRACTION               0.10 / 0.05 / 0.05
SOAK_NOWRAP_FRACTION              0.05
SOAK_CANCEL_FRACTION              0.02
SOAK_FAULT_RATE                   20 / 120 / 300            mean ops between faults; 0 is legal
                                                            and means no scheduled faults at all
SOAK_FAULT_MIN_PER_KIND           1 / 3 / 8                 0 = no guaranteed instance per kind
SOAK_DESTRUCTIVE_PER_HOUR         240 / 60 / 30            also sizes the conn trigger domain (§6)
SOAK_STORM_SECONDS                20 / 60 / 60              one storm per hour, 1-in-5 rate
SOAK_CHURN_PERIOD_SECONDS         15 / 15 / 30
SOAK_HEAP_MB                      512 / 1024 / 1024         -Xms = -Xmx
SOAK_JVM_EXTRA                    empty on every profile    extra arguments appended to the CLIENT
                                                            JVM's command line, split on whitespace;
                                                            a per-row measurement aid (D10), never
                                                            campaign tuning
SOAK_JFR_MAXSIZE                  256m per hour of run     derived from the duration unless set: the
SOAK_JFR_MAXAGE                   hours + 1                 ring must outlast the run (1g/4h covered
                                                            94 % of an 8-h campaign)
SOAK_NMT_INTERVAL_SECONDS         60 / 600 / 1800
SOAK_SAMPLE_SECONDS               10 / 30 / 30              rss.csv
SOAK_GAUGE_SECONDS                10                        client.csv + sava.soak.Gauge
SOAK_THREAD_MARGIN                24 / 16 / 16
SOAK_FD_MARGIN                    96 / 64 / 64
SOAK_VTHREAD_MARGIN               64 / 32 / 32
SOAK_SKIP_LIMIT_PCT               10 / 5 / 5                harness starvation -> INVALID
SOAK_RSS_SLOPE_KIB_PER_HOUR       4096 / 2048 / 2048
SOAK_RSS_NOISE_FLOOR_KIB          65536 / 8192 / 8192
SOAK_HEAP_FLOOR_SLOPE_KIB_PER_HOUR 1024
SOAK_HEAP_FLOOR_NOISE_KIB         16384 / 8192 / 8192      the after-GC heap floor's own noise floor (RSS's carries start-up drift the heap does not)
SOAK_PING_DELAY_MS                4000 / 15000 / 15000      validate shortens; sava default otherwise
SOAK_CHECK_DELAY_MS               1000 / 2000 / 2000
SOAK_HTTP_CLIENT_EXECUTOR         platform | virtual        default platform, 8 named threads
SOAK_SOCKET_THRESHOLD_MS          0 / 20 / 20               only informs which .jfc soak.sh copies
SOAK_WS_PORTS                     comma list, written by soak.sh after PeerMain prints them
SOAK_HTTP_PORT                    written by soak.sh after PeerMain prints it
SOAK_CONTROL                      empty, or a control id from controls.tsv
SOAK_MANAGER_REFLECT              0 | 1                     self-test only
SOAK_LIVE                         0 | 1
SOAK_LIVE_HTTP_URL, SOAK_LIVE_WS_URL, SOAK_LIVE_CLUSTER (local|devnet|testnet|mainnet),
SOAK_LIVE_CONFIRM (must equal "mainnet" for mainnet), SOAK_LIVE_RPS, SOAK_LIVE_CONCURRENCY,
SOAK_LIVE_ACCOUNTS (file of base58 keys)   all REQUIRED when SOAK_LIVE=1, no defaults
```

A live endpoint carries its credential in the URL, so the two URL keys are the one place the
record and the run differ: the JVMs take the real URLs from the process environment (which
`soak.sh` exports and `SoakConfig` overlays on `run.env`), while `run.env`, `run-client.json`
and `SoakConfig.toRunEnv()` keep `scheme://host` only, the shape `Redaction.endpoint` gives the
reports. Measured 2026-09-23: the first Helius run wrote the key into both files verbatim while
every report, log and recording was clean. A live run whose artifacts (text files and the
recordings) carry a URL credential is INVALID (`assert_no_secret_leak` in `soak.sh`), never a
note. The same runs settled three more live rules: every websocket engine dials
`SOAK_LIVE_WS_URL` (the port that keys peer-row joins is the profile's index); `W1-E` is not
evaluated, because the sequence it judges is the controlled peer's stamp and a real node's
field there is the slot; the plan's generic channel (`transactionSubscribe`, a method no real
node has) is skipped and counted; and `pending_confirm` reads the engine's own grants
(`Subscription.subId()`); the population every driver draws from is `SOAK_LIVE_ACCOUNTS` — the
HTTP driver samples the whole list, and the websocket and churn drivers index the byte-sized
table it is cycled into (its first 256 entries when the list is longer), so the subscriptions
target accounts that exist rather than a seeded table nobody funded (a list past 256 used to lose
its tail for HTTP too; review). The first three live runs predate that swap, so nothing they say
about account-channel deliveries is evidence: their engines subscribed to seeded keys, which is
why every churn cycle missed its notification latch and W2-A was never reached; the first run on
the supplied list (Helius, two accounts) delivered clock-sysvar notifications on the account
channel with W2-A stated as not evaluated; and `W1-A` renders no verdict, since its
evidence is the peer's re-send rows and nothing else clears a replay set. No `X-Soak-*` header
is sent, from any driver: the churn driver's copy of the probe marker was sending one until the
rule was made a single shared method (review).

Peer-side knobs (read by PeerMain from the same file): `SOAK_PEER_HTTP_THREADS` 16,
`SOAK_BLOCK_TXS` 400 (large 4000), `SOAK_PGA_ACCOUNTS` 500 (large 20000), `SOAK_BURST_FRAMES`
256, `SOAK_BURST_PERIOD_SECONDS` 120, `SOAK_LARGE_PERIOD_SECONDS` 300, `SOAK_LARGE_BYTES`
8388608, `SOAK_FRAGMENTS` 37, `SOAK_REFUSE_SECONDS` 20, `SOAK_SLOW_CONSUMER_MICROS` 2000,
`SOAK_THROW_EVERY` 7, `SOAK_WS_PORT_COUNT` 5.

## 6. Fault schedule (peer-owned, ordinal-keyed)

`FaultKind` (enum, `scope()` ∈ {CONN, SUB, NOTIFY, RPC}, `destructive()`, `expectedRetirement()`):

Websocket transport (scope CONN): `TCP_RESET`, `HALF_CLOSE`, `CLOSE_FRAME`, `HANDSHAKE_500`,
`HANDSHAKE_STALL`, `HANDSHAKE_BAD_ACCEPT`, `HANDSHAKE_RST`, `SLOW_WRITE`, `CONNECT_REFUSE`.
Websocket protocol (scope SUB or NOTIFY): `SWALLOW_SUBSCRIBE`, `DELAY_CONFIRM`,
`REJECT_SUBSCRIBE`, `DUPLICATE_CONFIRMATION`, `SWALLOW_UNSUB`, `REFUSE_UNSUB`,
`ZOMBIE_NOTIFICATION`, `UNKNOWN_SUB_NOTIFICATION`, `SWALLOW_PING`, `DANGLING_FRAGMENT`,
`MESSAGE_OVERFLOW`, `ERROR_RESPONSE`, `DUP_SUBID`.
HTTP (scope RPC): `STALL_BODY`, `DELAY_RESPONSE`, `HTTP_429`, `HTTP_503`, `RPC_ERROR`,
`TRUNCATE_BODY`, `BAD_GZIP`, `GZIP_TRAILING`, `BAD_CONTENT_LENGTH`, `CONNECTION_DROP`,
`CHUNKED_SLOW`, `WRONG_ID_ENVELOPE`.
Control-only (never in the weighted fill; selected by `SOAK_CONTROL`): `CROSSTALK`,
`SUBID_REUSE_ACROSS_PARAMS`, `WRONG_ID_ECHO`, `WRONG_KEY_ECHO`, `DROP_REPLAYED_SUBSCRIBES`,
`DUPLICATE_NOTIFY`, `SWAP_ADJACENT_NOTIFY`, `FLIP_CONTINUATION_BYTE`, `DEDUPE_BREAK`,
`GZIP_WRONG_TWIN` (scope RPC), `NEVER_APPLY_KIND`.

`destructive()` and `expectedRetirement()` are different questions and deliberately not the
same set. `destructive()` is what `SOAK_DESTRUCTIVE_PER_HOUR` pays for — it bounds how many
socket-ending faults the schedule may draw — and `expectedRetirement()` is what the client
subtracts from the unexplained-retirement gate.

Destructive: `TCP_RESET`, `HALF_CLOSE`, `CLOSE_FRAME`, every `HANDSHAKE_*`, `CONNECT_REFUSE`,
`SWALLOW_PING`, `SWALLOW_SUBSCRIBE`, `DUP_SUBID`, `DANGLING_FRAGMENT`, `MESSAGE_OVERFLOW`, and
the control-only `SUBID_REUSE_ACROSS_PARAMS`.

Expected retirement: every destructive kind above, **plus `SWALLOW_UNSUB`**, which is not
destructive. The peer never touches that connection's transport, but an unanswered unsubscribe
is an unanswered *request*: the engine escalates one at four resend delays by aborting the
connection, the same deadline and the same outcome as an unanswered subscribe, and while the
flag was false every such retirement was charged to the client as unexplained. Raising
`destructive()` instead would have made the per-hour cap pay for a kind that breaks no socket
and thinned the kinds that do out of every plan. `SLOW_WRITE`, `CROSSTALK` and
`DROP_REPLAYED_SUBSCRIBES` are the other direction: they disturb a connection without ending one,
so neither flag is set. The last of those carried both while it swallowed the replay outright and
the engine escalated the silence into an abort; it answers on the wire instead (below) and retires
nothing, and a name left in either list would excuse a retirement nobody asked for.

`DANGLING_FRAGMENT` is on both lists on measurement: `java.net.http.WebSocket`'s own reader
rejects a TEXT frame that follows an unfinished one with a `ProtocolException` and closes the
transport, so over a real transport the fault lands below sava. `SWALLOW_PING` has scope `CONN`
(a ping is neither a subscribe nor a notification, so the nth accepted connection is its only reproducible
trigger) and stages *silence*: the engine pings only into peer silence and, by
`SolanaRpcWebsocket.Builder.pingDelay`'s contract, any peer frame answers an outstanding probe, so
the peer holds its notifications and pongs to draw the ping, swallows it, and then holds
everything through a second window (`pingDelay + checkDelay + 2 s`) so the probe can only time
out; held frames queue rather than drop (a pause, not a gap). Phase one still answers requests:
an ack held back before the engine has pinged is an unanswered request to it, which replaced a
connection after its resend windows on a pilot before it had any reason to ping. An answer is
peer contact, so the window starts again behind each one — the engine's own silence clock does
the same — up to a budget of four windows fixed when the fault is armed; a client that answers a
request at least once per window for the whole budget writes the row `no_target`. Phase two holds
the answers too, because any frame would discharge the probe; a request sent into it is starved
for at most one window, and the probe's own deadline retires the connection first unless the
window is longer than the engine's unanswered-request deadline (four resend delays: the pilot's
15 s `pingDelay` against 12 s), where that detector can fire first. Either way the connection is
replaced and reported inside W1-H(ii)'s bound; which detector did it is counted
(`ws.harness.w1hProbeRetirements` / `w1hOtherRetirements`, exported as
`swallowed_ping_probe_retirements` / `swallowed_ping_other_retirements`), not judged. A contact
after the swallow — only a close can be one now — makes the client's W1-H(ii) check
contact-confounded (`ws.harness.w1hContactAfterSwallow`) rather than a verdict. Measured
2026-09-22 before the silence existed: the engine re-pinged eight seconds after the swallowed one
and was answered, and nothing retired. Measured the same day with a fixed phase-one window: every
workload engine wrote `no_target`, because the window ran from the handshake, which is when the
engine replays its subscriptions and is answered; only the churn port's idle engines pinged. `SWALLOW_UNSUB`/`REFUSE_UNSUB` are armed at a `sub` ordinal and applied to the next
unsubscribe on that connection; a connection that ends first writes them `no_target`. The same
armed-then-drained rule holds for the transport kinds, `SWALLOW_PING` and `SLOW_WRITE`: each is
armed on its connection and, if the connection ends before the client pings, writes or is reset,
the single armed reference is drained exactly once as `no_target` naming what never arrived.
`SWAP_ADJACENT_NOTIFY` holds one notification until the **same subscription's** next one and
writes the pair in reverse — emission is round-robin across subscriptions, so swapping the two
adjacent *messages* produced no per-key reorder at all — and the held sequence travels with the
held message.

`MESSAGE_OVERFLOW` lands on every port; only the engine whose `maxMessageLength` is below the
message (the `OVERFLOW` engine) actually overflows, and the client evaluates W2-B and opens a
recovery window only there. The oversized message is an ordinary notification for a live
account or program subscription — its own channel shape, a checksum-valid payload of
`OVERFLOW_CHARS × 3/4` bytes, and it consumes that subscription's next sequence — so on every
other engine it is delivered and verified like any large notification (a padded message with a
bogus payload and an unconsumed sequence was measured failing W2-A and W1-E on engines it
could not overflow).

`MESSAGE_OVERFLOW`'s *guaranteed* instance — the per-kind minimum — is placed on the `OVERFLOW`
engine's own ws port index (clamped to the configured port count), because that is the only port
where the property is evaluated at all: elsewhere the same message is delivered as an ordinary
large notification, so a floor landing there guarantees a delivery and not an overflow. The
weighted fill still places the kind anywhere, so the sentence above stays true of the plan as a
whole; only the floor is pinned, and W2-B therefore has at least one real overflow in every
ordinary run instead of depending on where the port cycle dropped it.

`DEDUPE_BREAK` is a control-only kind (D6): under the agave dialect it grants a fresh id to a
byte-identical repeat subscribe, **and** writes a second `sub_req` row for that request — same
`connId`, same JSON-RPC id, same `sub` ordinal in the `seq` column, detail
`DEDUPE_BREAK duplicate row fp=<16 hex digits>`. The row is the point. W1-B's verdict is that one
registration was *requested* twice on one connection, its evidence is two request rows under one
id, and that is evidence only a re-sending client leaves behind; the fresh id alone moves no
W1-B verdict, which is why the control ran green against a correct client.

`DROP_REPLAYED_SUBSCRIBES` (D1) drops the *record*, not the answer. From the second accepted
connection onward the peer grants a subscription id for the replayed subscribe and sends the
confirmation on the wire, writes neither a `sub_req` nor a `sub_ack` row, and never emits for that
id; nothing
enters the connection's live set, so no later notification can put the missing rows back. The
`faults-applied.tsv` detail is `subscribe <id> answered, never logged, never emitted`. Swallowing
the replay instead was measured never reaching the property the control names: an unanswered
replay is an unanswered *request*, which the engine escalates at four resend delays by aborting
the connection, so the W1-A episode was superseded before W1-A's own bound. A subscription that
is granted, confirmed and then never emitted for is what a client that lost the registration
looks like from the peer's side — the wire looks healthy and the evidence is missing.

`ZOMBIE_NOTIFICATION` is amplification-bounded per retired subId: at most a fixed number of
further notifications for one id (`WsConnection.ZOMBIE_AMPLIFICATION_BOUND`), after which the row
reads `no_target` with `retired subId <n> has drawn its <N> notification(s) after the
acknowledged unsubscribe` and the notification slot goes back to the live subscriptions. The
always-on control gets that sentence once per retired id — at `SOAK_WS_NOTIFY_RPS` one row per
notification would be the whole of `faults-applied.tsv` — while a *scheduled* firing is still owed
its own outcome row at its own ordinal, or the report would read the kind as reached and never
applied. The bound binds the control, not the schedule: a schedule reaches it for one id only if
the client stops unsubscribing. Unbounded, one control run's zombies were almost every
notification it sent, thousands under a single retired id, starving the live subscriptions the
other oracles read.

`STALL_BODY` is rationed: at most a quarter of `SOAK_PEER_HTTP_THREADS` holds may be parked at
once, because a stall occupies a worker for `STALL_HOLD_SECONDS` and an unrationed schedule
parks the whole pool, after which nothing the client sends can be answered and the run measures
the peer rather than the client. A trigger that arrives over budget is **not injected**: it
writes no `faults-applied.tsv` row, so it counts as reached and never applied and tells against
`fault_fidelity`, with `hold skipped: N stall hold(s) already parked` in `peer-http.tsv`'s
detail column.

Two control-only kinds are worth their own sentence because what they do is not guessable from
the name:

- `CROSSTALK` (control `crosstalk`, scope CONN, from the second accepted connection onward):
  on an otherwise unfaulted notification the peer writes, **to this connection**, a notification
  for **one of this connection's own subscriptions** (own subId, own key, own channel) carrying
  the sequence another live connection on the same port has reached on that same channel. The
  receiver's per-`(engine, key, epoch)` oracle therefore sees a DUP or a GAP. Only the sequence
  crosses: writing the other connection's whole notification would put a subId this client was
  never granted on the wire, which is `UNKNOWN_SUB_NOTIFICATION` wearing another fault's name.
  The subscription's own counter is not advanced, so the next ordinary notification still
  carries the sequence the client is owed. Two `no_target` cases: no other live connection on
  the port holds a started subscription of that channel, and the two counters are momentarily
  aligned so the borrowed value is exactly the sequence the receiver is owed next (injecting it
  would change nothing, and fabricating an offset would be the harness inventing a sequence no
  counter holds). Its rows are bounded — see `faults-applied.tsv` below.
- `GZIP_WRONG_TWIN` (control `gzip-wrong-twin`, scope RPC): on a `getAccountInfo` answer that is
  actually served with `Content-Encoding: gzip`, the peer reports `value.space` one above the
  identity representation's and changes nothing else. The stamp still carries `fnv64` of the key
  that was asked for, so W3-A stays clean and the control fails exactly the one property its
  row names: W3-C's plain-versus-gzip projection. It fires only where the peer actually gzips
  (the §7 encoding rotation, three of four gzip-capable requests) — a disagreement between two
  representations does not exist when only one was served.

`fault-schedule.tsv` (header row, tab-separated): `ordinal	kind	scope	port	trigger	params`
where `trigger` is `<counter>=<n>` over the peer's own counters: `conn` (nth accepted
websocket connection on that port), `sub` (nth subscribe request on the port), `notify` (nth
notification written on the port), `rpc` (nth JSON-RPC request), `rpcMethod:<m>` (nth of that
method).

Each counter's **domain** — how far a trigger may be keyed — is derived from the rates the run
configures, except `conn`, which is not a function of the clock: a connection ordinal is reached
only through a *retirement*, and `SOAK_DESTRUCTIVE_PER_HOUR` caps the run's retirements across all
the ws ports. The domain is therefore
`1 + destructivePerHour × durationSeconds / 3600 / ports`, rounded up and floored at 2 — the first
connect, which needs no retirement, plus that port's share of the retirement budget, with the floor
keeping the reconnect ordinal reachable when the budget rounds to nothing (churn and the client's
own escalations still retire occasionally). The old wall-clock estimate of one connection per 30 s
overshot exactly where the cap binds hardest: measured on the 2026-09-21 validate run, seven of the
ten CONN kinds had their guaranteed minimum placed at ordinals no port ever reached, so those kinds
were planned, never reached, and read `UNEXERCISED` as an artefact of the estimate. One consequence
is that at ordinary fault rates the weighted fill's step already exceeds the whole `conn` domain, so
CONN faults come from the per-kind minimums — which is the same reachability the old formula had,
since a fill trigger at an unreachable ordinal was never reached either.

The other domains are upper bounds built from configured ceilings, not forecasts, and a run reaches
only part of them (measured on a 2026-09-22 validate run: the per-port `sub` counter reached
31–77 of an estimated 504, `notify` 41906 of 48000). The per-kind minimums are therefore spread
over the first quarter of each domain (`MINIMUM_PLACEMENT_DIVISOR`) while the weighted fill still
covers the whole of it, so the guaranteed instance of every kind lands where the run will get to
and the campaign's faults are not front-loaded by it.

`FaultSchedule.of(seed, config)` resolves the run's control first, then plans — or
does not. The ordinary plan is three stages: the per-kind minimums at evenly spread ordinals
(each kind offset inside its scope, and a taken slot probed forward over the whole domain
before the kind may be dropped, because every kind of one scope otherwise lands on the same
ordinal and only the port cycle separated them), then a weighted fill at `SOAK_FAULT_RATE`,
then the storm windows (`SOAK_STORM_SECONDS` once per hour) as extra dense ranges keyed on the
`notify`/`rpc` counters. **The destructive cap per hour of expected traffic binds the fill and
the storms alike**, against one shared counter: a storm is a dense window, not a licence to
exceed the run's retirement budget (measured 2026-09-21, storms drawing uncapped produced 1,402
of a plan's 1,410 destructive rows and cost P7 most of its evidence). The minimums are the
floor and the cap never deletes one, because a kind the run never exercised must read as a
finding rather than as an artefact of the cap.

All three stages are conditional:

- `SOAK_FAULT_RATE=0` plans **nothing**, in every stage. It is no longer clamped up to 1, and
  that clamp — which turned the quietest row in `controls.tsv` into the densest schedule the
  harness can build — is the mechanism the `no-faults` control depends on. A rate of 0 also wins
  over an `F*` control's own density: an operator who asked for no faults gets none.
- `SOAK_FAULT_MIN_PER_KIND=0` places no minimums and omits nothing.
- An `F*` control replaces all three stages with a single kind at a stated density, optionally
  on one ws port index and optionally in bursts (see §14), so the row's property is read against
  its own fault and no other.
- The client-side `I1-*` and `I2` controls plan no peer fault at all: their injection is in the
  harness's own seams (§10) and a peer fault would retire the very connections the #52 episode
  is trying to attribute.

An empty plan is still written and still digested: `SCHEDULE_PLANNED=0` beside the SHA-256 of a
file holding only its header row is what makes "nothing was planned" provable, where a missing
file could not be told apart from a peer that failed to write one. The bounds below hold for a
restricted plan too. The file's SHA-256 goes into `run.json`.

`faults-applied.tsv` (header row): `ordinal	kind	scope	port	connId	attemptOrdinal	wallMillis	nanosSinceAnchor	outcome	detail`
with `outcome` ∈ {`applied`, `no_target`}. `attemptOrdinal` is the value of the
`X-Soak-Attempt` upgrade header of the connection the fault hit (`-1` if none). A control
injection (never in the plan) is written with `ordinal` 0 and the counter `control`, so it is
never joined to a scheduled row nor counted in schedule fidelity. `CROSSTALK`'s rows are
bounded rather than one per injection — one `applied` row for the first injection naming the
source connection, subId and whether it is a duplicate or a gap, one summary `applied` row at
connection end when more than one injection happened, and one `no_target` row — with the
per-message evidence in `peer-ws.tsv`. At `SOAK_WS_NOTIFY_RPS` a row per notification would
spend the bounded peer-log queue on one repeated sentence and drop the `notify` rows the
oracles read.

Fidelity = applied / (reached − no_target) and must be ≥ 0.90, where a planned fault is
*reached* when the peer's final counter for its `(counter, port)` domain (`peer-summary.json`)
passed its trigger, or when an outcome row exists for it. A planned CONN fault is spent only
after a successful `accept()` — `CONNECT_REFUSE` excepted, which stays a pre-accept decision —
because `take` removes the entry and a rebind-induced accept failure (the `CONNECT_REFUSE`
window is exactly one) otherwise consumed a planned fault permanently while the report still
counted its trigger reached. One still held when the peer shuts down is reported
`fault_unreached`, which is what it is.

The control-injection seam has two forms. `always(scope, connOrdinal)` is the websocket form and
honours the control's `minConnOrdinal` gate: `WsPeer`'s accept loop consults it for CONN with
the ordinal it is about to assign, beside its `take("conn", …)` lookup, and `WsConnection`
consults it for SUB/NOTIFY and for a CONN kind that acts on an established connection
(`CROSSTALK`) with its own `connId`. `always(scope)` is the connection-independent form for
RPC-scope callers — the gate left out rather than faked with a number, since the gate exists for
controls that need a *reconnect* and an HTTP request has no generation to wait for. A control
kind is returned on **every** eligible event, not once per run: a differential or crosstalk
oracle handed one instance would prove only that it can fire once. A control that pre-empts a
scheduled fault at the same ordinal records that fault `no_target` with
`pre-empted by control <KIND>`, so the fidelity denominator stays honest.

The plan is sized from the configured duration and the expected rates before the run starts, so
a shortened run leaves its tail unreached; that is reported as
`fault_unreached` and as "planned but never reached" per kind, never as injection failure. A
kind that was reached and never applied is `fault_kinds_unapplied`, which fails the run (and is
what control D12 must trip); the report and the runner both render that set as one **bracketed
token per kind** (`[SWALLOW_PING],[DUP_SUBID]`), which is what gives a control row an exact
needle. A request carrying `X-Soak-Probe` is never given a scheduled fault (§7): the trigger is
consumed and written `no_target` with `probe: X-Soak-Probe request, not subject traffic`, so it
is reached, deliberately not injected, and subtracted from fidelity's denominator rather than
left armed at an ordinal the counter has already passed. Two bounds not in the original plan:
at most 1,000 faults per `(counter, port)` domain and 20,000 planned overall; when the bound
binds the effective interval widens.

## 7. Peer wire contract (what the client can verify)

Websocket peer (`WsPeer`, one instance per port, RFC 6455 text frames only, client frames
must be masked, server frames unmasked, 16 MiB frame cap): dialect per port is
`agave` for even ports in `SOAK_WS_PORTS` order and `helius` for odd ports, fixed for the run.
- Subscribe: `{"jsonrpc":"2.0","result":<subId>,"id":<msgId>}`. Id policy: `agave` → the same
  subId for byte-identical `method+params` on one connection (dedupe) and one unsubscribe
  cancels it; `helius` → a fresh id every time.
- Unsubscribe: `{"jsonrpc":"2.0","result":true,"id":<msgId>}`; unknown id → `agave`
  `{"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid subscription id."},"id":<msgId>}`,
  `helius` `{"jsonrpc":"2.0","result":false,"id":<msgId>}`.
- Unknown method → `-32601`; malformed params → `-32602`; `REJECT_SUBSCRIBE` → `-32603
  "Subscription refused"`.
- The upgrade request's `X-Soak-Attempt` header (client-side attempt ordinal) is recorded per
  connection; absent in live mode.
- Notifications are emitted by one `ws-emit` scheduler per port at `SOAK_WS_NOTIFY_RPS` per
  connection, round-robin over live subscriptions; the per-`(connId, subId)` sequence is
  assigned under the connection's write lock immediately before the bytes are written
  (write-time assignment), then logged. Sequences start at 1 per `(connId, subId)`. A
  subscription goes live with its confirmation, never before it: under `DELAY_CONFIRM` the
  stream starts when the deferred confirmation is sent, as a real node's would (emitting first
  was measured making the engine kill the id as unknown and re-subscribe on confirmation, which
  is `UNKNOWN_SUB_NOTIFICATION` wearing another fault's name).
- Threads: `peer-ws-accept-<port>`, one `peer-ws-conn-<n>` reader and one `peer-ws-out-<n>`
  writer per connection (the bounded outbound queue needs a drainer that is neither the reader
  nor the shared emit scheduler), `peer-ws-emit-<port>`, `peer-http-<n>`, `soak-peer-log`.
- That queue's bound is sized past the longest hold the peer can impose on *itself* — see the
  constant, never a literal here: `SLOW_WRITE`'s per-fragment gap multiplied by `SOAK_FRAGMENTS` is
  seconds per message, against the highest `SOAK_WS_NOTIFY_RPS` any profile drives, plus a
  `SOAK_BURST_FRAMES` burst and margin. Under the old bound the 2026-09-21 campaign turned exactly
  that hold into a full queue and a 1011 `overflow` close on the port carrying a `SLOW_WRITE`, on a
  connection the catalogue says that fault does not end — and the client charged the retirement to
  itself as unexplained, taking W1-F's containment check down with it. The queue holds *intents*, so
  the largest notification a run can send is never in it (it is built under the write lock) and a
  full queue costs well under a mebibyte per connection.
- Every connection the peer **accepts and does not upgrade** writes a terminal `abort` row when its
  socket closes, detail `<FAULT>: upgrade never completed, socket closed`. It covers
  `HANDSHAKE_500`, `HANDSHAKE_RST`, `HANDSHAKE_STALL` (at the end of its hold, when the socket
  actually closes, not at its start), `HANDSHAKE_BAD_ACCEPT` and a request with no
  `Sec-WebSocket-Key`. `abort` and not `close_out`, because none of those paths sends a close frame
  and a peer must not claim a courtesy it never extended. Without the row the client's view of that
  connId stayed open for the rest of the run, and the oracles went on asking about a connection the
  peer had already dropped. `CONNECT_REFUSE` owes none: its rows carry `connId` -1 and are
  listener-level, never a connection. Relatedly, the close path writes no websocket close frame
  before the upgrade, so a shutdown that lands on a stalling handshake cannot put websocket bytes on
  an HTTP socket.

Stamps (`Stamp`, shared by both peers; the client-side `RpcOracle`/`SequenceOracle` reimplement
the same functions in the harness): `fnv64(bytes)` = FNV-1a 64-bit; `BASE_SLOT = 10_000_000`.

| channel | where the sequence is | other verifiable fields |
|---|---|---|
| `accountNotification` | `value.lamports = seq`; `value.data[0]` = base64 of `payload(seq, subId, size)` | `context.slot = BASE_SLOT + seq`; `value.owner` = `Stamp.OWNER` (fixed base58) |
| `programNotification` | as account, plus `value.pubkey` = the program key the subscription was granted with, echoed from the request's params (never re-derived; `Stamp.derivedKey` is the HTTP `getProgramAccounts` rule) — this is W1-J's oracle and what `WRONG_KEY_ECHO` corrupts | |
| `slotNotification` | `result.slot = BASE_SLOT + seq`, `parent = slot - 1`, `root = slot - 32` | |
| `rootNotification` | `result = BASE_SLOT + seq` | |
| `logsNotification` | `context.slot = BASE_SLOT + seq`; `value.logs[0] = "soak seq=<seq>"` | `value.signature` = base58 of `sha256("logs:" + seq) ‖ sha256("logs:" + seq + ":hi")` (64 bytes; SHA-256 yields 32, and a repeated half would make two sequences share half a signature) |
| `signatureNotification` | `context.slot = BASE_SLOT + seq`; `value` = `"receivedSignature"` then `{"err":null}` (terminal) | |
| `transactionNotification` (generic, Helius shape) | `result.slot = BASE_SLOT + seq`, `result.signature` as logs | |

`payload(seq, subId, size)` = 8 bytes seq (big-endian) + 8 bytes `fnv64(filler)` + filler,
where filler = `size − 16` bytes generated by `SplittableRandom(seed ^ subId ^ seq)`; `size`
is 64 by default and `SOAK_LARGE_BYTES` for the LARGE emission (split into
`SOAK_FRAGMENTS` continuation frames at seeded split points). The client verifies the payload
on **every consumer kind** for the three payload-bearing channels (`accountNotification`,
`programNotification` and the keyed-program shape), and never for the other five, which carry
no payload: a per-key consumer behaviour must not be able to decide which notifications get
checked. The check is two-sided — the filler checksum, the payload's own big-endian sequence
header and `value.space` against the notification the bytes arrived in — so a body replaced
wholesale by another sequence's, or one that base64-decodes shorter than `space` announced, is
a failure and not a pass. An absent or short payload on a payload-bearing channel is a failure
rather than a skip.

HTTP peer (`RpcPeer`, `com.sun.net.httpserver`, HTTP/1.1, fixed pool `SOAK_PEER_HTTP_THREADS`,
threads + 256 exchanges outstanding, counted from submission to the pool until the handler
returns — counting only once a worker had picked an exchange up let the pool's own queue absorb
everything and made the bound unreachable (review) — overflow → 503 recorded as
`peer_overflow`): responses are pure functions of the
request. Account stamp (32 bytes, base64 in `value.data[0]`): `[0..8) requestId`,
`[8..16) fnv64(pubkey base58 bytes)`, `[16..24) peer rpc seq`, `[24..32) fnv64(bytes[0..24))`;
`value.owner = Stamp.OWNER`, `value.lamports = seq`, `context.slot = BASE_SLOT + seq`.

| method | association check (W3-A) |
|---|---|
| `getAccountInfo(key)` | stamp `fnv64(key)` equals the key asked for; checksum |
| `getMultipleAccounts(keys)` / `getAccounts` | each entry's stamp matches the key at that index |
| `getProgramAccounts(program)` | N accounts, `pubkey_i = Stamp.derivedKey(program, i)`, stamp fnv of `program` |
| `getBlock(slot)` | `blockHeight == slot`, `parentSlot == slot − 1`; transactions shaped from `rpc_response_data/getBlock.json.zip` sized `SOAK_BLOCK_TXS` |
| `getSignatureStatuses(sigs)` | `slot_i = BASE_SLOT + (fnv64(sig_i) & 0xFFFFF)` |
| `getLatestBlockHash`, `getSlot`, `getBlockHeight`, `getEpochInfo`, `getVersion`, `getHealth` | N/A (rate and no-wedge only); `getSlot` returns `BASE_SLOT + seq` |

Encoding rotation when the request carries `Accept-Encoding: gzip`: cycle `gzip`, `x-gzip`,
`identity, gzip`, plain (no encoding) by rpc seq mod 4; `GZIP_TRAILING` is observation-only.
Every response carries `X-Soak-Seq: <seq>`. `value.space` is the stamp's own length (32) —
except under the `gzip-wrong-twin` control, where the gzip representation reports 33 and the
identity twin still reports 32.

The harness marks its own liveness and survival probes `X-Soak-Probe: 1` (any non-blank value
counts; the HTTP counterpart of the websocket `X-Soak-Attempt` header, and like it a header a
live run should not be sending to somebody else's node — see §14's open decision). The senders
are W3-D's wedge probes, `HttpWorkload`'s end-of-drain W5-B1 probe and every request the churn
driver's RPC clients make. The peer neither faults nor control-injects such a request: a probe
is the harness measuring the subject, not the subject's workload, and an injected fault would
answer a question nobody asked. The trigger is still consumed and recorded `no_target` (§6), and
every row a probe produces in `peer-http.tsv` carries `probe` in its detail column, joined with
`; ` to whatever else that row has to say so one reason never overwrites another.

Admin routes on the HTTP port, only for `soak.sh`/`PeerAdmin`: `GET /__soak/stats` (JSON
totals: `httpPort`, `rpcRequests`, `accountInfoRequests` (the `rpcMethod:getAccountInfo`
trigger counter), `peerOverflow`, `logDropped`, `quiesced`, and per ws port `port`, `dialect`,
`idPolicy`, `acceptedConnections`, `openConnections`, `liveSubscriptions`,
`subscribeRequests`, `notifications` — the last three being the `conn`/`sub`/`notify` trigger
counters the report uses to decide which planned faults were reached),
`POST /__soak/quiesce` (stop emitting, keep answering), `POST /__soak/flush`,
`GET /__soak/capture?port=<p>&conn=<id>` (last 64 in/out frames, hex), `POST /__soak/shutdown`.

Peer logs (append-only, one line per event, flushed every 100 lines or 1 s):
`peer-ws.tsv`: `wallMillis	nanosSinceAnchor	port	kind	connId	attemptOrdinal	subId	msgId	seq	bytes	detail`
with kinds `anchor`, `accept`, `handshake`, `sub_req`, `sub_ack`, `sub_err`, `unsub_req`,
`unsub_ack`, `unsub_err`, `notify`, `ping_in`, `pong_out`, `close_in`, `close_out`, `abort`,
`error`, `overflow`. A `sub_req` row's `seq` column carries the port's `sub` counter (the
schedule's trigger ordinal) and its detail ends ` fp=<16 hex digits>`, the `fnv64` of
`method + '|' + params` taken from the raw request text — the dialect's own dedupe key. That
token is an interface between the two processes, like the `unsub_req` detail below: W1-B keys
on it, because a re-send under a fresh JSON-RPC id is invisible to a join on the id. It follows
the method name, or `swallowed <kind>` when the request was swallowed. A `sub_err` or
`unsub_err` row's detail carries `code=<n>`, the JSON-RPC error code the peer answered with (a
peer refusal of an unknown method adds ` unknown method <name>`); the client's request-defect
decision (§8, W1-A) reads that token. An `unsub_req` row's
detail is `<method> subMsgId=<n>` naming the
subscribe that granted the id, or `<method> stale` when the id was not live — that is how the
client joins a cancellation to its registration when the confirmation was delayed. A `notify`
row's detail is the fault kind applied to that notification, or empty; a `no_target` fault
leaves it empty (the row is an ordinary notification). Under the `crosstalk` control the
diverted notification's row carries `CROSSTALK from connId=<n> subId=<m>`, which is the
per-message evidence the client joins against the bounded `faults-applied.tsv` rows.

Two more rows of that log are a cross-process interface in their own right. An `overflow` row is
what a 1011 close and the retirement behind it are attributed to, so a retirement standing next to
one is the peer's doing and not an unexplained client defect; and an `abort` row naming a
`HANDSHAKE_*` fault is how a connection the peer accepted and never upgraded closes in the client's
view of the port. *Absence* is interface too: `DROP_REPLAYED_SUBSCRIBES`' confirmation is the one
message the peer puts on the wire without logging, and the missing `sub_req`/`sub_ack` rows are the
staged defect W1-A reads. `peer-http.tsv`: `wallMillis	nanosSinceAnchor	method	requestId	seq	status	bytes	encoding	delayMillis	faultKind	detail`.
The first line of each is the `anchor` row carrying `System.nanoTime()` at start so client
and peer nanos can be joined at millisecond granularity (never finer across JVMs).

Determinism self-check at PeerMain start: build the first 64 responses of each RPC method and
the first 64 notifications of each channel twice from two fresh instances and compare bytes;
a mismatch prints `PEER_NONDETERMINISTIC` and exits 4.

PeerMain prints, once ready: `WS_PORTS=<p1>,<p2>,...`, `HTTP_PORT=<p>`, `SCHEDULE_SHA256=`
(the file as written), `SCHEDULE_CANONICAL_SHA256=` (the same rows with the port *index* in
place of the ephemeral port, stable across runs at one seed), `SCHEDULE_PLANNED=<n>`,
`SCHEDULE_OMITTED=<kinds>` (a kind that was **wanted and could not be placed**: its counter
domain was smaller than `SOAK_FAULT_MIN_PER_KIND`, or the domain was already fully taken by
other kinds' minimums — so it is empty by construction for a plan emptied by
`SOAK_FAULT_RATE=0`, restricted by an `F*` control, or skipped by a client-side control, where
the other kinds were never wanted), then `PEER_READY`. All four schedule lines are printed for
an empty plan too, which is the contract that makes "nothing was planned" provable. It handles SIGTERM by flushing the logs, writing
`peer-summary.json` (the schedule digests, `faultsPlanned`, `faultsRemaining`, `omittedKinds`,
`logDropped`, then the `/__soak/stats` body), and exiting 0.

## 8. Correctness ledger (`oracle`)

```java
enum Grade { A_PUBLIC_CONTRACT, B_DOCUMENTED_INVARIANT, C_PEER_ESTABLISHED, D_MEASUREMENT, NONE }
record Property(String id, Grade grade, String statement, String guarantee, String bound) {}
final class PropertyLedger {  // thread-safe
  void pass(String id);                       // evaluated++, passed++
  void fail(String id, String example);       // evaluated++, failed++, keeps <= 3 examples, writes findings/finding-NN/note.md once per id
  void notEvaluated(String id, String reason);// stated skip (live, no add-opens, profile too short)
  void write(Path propertiesTsv);            // id	grade	evaluated	passed	failed	skipReason	statement	guarantee	examples
}
```

Fixed property ids (defined once in `oracle.Properties`, with statement/guarantee/bound text
taken from the design): `W1-A` replay after reconnect (A), `W1-B` no re-send on one
connection (A), `W1-C` unanswered request escalates (A), `W1-D` cancelled registration receives
nothing (B/C), `W1-E` per-key gapless sequence within an epoch (C), `W1-F` unknown-sub
notification tolerated (B), `W1-G` `lastMessageReceivedTimestamp()` monotone (A), `W1-H` ping
and keep-alive windows (A), `W1-I` `close()` terminal and bounded (A), `W1-J` a program
notification carries the pubkey the subscription was granted for (C; `WRONG_KEY_ECHO` is its
negative control), `W2-A` fragment
reassembly byte-exact (A/C), `W2-B` `maxMessageLength` aborts and reports (A), `W2-C` throwing
consumer contained (A), `W2-D` slow-consumer coupling (D), `W3-A` request/response
association (A), `W3-B` exchange deadline on default routes (B), `W3-C` body decoding and
untrusted Content-Length (A/B), `W3-D` errors do not wedge (A), `W3-E` cancellation isolated
(A), `W3-F` the predicate's invocation count equals the client-side count of completed
default-route exchanges (A — an aggregate identity, deliberately not "exactly once", which only
a per-response identity check could establish), `W4-C` commonPool deadline
timeliness (B, bound `2 × 500 ms + 2 s`), `W5-A` engine-owned resources released (A/B),
`W5-B1` caller-owned HttpClient survives (A), `W5-B2` injected executor not shut down (A, skip
without add-opens), `W5-C` fd count returns within margin (D → margin FAIL), `P7` recovery
budget after a connection-level fault (derived bound printed in the ledger), and the named
non-properties `NP-1..NP-4` with grade `NONE`.

Verdict rule (in `soak.sh`, reading `properties.tsv`): grade A/B/C with `failed > 0` → FAIL;
grade A/B/C with `evaluated == 0` and no `skipReason` → FAIL `UNEXERCISED`; grade D never
fails; `NONE` rows are informational.

`SequenceOracle`: one `long[]` slot per `(engine, key)` plus an epoch counter; `observe(engine,
key, epoch, seq)` returns `OK | GAP | DUP | REORDER | FIRST_OF_EPOCH`, allocation-free; a new
epoch (connection ordinal) resets to "monotone, any gap allowed" (NP-1).

`RecoveryLedger`: `open(faultOrdinal, engine, originOrdinal, owedMsgIds, faultKind,
budgetMillis)` (returns the window it displaced, if any; a count-only overload remains),
`openAtAdoption(...)` (the same window, anchored on the attempt that was finally adopted, counting
confirmations from it inclusive — see P7 below), `adopted(engine, attemptOrdinal)`, `confirmed(engine, msgId, attemptOrdinal)` (true on the
confirmation that empties the owed set; only attempts after the fault's count, and only ids
the window owes), `released(engine, msgId)` (a plan unsubscribe, a one-shot signature the
engine completed, or the quiesce takes the id off the owed set — the last two windows of a run
were measured failing on the registrations the quiesce cancelled before their replay was
confirmed), `closeCompleted(engine)`, `closeIfOpen(engine, faultOrdinal)` (the budget timer),
`close(engine, nextAttemptOrdinal)`. A window closes in one of three ways: the confirmation or
release that empties the owed set closes it at once; its own budget timer closes an
incomplete one as a `P7` failure with the fault kind in the example; the next connection-level
fault on the same engine displaces it — judged as complete or over budget when it already was,
otherwise **superseded** (`recovery.superseded`): no verdict, because at the validate density
the next destructive fault lands inside most 25 s windows and failing those would fail `P7`
whenever the schedule is denser than the budget. A window that owes nothing (no live
registration, or every owed one released before any was confirmed) is `recovery.vacuous`. `recovery.completed` counts evaluated windows. The
derived budget assumes the engine learns of the fault at once; `SWALLOW_PING` can only be
noticed by the two-phase ping probe, so the websocket driver opens its window with
`2 × pingDelay + subscriptionAndPingCheckDelay` added. A recovery window is opened for the
faults the peer marks `expectedRetirement()`, not for every CONN-scope fault: the question is
whether the engine recovered from a fault that *retired* the connection. The replay bound (W1-A: every live
registration re-*sent*) and the recovery budget (P7: every one *confirmed*) are separate clocks
— closing P7's window when the last replay was sent was measured cutting it a few hundred
milliseconds before the last confirmation.

Oracle decisions the integration settled, each on evidence from a run:

- **W1-A** is measured from the successor's `onOpen`, bound `4 × subscriptionResendDelay + 2 s`:
  a managed engine's reconnect is paced by ravina's `Backoff`, so a bound from the retirement
  would test the backoff, not the replay promise. The reconnect interval itself goes to
  `ws.reconnect.latency`. Only registrations still live at the bound are owed a replay: one the
  plan unsubscribed inside the window, or a one-shot signature registration the engine
  completed, has left the promised set. The signature case is retired from the terminal
  notification itself (`value` not `receivedSignature`, the rule `SolanaJsonRpcWebsocket` releases
  the subscription on), never from a wire unsubscribe: the engine sends none for a completed
  signature, and the `signatureUnsubscribe` rows a run does show are its unknown-id auto-cancel
  reacting to the peer's surplus post-terminal frames — which a reset can truncate (measured
  2026-09-22, one registration charged to sava as a lost replay for exactly that reason). So has a registration whose subscribe the server answered with a code
  `SolanaJsonRpcWebsocket`'s `isRequestDefect` classes as terminal — `INVALID_REQUEST`,
  `METHOD_NOT_FOUND`, `INVALID_PARAMS`: that branch removes the pending subscription and releases its
  channel slot, on the argument that re-sending the same frame can only collect the same answer, so
  the engine owes it neither a replay (W1-A) nor a confirmation (P7's owed set). `-32603` and its kin
  are the *server's* condition, stay pending, are retried by the resend pacing, and are still owed
  both. Establishing which of the two happened needs the code: `JsonRpcException` carries it but no
  request id, so the peer's `sub_err` and `unsub_err` rows carry a `code=<n>` token in their detail
  (§7) and the harness decides from that token exactly as sava's `isRequestDefect` does. A row
  without the token (an older peer) falls back to pairing the row with the `ERROR_RESPONSE`
  (`-32602`) or `REJECT_SUBSCRIBE` (`-32603`) record in `faults-applied.tsv` on the peer's own
  clock. The retirement of the registration is counted as `ws.subscribe.requestDefectRetired` (the
  client-side sighting through `exceptionSubscribe`, which cannot name the registration, is
  `ws.subscribe.requestDefectObserved`).
- **W1-B** joins on `(port, connId, subscribe fingerprint)` — the peer's `fp=` token (§7) —
  with the JSON-RPC id kept only as a fallback for a row that carries no token, counted as
  `ws.harness.w1bMsgIdFallback` so a reviewer can tell which rule a run's verdicts were reached
  under. Keying on the id alone could not see a re-send that arrived under a fresh one. Three
  exits are modelled rather than failed: a subscribe the peer answered with an error (`sub_err`)
  is an *answered* request, and the engine's retry of it under the same id after
  `subscriptionResendDelay` is counted as `ws.subscribe.resentAfterError`; an unsubscribe of
  the granted id (`unsub_req … subMsgId=`) restarts the count; and a second request for one
  fingerprint whose earlier registrations were all cancelled is a new registration's first send,
  judged from the harness's own registry so no peer-log row ordering has to be trusted. A second
  request that cannot be joined at all (an earlier id no longer resolves, or the per-fingerprint
  id list hit its bound) is dropped and counted, never guessed.
- **W1-F** and **W2-C** do not count a retirement that a fault marked `expectedRetirement()`
  on the same attempt explains (`ws.containment.excused`): at the validate fault density some
  unknown-id notification and some deliberate throw precede every retirement, and without the
  exclusion the properties could only fail. The judgment waits `RETIREMENT_EXPLANATION_MILLIS`
  after the containment window, because the fault rows arrive through the peer-log tail, which
  was measured lagging a fault storm by more than the 2 s window (three "failures" whose
  explaining row was written 20 ms before the retirement and read after the check). Timing
  alone never explains a retirement (`ws.retirement.unexplained`). A retirement is admitted by its
  **cause** as well as by an applied fault: one the attempt tracker classed `JDK_CLOSE`, `JDK_ERROR`,
  `UNANSWERED_REQUEST`, a `PING_*` timeout, `MAX_MESSAGE_OVERFLOW`, `INSTANCE_DEATH`,
  `CANCELLED_BEFORE_INSTALL` or `HARNESS_INJECTED`, or one the peer's own `overflow` row explains, is
  excused under the separate counter `containment.excusedByCause`. Both exclusions feed the
  per-property `checksExcused` tally, so a stated skip stays accountable. And the verdict is **one per
  `(engine, attempt, property)`**, never one per notification: one control run reported eighteen W1-F
  failures from two retirements before the claim was deduplicated.
- **W2-B** is evaluated only where the message exceeds the engine's own `maxMessageLength`.
- **P7**'s clock starts at the adoption that follows the fault, for every fault kind: the window
  waits for the next adoption on that engine, keeps the base budget, keeps the fault's own ordinal,
  kind and origin in the record (`-1` for a fault that hit no identifiable connection), and counts
  confirmations from the adopted attempt *inclusive*. The reason is the one W1-A's bound rests on:
  the peer's refusal window, a stalled upgrade, a reset on the next attempt and the manager's
  backoff are not sava's replay promise. Measured twice — a `CONNECT_REFUSE` with no identifiable
  origin spent the whole budget before a socket existed on a control run, and on a pilot a reset on
  one attempt was followed by six refused or failed attempts, so a window opened at the reset aged
  out with nothing to recover on. Counted as `ws.harness.recoveryAnchoredAtAdoption`, which P7's
  skip reason names, so a run whose faults were all deferred and never adopted says that rather
  than claiming no window opened.
- **W1-H(i)** owes a peer connection a keep-alive only once its `handshake` row reads `101`. A refused
  upgrade is never owed a probe — the sweep was reporting "no ping" on sockets the peer had reset
  inside the handshake.
- **W1-I** is asserted on the bare engine's **currently open** epoch's peer connection, joined on the
  attempt ordinal both processes stamp, and resolved before `close()` while the epoch is still open.
  Keying on the latest connection id the peer logged for the port could only time out when that
  connection was one the peer had refused and reset. With no open epoch at `close()` the two API
  halves decide, `ws.harness.closeWithoutLiveConnection` records it, and a stated skip beside the pass
  says which half of the contract the run established.
- **W3-E**'s second term is an exchange left unsettled past its **own** bound, not a timeout: an
  operation the harness stopped waiting for before the client's deadline was due is not evidence of
  cancellation fallout. `getProgramAccounts` is the case that forced it — its harness bound expires
  well before its exchange deadline — so the finding now reads "exchanges on the same client left
  unsettled past their own bound within N ms".
- **The bare engine's reconnect** is re-armed from an exceptionally completed `connect()` future as
  well as from the retirement hook, because a refused connect or a rejected upgrade retires nothing
  and would otherwise leave that engine down for the rest of the run (`ws.harness.bareConnectFailed`).
- **Retirement attribution** comes from the attempt tracker's `RetirementRecord.originOrdinal`, not
  from `onClose`/`onError`: `connect()`'s own javadoc says the lifecycle callbacks receive a reusable
  wrapper and no attempt token, so the API cannot attribute a callback to a particular future. Under a
  constant-zero reconnect the successor's `onOpen` was measured running before the predecessor's
  `onError`, which closed the successor's epoch and left the predecessor's retirement time unset.
  Consequences for the counters: `ws.epoch.retired` is one per *attributed* retirement (it was one per
  callback that found an epoch open, which is why a run could report more epochs opened than retired);
  `ws.harness.retirementCallbacks` is the callback total; `ws.harness.retirementWithoutOrdinal` counts
  records the tracker could not attribute; and `EpochRecord.errorsObserved` — the input to W1-H(ii)
  and W2-B — is set from the record's cause class rather than from an unattributed `onError`.
- **Retirement classification has a third outcome** beside expected and unexplained. A retirement the
  peer's own `overflow` row explains — its per-connection outbound queue filled and it closed 1011 —
  is `ws.retirement.peerOverflow` with a `PEER_OVERFLOW` anomaly, and belongs to neither column: the
  peer, not the subject, decided to close. Two more sit beside it, measured on the first pilot: a
  `MAX_MESSAGE_OVERFLOW` retirement of the OVERFLOW engine is `ws.retirement.overflowCap`
  (`OVERFLOW_CAP`) whatever message tripped the cap — the peer's periodic large notification is
  above that engine's cap too, not only a scheduled `MESSAGE_OVERFLOW`, and the engine enforcing
  the cap it was configured with is the documented behaviour, while the same cause on any other
  engine stays unexplained; and an `UNANSWERED_REQUEST` retirement on an attempt where the peer
  answered a subscribe with a server-condition refusal (`REJECT_SUBSCRIBE`, -32603, which sava keeps
  pending rather than retiring) is `ws.retirement.rejectEscalated` (`REJECT_ESCALATED`): the refusal
  is the cause, and whether the engine should have re-sent before escalating is reported to the
  owner rather than charged as unexplained.
- **Every recovery window anchors at the adoption that follows its fault.** What lies between the
  fault and that adoption — the manager's reconnect pacing, a refusal window, a stalled upgrade, a
  reset on the next attempt — is not sava's replay promise (measured twice: a refusal window plus
  the backoff outlasted the budget before a socket existed on `D7`, and on a pilot a reset on one
  attempt was followed by six refused or failed attempts, so a window opened at the reset aged out
  with nothing to recover on). A window still open when the next fault lands is displaced
  (superseded, no verdict), and the pending fault re-opens the measurement at the adoption that
  finally comes, with the base budget. A pending fault whose row names the very attempt being
  adopted is held for the adoption after it (`ws.harness.recoveryHeldPastCasualty`): the peer's
  row can outrun the JDK's `onOpen` — measured on a pilot, a reset 2 ms after the handshake was
  tailed 7 ms before `onOpen` fired — and a window opened on the casualty expires before the
  recovery attempt exists. A pending fault replaced by a newer one before any adoption anchored it
  leaves no record (`ws.harness.recoveryPendingOvertaken`), which is why `recovery.opened` can be
  below `ws.harness.recoveryAnchoredAtAdoption`.
- **W1-A and P7 are judged on the wire's clock, once the tail has read that far.** Both read
  the peer's rows through a tail that can fall behind — measured on the first campaign, about
  20 s behind for half a minute, several times in eight hours — and a bound kept on the
  harness's clock then charged its own read latency to the engine: a replay that was on the
  wire inside 9 ms read as three registrations never re-sent, and a window whose 128
  confirmations were all written inside 6 s closed with 94 outstanding. So a replay row or a
  confirmation counts by the timestamp the peer wrote (both JVMs share the machine's clock),
  late ones (`ws.harness.replayRowsLate`; `late=` on the P7 example) are counted and never
  credited, and the judgment itself waits past its bound until the tail's watermark — the newest
  row timestamp read — has passed that bound, for up to 60 s of grace; a judgment made after the
  grace with the watermark still behind is counted (`ws.harness.judgedUnderTailLag`, exported as
  `recovery_judged_under_tail_lag`) so the verdict can be read for what it is; a deadline the
  peer wrote nothing past (quiesce, drain) is judged once the tail is at the end of the file and
  2 s have passed, the peer's own flush lag being tens of milliseconds. The lag itself is a gauge
  column (`tail_lag_ms`: how long after the peer wrote the newest row read the harness read it,
  so a stall shows in the rows read once it ends) with its maximum exported as `tail_lag_max_ms`
  and a `ws.tail.lag` histogram sampled every 256th row plus every row read more than a second late,
  and the peer records its own half (`logMaxLagMs` in `peer-summary.json`: the longest a row
  waited between its stamp and its flush) so the next lag can be placed on one side or the
  other. The other deferred checks (W1-H(ii), W1-C, W2-B) still read tailed state at their
  bound; a lag there can drop a check as confounded or pass one, never fail it.
- **W1-C, W1-H(ii) and W2-B** do not read a retirement as their own when another retiring fault
  hit the same attempt, or — for W1-C — when it arrived before the four-resend deadline. Those
  are dropped checks, not passes: a schedule dense enough to retire an attempt twice can prove
  nothing about which fault the engine answered. Expect W1-C to report `evaluated = 0` on a
  validate-density schedule.
- **W1-J** asserts the program key the *peer granted*, forwarded by the engine into
  `AccountInfo.pubKey()`, and only on the two program channels. A plain account notification
  carries no `value.pubkey` at all — sava fills it from the registration — so asserting there
  would compare the harness against its own request.
- **W2-A** is checked against the notification the bytes arrived in (payload sequence header and
  `value.space`), not only against the payload's own checksum; see §7. It is a peer-established
  check — the checksum is the controlled peer's — so it is gated on the peer oracle and stated as
  not evaluated on a live run, where a real node's account data would otherwise have failed it on
  the first delivery (review; the first live runs delivered none only because their engines still
  subscribed to the seeded table, and the first run on the supplied accounts delivered clock
  notifications with the gate in place).
- **W3-B**'s bound is the bound the ledger publishes: the exchange deadline
  (`2 × requestTimeout`) plus 2 s of slack, and a FAIL past *that*, not past twice it. An
  operation whose own harness bound expires before the client's deadline was due records neither
  pass nor fail, because nothing was observed. `getProgramAccounts` is drawn with a two-minute
  request timeout, so its exchange deadline is twice that while the harness bounds its own wait at a
  stated 125 s: a watchdog `TIMEOUT` there means the operation is still running, never that it is
  late, which is why the overdue accounting (§9) reads each route's own bound rather than the
  harness's.
- **W3-F** tolerates one predicate invocation per harness-driven cancellation: `cancel(true)`
  on the caller's future does not stop the source stage, so a response already in flight still
  reaches `testResponse` once. Probe operations (below) are excluded from both the per-subject
  completed count and the per-subject outstanding band, because the probe client carries no
  predicate; excluding one without the other would bias the band rather than leave it neutral.
- **W5-B1** has one survival rule with two call sites and one implementation
  (`ChurnWorkload.peerAnswered`): a `JsonRpcException`, or an `UncheckedIOException` wrapping
  `UnknownServiceException`, means a status line and a body completed over the caller's
  `HttpClient` — which is what the property asks — while a transport `IOException`, a timeout
  with no status, an `IllegalStateException` from a closed client and a `RejectedExecutionException`
  stay failures. It is stated here so a future reader does not reintroduce a second copy.
- **W3-C**'s twin comparison is over the key-derived projection of the stamped account (key
  hash, owner, space), because the two stamps carry their own request id and peer sequence and
  can never be byte-equal. A decoding failure that surfaced cleanly is *not* a W3-C pass: this
  side cannot tell a malformed body from a whole one the client rejected, so the clean failure
  only leaves the row the report joins against the peer's applied faults, and the pass comes from
  the paths that saw the body whole (a client rejecting every valid gzip body used to pass on its
  own rejections; review). Those paths must also have seen it *compressed*: the predicate's pass
  needs a body the peer served under `gzip`, `x-gzip` or `identity, gzip` and handed over whole
  and unframed — the rotation's plain quarter is counted (`rpc.gzip.identityServed`) and earns
  nothing — and the twin comparison records nothing when its gzip half came back identity-served
  (`http.twin.identityServed`; the stamp's request id is matched against the predicate's ring of
  identity-served ids). Both used to pass on bodies nothing had decoded (review).
- **W3-D** means *settles*, not *succeeds*: the three operations after an error must settle
  inside the client's exchange deadline (`2 × requestTimeout`, `JsonHttpClient`'s
  `withResponseDeadline`) plus 2 s. One answered promptly with another error is not wedged — the
  peer's next fault, or the JDK pool handing out a keep-alive socket the peer had just dropped
  (`HTTP/1.1 header parser received no bytes`, settled in a millisecond; the JDK does not retry
  a POST) — and is counted as `rpc.wedgeProbe.erroredFast`. The three probes travel on a separate
  `SolanaRpcClient` built over the **same** caller-owned `HttpClient` and endpoint, not on the
  failing client instance: the probe shares the connection pool, the executor and the common-pool
  deadline timer — every piece of state a wedge can live in — and differs only in per-instance
  configuration, which `JsonHttpClient` holds immutably. A per-call flag would have meant
  assuming when the subject builds its request, which is an assumption about the library's
  internals this harness must not make. The countdown is claimed only by the paced wrapped
  route, so the no-wrap route, W3-C's twins and the OVERLAP fan-out never become probes — a twin
  claimed as a probe would have been exempted from the very fault W3-C needs. Consequence for a
  run's numbers: `rpc.wedgeProbe.erroredFast` should now be near zero, and a non-zero value means
  the pool handed out a peer-closed socket rather than that the peer faulted the probe.
- The no-wrap route (`SoakRawHttpClient`) keeps the whole `HttpResponse`: a non-2xx status is
  `HTTP_ERROR`, a body served with a `Content-Encoding` is handed back as sent by that route's
  contract (`rpc.nowrap.encodedBody`) and the envelope check applies only to plain 2xx bodies.

## 9. Counters, gauges, phases (core)

`Counters` — one `LongAdder` per name below, `LatencyHistogram` (log-scale 5 % buckets, µs,
allocation-free) per histogram; written to `counters.properties` as `name=value` and
`hist.<name>.{count,p50,p99,max}=` (a percentile is a bucket's upper edge, clamped to the exact
max so p50 can never print above it):

```
ws.notifications.delivered           consumer accept returned or threw
ws.notifications.sequence.ok/gap/dup/reorder
ws.notifications.zombie              consumer invoked for an unsubscribe-acked subId
ws.notifications.unknownSub          UNKNOWN_SUB_NOTIFICATION observed at the peer, connection survived 2 s
ws.consumer.threw / ws.consumer.threw.unexpected
ws.subscribe.requested / .confirmed / .replayed / .refused
ws.subscribe.requestDefectRetired / .requestDefectObserved
                                     a subscribe the server answered with a code isRequestDefect
                                     classes as terminal: the engine dropped the registration and
                                     freed its slot, so it is owed no replay (section 8). The
                                     observed half is the client-side sighting, which carries no
                                     request id and can only corroborate
ws.unsubscribe.requested / .acked / .refused
ws.epoch.opened / .retired          per run (per-engine in client.csv); retired is one per
                                     ATTRIBUTED retirement (the tracker's originOrdinal), not one
                                     per callback that found an epoch open
ws.retirement.expected / .unexplained   unexplained = no fault applied on that connection within 5 s and not a deliberate abort
ws.retirement.peerOverflow           the peer's own outbound queue filled and it closed 1011: the
                                     third outcome, in neither column above
ws.retirement.overflowCap            the OVERFLOW engine enforcing its configured cap on a message
                                     the schedule did not send (the periodic large notification)
ws.retirement.rejectEscalated        an unanswered-request escalation of a subscribe the peer had
                                     refused -32603; the peer's doing, reported as an observation
ws.exceptionSubscribe.received
ws.close.terminal                   W1-I evaluations
rpc.completed.ok / .rpcError / .httpError / .timeout / .cancelled / .deadline / .decode / .transport / .mismatch
                                     cancelled: the harness cancelled it (W3-E's input); deadline: the
                                     client's own exchange deadline (2 x requestTimeout) cancelled a stalled
                                     exchange - the same CancellationException, told apart by who asked;
                                     decode: the body arrived and could not be decoded, kept apart from
                                     .transport (a dead socket is a different defect from a body the client
                                     could not inflate), incremented once per request on either of its two
                                     paths - a DECODE settlement, and an inflate failure the outcome
                                     taxonomy files as TRANSPORT because it never reached the predicate
rpc.testResponse.invoked
rpc.bytes.received
rpc.deadline.honoured / .missed
rpc.nowrap.completed                 caller-body-handler route, no deadline property
rpc.shutdown.cancelled               futures the harness cancelled at SHUTDOWN because the exchange
                                     was still open: the per-op watchdog records TIMEOUT and
                                     deliberately leaves the future alone, so a stalled body would
                                     otherwise outlive the run. Filed as harness-cancelled, and
                                     deliberately NOT a W3-F tolerance, because by then no property
                                     is still watching
harness.http.inflightUntracked       operations the in-flight registry refused at its cap: issued
                                     and counted everywhere else, but contributing to neither
                                     overdue_rpc nor the shutdown sweep
rpc.nowrap.encodedBody               a no-wrap 2xx served with a Content-Encoding: handed back as sent,
                                     no envelope check (that route does not inflate by contract)
rpc.wedgeProbe.erroredFast           a W3-D probe that settled with an error inside its bound: not a
                                     wedge, but a burst says the pool handed out a peer-closed socket
rpc.probe.sent                       requests carrying X-Soak-Probe (section 7), counted once per request the
                                     client built, so it cannot drift from what went on the wire
rpc.gzip.ok / .badGzipClean / .truncatedClean / .identityServed
                                     identityServed: gzip-client answers the peer's rotation served with no
                                     encoding; whole, but no W3-C pass
http.twin.identityServed             W3-C twins whose gzip half was identity-served: no verdict either way
churn.engine.cycles / churn.client.cycles / churn.engine.cycleFailed{,.connect,.subscribe,.notify,.confirm,.engineError,.exception} / churn.engine.subscribeRefused / churn.client.cycleFailed{,.request,.exception}
                                     the churn ports sit under the fault schedule, so a failed cycle
                                     names its cause (a stalled handshake, a scheduled 503) beside the count;
                                     subscribe: the engine accepted none of the cycle's keys; notify (local) /
                                     confirm (live): the accepted registrations were not all notified /
                                     confirmed inside the bound; subscribeRefused: keys the engine answered
                                     false for, released from the latch rather than waited on
harness.opsSkipped.inflightCap / harness.opsSkipped.deadline / harness.opsAttempted / http.ops.attempted
                                     inflightCap: the HTTP driver drew its pace token and its worker's
                                     SOAK_HTTP_INFLIGHT permits were all still held; deadline: reserved,
                                     no driver increments it (the token bucket waits for a slot, never
                                     refuses one — the refusing version burnt tokens and spun);
                                     http.ops.attempted: the HTTP driver's own draws that reached the
                                     in-flight permit, whichever way it went, so inflightCap is a strict
                                     subset of it and the starvation ratio is a true fraction of draws;
                                     harness.opsAttempted deliberately keeps its cross-driver meaning and
                                     is the progress line's number, never the starvation denominator
harness.peerLog.dropped              from /__soak/stats at the end
harness.peerStats.unavailable        1 when /__soak/stats could not be read at SHUTDOWN, 0 when it was:
                                     logDropped is then unknown and the record cannot be certified
harness.tsv.dropped / harness.counters.dropped / harness.gauge.dropped / harness.recovery.dropped / harness.heapSeries.dropped
                                     every bounded harness structure counts its own overflow
faults.observed / recovery.opened / recovery.completed / recovery.overBudget / recovery.superseded / recovery.vacuous
ws.containment.excused               a W1-F/W2-C check whose retirement a destructive fault explains
containment.excusedByCause           the same, admitted by the retirement record's cause class or by
                                     the peer's overflow row instead of by an applied fault
ws.plan.subscribeAtCap               a SUBSCRIBE step refused at SOAK_WS_SUBSCRIPTIONS (the plan's ceiling, not starvation)
ws.subscribe.resentAfterError / ws.signature.terminated / ws.harness.engineUnsubscribedLive / ws.harness.overflowBelowCap
ws.harness.{checksDropped,peerRowsDropped,peerRowsRead,connectionViewsEvicted,oracleSlotsExhausted,replayEpisodesSuperseded,msgIdsEvicted}
                                     msgIdsEvicted also carries the per-connection W1-B join windows'
                                     evictions, not only the engine's msgId history
ws.harness.w1bMsgIdFallback          sub_req rows with no fp= token, so W1-B fell back to the msgId join
ws.harness.{retirementCallbacks,retirementWithoutOrdinal}
                                     the NP-2 callback total, and records the tracker could not
                                     attribute to an attempt (section 8)
ws.harness.bareConnectFailed         a bare-engine connect() future that completed exceptionally, which
                                     retires nothing and so re-arms the reconnect itself
ws.harness.recoveryAnchoredAtAdoption   a P7 window a pre-handshake fault deferred to the next adoption
ws.harness.closeWithoutLiveConnection   W1-I reached close() with no open epoch, so only the two API
                                     halves were established
ws.harness.deliveryToTombstoned      D7: a delivery that reached a registration the harness had already
                                     tombstoned, which is the W1-D failure that control stages
ws.harness.i52InjectionsArmed        one per run: the forced injector is armed once (section 13). The
                                     ceiling and the fired count come from the tracker instead, as
                                     i52.harness.injectionCapReached and i52.harness.injectedRetirements,
                                     because an injector that stopped at its budget and one that never
                                     armed otherwise leave the same empty win rate
harness.http.cancelChecksDropped, harness.control.{retainCapped,socketsOpened,socketsRefused,threadsParked}
jfr.errorsThrown.linkageProbe        jdk.JavaErrorThrow raised and caught inside java.lang.invoke's own
                                     pregenerated-LambdaForm probing (NoSuchMethodError from
                                     MemberName$Factory.resolve); dozens per JVM start, never escape,
                                     counted apart from jfr.errorsThrown so the gate can read zero honestly
i52.retirements / i52.claims / i52.doubleClaims / i52.misattributed / i52.doubleClaimsUnexplained / i52.opportunities / i52.claimsUnrouted / i52.originUnknown / i52.retirementsInsideAdopt / i52.futureSettledByRetirement
i52.harness.<key>                    every AttemptTracker.Stats entry, summed over the four trackers, one
                                     per engine; the two process-wide entries frameRegistryOverflow and
                                     frameRegistryThreads are written once from one snapshot instead,
                                     because the registry is one static structure and the second is a
                                     level, so summing either would report the same number four times
jfr.submitFailed / jfr.pinned / jfr.errorsThrown / jfr.vtStarted / jfr.vtEnded / jfr.threadStarted / jfr.threadEnded
histograms: rpc.latency.<method>, rpc.latency.<method>.netOfInjectedDelay, ws.delivery.<consumerKind>,
            ws.replay.latency, ws.reconnect.latency, http.deadlineLate, i52.gap, i52.retryMargin
```

`GaugeSampler` every `SOAK_GAUGE_SECONDS`: commits `sava.soak.Gauge` and appends to
`client.csv` with header
`epoch_s,phase,live_subs,pending_confirm,inflight_rpc,overdue_rpc,tail_lag_ms,open_conns,delivered,rpc_completed,mismatches,faults_observed,retirements,claims,misattributed,retained_regs,retained_tombstones,retained_ordinals,retained_exc_subs,heap_after_gc,threads,fds,last_msg_age_max_ms,vt_started,vt_ended,submit_failed,pinned,errors_thrown,commonpool_par,commonpool_queued,commonpool_active`.
`rpc_completed` is the series form of the same total `counters.properties` prints: it sums every
`rpc.completed.*` settlement kind, deadline and decode included, because an exchange the client's
own deadline cancelled and one whose body would not decode are settled exchanges, and dropping
them made a run whose faults mostly settle that way read as though it had stopped issuing work.
`inflight_rpc` and `overdue_rpc` answer different questions and only the second is evidence about
the subject: `inflight_rpc` is the harness's own load, bounded by the in-flight permits, while
`overdue_rpc` is its late subset — an operation still outstanding past the bound its *own* route
promises, which is the client's exchange deadline plus the harness's 2 s slack for a wrapped call
and the harness bound alone for the caller-body-handler route, that route carrying no library
deadline by contract. A `getProgramAccounts` call the pace drew in the last minutes of STEADY is in
flight at DRAIN end *by contract*, so only `overdue_rpc` is a residue and only it is what the DRAIN
gate reads (§14). A gauge source that tracks no per-operation deadline reports `-1`, which reads as
unavailable rather than as measured and empty. Every consumer reads this file **by header name,
never by column position**, so inserting a column cannot silently re-point a later field's gate.
`heap_after_gc` is the after-collection used heap of the last **full** collection — G1's
`G1Full`, classified by `JfrCounters` from `jdk.GarbageCollection`, plus the
`GarbageCollectorMXBean`'s major-collection notifications as the collector-agnostic fallback —
in **bytes**, carried forward until the next one and `-1` before the first. Neither a young nor
a mixed pause qualifies: the first carries everything promoted since the old generation was last
reclaimed, the second every old region it chose not to evacuate (measured 2026-09-22: 300 to
750 MiB between mixed pauses on a pilot, 23.6 MiB after its full collection). The harness forces
one full collection at each end of STEADY (`Phases`, each recorded as a `STEADY` marker row in
`phases.tsv` and as a `PhaseEvent` carrying the detail, and recorded only — the marker sends no
`onPhase` callback, because a notified `STEADY` microseconds after `HTTP_QUIET` had the HTTP
driver restore its full rate and the first quiet window ran at full load; review) and
none in between; the runner's gate is the rise between those two readings against
`SOAK_HEAP_FLOOR_NOISE_KIB`, the slope the report fits is informational, and `jfr.gc.pauses` /
`jfr.gc.floorSamples` say how many collections happened and how many were full; the report scales it
to KiB, drops the sentinel rows and fits with the noise-floor rule, publishing the limit it
applied as `heap_after_gc_limit_kib_per_hour` for the runner's gate. `fds` from
`UnixOperatingSystemMXBean.getOpenFileDescriptorCount()`, `-1` where unavailable.

`JfrCounters` (in-process `RecordingStream`, try-with-resources, started after `READY`,
closed before the final dump with a 10 s bound then `Runtime.halt(4)`): enables only
`jdk.VirtualThreadPinned` (1 ms), `jdk.VirtualThreadSubmitFailed`, `jdk.JavaErrorThrow`,
`jdk.VirtualThreadStart`, `jdk.VirtualThreadEnd`, `jdk.ThreadStart`, `jdk.ThreadEnd`,
`jdk.GCHeapSummary`, `jdk.GarbageCollection`, `jdk.G1GarbageCollection` (the qualifying after-GC
min per 30 s bucket → `heap_after_gc` series and its slope, and the `heap_after_gc` column's
primary source through the gauge's sink).

`Phases`: `STARTUP → WARMUP → STEADY (with HTTP_QUIET/HTTP_LOADED 120 s alternation, OVERLAP
fan-out first at STEADY start + min(300 s, half of STEADY) and then every 300 s, FAULT_STORM
once per hour) → QUIESCE → DRAIN → SHUTDOWN`; the OVERLAP placement is the same rule the storm
windows already used, and without it a validate or control run reached its end before the flat
300 s offset and W4-C could only ever be a stated skip; each boundary
commits a `sava.soak.Phase` event and appends to `phases.tsv`. Warm-up: correctness fully on;
only trend fits drop warm-up samples. Main prints `READY` once STARTUP completes and exits
0 only when every stage completed; exit codes: 0 complete, 3 incomplete (a bounded stage
breached), 4 invalid (subject mismatch, redaction self-test failed, detector self-test failed,
unknown config key).

## 10. Issue #52 capture (`issue52`)

Mechanism (verified attribution rule, see `README.md` for the argument):

- `AttemptTracker` (one per engine, `AttemptTracker.forEngine(String engine, Consumer<RetirementRecord>, Consumer<ClaimRecord>, boolean forced, boolean liveMode)`;
  the websocket driver adapts the two sinks to `SoakContext.retirements()`/`claims()` and the
  `i52.*` counters, and the TSV headers are `RetirementRecord.tsvHeader()` /
  `ClaimRecord.tsvHeader()`, which `SoakContext` uses so writer and rows cannot disagree):
  - `WebSocket.Builder wrap(HttpClient httpClient)` → `HarnessWebSocketBuilder`, which
    materialises a fresh `httpClient.newWebSocketBuilder()` per `buildAsync` (headers
    accumulate on a shared JDK builder), assigns ordinal = 1-based `buildAsync` count, adds
    header `X-Soak-Attempt: <ordinal>` (not in live mode), pushes a `buildAsync` frame on the
    calling thread, returns a `HarnessBuildFuture(ordinal)` DIRECTLY (sava's `ownBuild`
    returns the builder's future itself, so `retireConnection` cancels this very object), and
    completes it from `jdkFuture.whenComplete` with `wrapperFor(rawSocket)` (ONE `HarnessSocket`
    per raw socket, identity map; the same instance is substituted into every listener
    callback, because sava adopts the `onOpen` argument and compares identities in
    `connect()`/`close()`/`buildReservedAttempt`). In forced mode the completion is deferred
    until `onOpen(ordinal)` has returned.
  - `HarnessBuildFuture extends CompletableFuture<WebSocket>`: `cancel(true)` records
    `(ordinal, wasDone = isDone())`, sets the thread's `insideBuildCancel` flag for the
    duration of `super.cancel(true)`, forwards `jdkFuture.cancel(true)`.
  - `HarnessSocket implements WebSocket`: `abort()` pushes a retirement sub-frame candidate
    `(ordinal, seq, nanoTime, StackWalker direct caller class+method)` before delegating;
    classifier: caller `SolanaJsonRpcWebsocket.retireConnection` → RETIREMENT; `connect`,
    `adopt`, `close`, `lambda$ownBuild*`, `buildReservedAttempt`, or no sava frame → OTHER;
    duplicate aborts of one socket de-duplicated by identity. `request(long)` carries the
    forced-mode injection hook. Every other method delegates, mapping returned futures to
    `this`.
  - The forced injector is armed **once**, as an arithmetic series of attempt ordinals fixed at
    arming time (`injectRetirementEvery(period, maxInjections)`: the ordinal after the one current at
    arming, then every `period`-th) with a fired budget, and each adopted socket asks
    `claimInjection(ordinal)`. It is not a single ordinal a workload re-arms after each retirement:
    re-arming from the retirement callback loses the race with a constant-zero reconnect, because the
    successor is built and adopted before that callback returns, so the arm lands behind the attempt
    it meant to select — which is how `I1-forced` came to arm one injection and fire none. Injection
    is forced-only *by construction* (arming an unforced tracker throws), so an injected retirement
    can never meet the revisit trigger. Whether it counts towards `i52_natural_win_rate` is a
    different question — whether the *race* was decided by the harness: `INJECTED_RETIREMENT`
    (I1-forced) holds the retiring thread in the gap until the successor's build is observed, so it
    forces the race to be won and is excluded; `INJECTED_RETIREMENT_UNBLOCKED` and
    `INJECTED_RETIREMENT_JDK_ORDER` only cause the retirement and let the successor race at this
    machine's speed, so they are the denominator (`RetirementRecord.raceUnblocked`, one rule for the
    detector and the report). The rate says how often, once the race is met, the successor is
    charged — never how often production meets it. Two counters are the evidence:
    `injectedRetirements` (fired) and `injectionCapReached` (the arming hit its ceiling); an empty
    `i52_natural_win_rate` must be read against them.
    Measured 2026-09-22: `I1-jdk25` (deferral off) did **not** reproduce the future-first order it was
    built for — all its rows read `openOrder=ONOPEN_FIRST`, `buildWasDoneAtCancel=false` — and still
    double-claimed 182 of 200, because the future ravina claims through is sava's own connect
    bridge (`connected.copy()`), which `retireConnection` settles synchronously by cancelling the
    pending build (`insideBuildCancel=true` on every FUTURE claim). What removes the double claim is
    a build future already *done* when the retirement runs, the shape only `SelfTest.jdkOrderControl`
    stages (`buildWasDoneAtCancel=true`, `futureRoute=NONE_ALREADY_SETTLED`, one attributed claim);
    the row therefore reports its double-claim count and asserts only that none is unexplained.
  - `HarnessListener implements WebSocket.Listener`: wraps sava's listener, substitutes the
    wrapper socket into every callback, pushes a listener frame `(kind, ordinal)` around each
    delegation, records the thread and nanoTime of `onOpen` and emits
    `sava.soak.ConnectAttempt` with `openOrder` (FUTURE_FIRST | ONOPEN_FIRST).
  - `DeliveryFrames`: per-thread LIFO frame stack pushed by the three harness entry points
    (listener callback, build-future completion, `buildAsync`); retirement sub-frames live in
    the frame that created them; a lazy frame is created on a thread with no entry point (the
    engine's check loop) by the first retirement abort and replaced by the next one or aged
    out at 2 s; consumption innermost-first, `consumedBy += kind`, sub-frame kept alive so
    `onPingError` reads the same one; a handler consumes only `seq > lastConsumedSeq` of its
    thread.
  - `Backoff backoff(Backoff delegate)` → `RecordingBackoff`: every `delay(errorCount, unit)`
    call is one accepted manager claim: records `ClaimRecord(seq, errorCount, delayMillis,
    thread, insideBuildCancel, innermostOrigin, originQuality FRAME|LAZY|UNKNOWN,
    currentOrdinal, nanoTime)` and emits `sava.soak.ManagerClaim`.
  - `SolanaRpcWebsocket.Builder instrument(SolanaRpcWebsocket.Builder prototype)`: installs
    `onOpen/onClose/onError/onPingError` composed AFTER any existing handler on the prototype
    (the manager later composes its own BEFORE these via `andThen`); these finalise the
    `RetirementRecord`, emit `sava.soak.Retirement`, and (for BARE engines) an optional
    reconnect policy supplied by the workload.
  - `ManagerLogCapture.install()` (global, once): strong-referenced JUL handler on
    `software.sava.services.solana.websocket.WebSocketManagerImpl` (level WARNING+INFO) and
    `software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket` (WARNING+); classifies message
    prefixes `Websocket connection attempt failed. Re-connecting in ` → ATTEMPT_FAILED,
    `Websocket closed [statusCode=` → CLOSED, `Websocket failure. Re-connecting in ` →
    FAILURE, `WebSocket connected to ` → CONNECTED, `Websocket became terminal while
    connecting.` → TERMINAL; tags each record with the thread's current frame; a startup
    assertion drives one synthetic failure through a throwaway manager and requires a
    non-OTHER classification; in forced mode the handler can block on ATTEMPT_FAILED until
    the tracker observes the successor's `buildAsync` (latch, 2 s bound).
- `RetirementRecord` fields = the `retirements.tsv` columns:
  `retirementId	engine	backoffClass	forced	forcedReason	originOrdinal	originGeneration	adoptedAtNanos	retiredAtNanos	connectionAgeMs	openOrder	causeClass	causeDetail	deliveryThread	threadKind	frameKind	retiredNewerThanListener	abortSeq	abortBeforeCallback	abortClassifier	abortToHandlerUs	buildCancelObserved	buildWasDoneAtCancel	futureRoute	callbackRoute	pingRoute	claimCount	errorCountBefore	errorCountAfter	retryDelayMs	loggedRetryMs	gapFutureToCallbackUs	retryMarginUs	harnessTimeInGapUs	currentOrdinalAtCallbackClaim	successorBuiltInsideGap	verdict	nextAttemptOrdinal	nextAttemptOutcome	wakeLatencyUs	subscriptionRecoveryMs`
  with enumerations: `causeClass` ∈ {JDK_CLOSE, JDK_ERROR, UNANSWERED_REQUEST,
  PING_SEND_TIMEOUT, PING_RESPONSE_TIMEOUT, PING_SEND_FAILURE, MAX_MESSAGE_OVERFLOW,
  ID_COLLISION, UNSUB_NONEQUIVALENT, INSTANCE_DEATH, CANCELLED_BEFORE_INSTALL,
  HARNESS_INJECTED, UNKNOWN}; `threadKind` ∈ {JDK_LISTENER, CHECK_LOOP, ADOPTING, BUILD_COMPLETION,
  OTHER}; `futureRoute` ∈ {NONE_ALREADY_SETTLED, FIRED_IGNORED, FENCED, ACCEPTED};
  `callbackRoute` ∈ {ON_CLOSE_ACCEPTED, ON_CLOSE_FENCED, ON_ERROR_ACCEPTED, ON_ERROR_FENCED,
  UNOBSERVED}; `verdict` ∈ {ATTRIBUTED_SINGLE_CLAIM, FENCED_CORRECTLY, OPPORTUNITY_NOT_REALIZED,
  DOUBLE_CLAIM_MISATTRIBUTED, DOUBLE_CLAIM_UNEXPLAINED, INSTANCE_DEATH, UNOBSERVED_DELIVERY};
  `nextAttemptOutcome` ∈ {OPENED_ACKNOWLEDGED, OPENED_UNACKNOWLEDGED_REPLACED, REJOINED_ACKNOWLEDGED,
  FAILED, CANCELLED_BEFORE_INSTALL, PENDING}. Unknown numerics are `-1`.
- `claims.tsv`: `claimSeq	engine	errorCount	delayMillis	route	originOrdinal	originQuality	currentOrdinal	insideBuildCancel	thread	nanoTime`.
- `RetirementDetector.evaluate(List<RetirementRecord>, List<ClaimRecord>, BackoffClass, boolean forced)`
  → `Issue52Summary` (pure function): per record `C_f` (accepted claim while
  `insideBuildCancel`), `C_c` (accepted claim after it cleared and before the harness handler
  returned), `doubleClaim = C_f ∧ C_c`, `misattributed = doubleClaim ∧ successorObserved` where
  `successorObserved = successorBuiltInsideGap ∨ currentOrdinalAtCallbackClaim > originOrdinal`
  (the ordinal the second claim itself read is published before the attempt map's entry, which a
  scan can miss while the successor is being constructed — measured once in 200), `unexplained =
  doubleClaim ∧ ¬successorObserved` (counted apart,
  makes the verdict INCONCLUSIVE and fails the run as a harness-health gate — a harness bug or a
  changed manager, never a #52 finding), `opportunity = C_f ∧ retryDelay ≤ gap`,
  `retryMargin = retryDelay − gap`; totals with denominators (retirements, wasDone==false
  retirements, retirements inside adopt, opportunities, doubleClaims, misattributed,
  unexplained) and gap/retryMargin distributions; `BackoffClass` ∈ {CONSTANT_ZERO,
  ZERO_INITIAL_ESCALATING, POSITIVE_ESCALATING, POSITIVE_CONSTANT} classified by sampling
  `delay(1..8)`. Trigger met iff `misattributed > 0 ∧ ¬forced ∧ backoffClass == POSITIVE_ESCALATING`.
- `HarnessBackoffs`: `exponential250to30s()` (`Backoff.exponential(MILLISECONDS, 250, 30_000)`),
  `zeroInitialEscalating()` (harness-defined: 0 for errorCount 1, then the exponential value),
  `constantZero()` (`Backoff.single(MILLISECONDS, 0)`).
- `SelfTest` (main): cases P1–P15 (JDK onClose, JDK onError, max-message overflow,
  non-equivalent id collision, unanswered escalation, ping send timeout, ping response timeout,
  ping send failure with onError+onPingError on one sub-frame, check-loop death classified
  INSTANCE_DEATH, close() zero records, connect() replacement anti-misfire, adopt/ownBuild
  stale, cancelled-before-install, nested delivery, injection arming) driving the REAL engine
  through its public
  API with `FakeWebSocketBuilder`/`FakeWebSocket` (deferred futures, `requestAction`,
  `abortAction`, `failPing`, `throwPing`, `deferPings`, `deferTexts`, `failText`) and the
  reflective `clock/executorService/scheduler/checkCycle(0)` seams (requires the sava
  `--add-opens`); the polling-manager negative control (double claim expected), its positive
  control (single accepted future claim, callback fenced), the threaded variant with the
  production factory (latch, 2 s), the JDK-shaped-order control (future before onOpen → no
  future route), and the detector unit test on synthetic frames. Prints one line per case
  `SELFTEST <id> PASS|FAIL <detail>` and exits 0 only if all pass. The synthetic detector test
  is also invoked by `Main` at STARTUP (< 5 ms) as a gate.

## 11. Report (`report`)

`SoakReport <runDir> <recording|->`: one streaming `RecordingFile` pass (skip JFR sections when
`-`), plus the TSV/CSV/properties artifacts. Sections: (0) recording census (events, first/last
instant, ring coverage % of the run *up to the dump* — the final dump is taken inside DRAIN by
design, so the covered window ends at the recording's last event and under 100 % means the
ring rolled, never that the run outlived the dump — chunk count, method filters resolved N of M from
`jfr-methodtrace.log`, OldObjectSample count and rooted count with an INCONCLUSIVE banner at 0,
top 30 types, socket-event share, exception throttle saturation from `jdk.ExceptionStatistics`),
(a) run identity, (b) progress and starvation per workload and per fault kind (schedule vs
applied vs no_target, fidelity), (c) correctness ledger, (d) faults and recovery — the per-kind schedule/applied/no_target table,
the decoding reconciliation and fault-kind *coverage* (kinds with zero planned rows, split into
those the peer declared omitted and those nothing accounts for, the second raised as a NOTE,
because the unapplied-kind gate can only iterate kinds that reached the plan) — (e) issue #52
(also `issue52.md`: per-retirement table, claim table, distributions, backoff classes, verdict
line `ISSUE52: REVISIT | KEEP-DEFERRED | INCONCLUSIVE`), (f) latency (harness histograms; JFR
tails; the W4 sub-phase comparison, whose OVERLAP window is the report's own widened marker
window — the fan-out is a marker, not a duration event, so the window is synthetic and every
section that prints the comparison says so — `http.deadlineLate`), (g) resources (platform thread
starts/ends by spawner, vthread balance, fds, after-GC floor series from `client.csv` with a
least-squares slope after warm-up, OldObjectSample types with root paths, RSS and gated NMT
slopes from `rss.csv`/`nmt.csv`, probes — the `retiredSubIds` residue is **not measured**, and
the section says so rather than listing a number no probe reads), (h) quiescence residuals,
(i) stalls (`jdk.ThreadPark` ≥ 10 ms by top frame, `jdk.JavaMonitorEnter` sites, socket tail by
host/thread, a ThreadDump detector whose signature is the same significant frame across three
consecutive dumps **and** no sign of the thread having run in between — the frame alone reported
sava's four check loops, which park in `prepareCheckCycleDelivery` for their whole life and are
perfectly healthy, as stalls the runner then turned into a FAIL naming a production method. What is
exempt is a named list of harness **driver** thread prefixes (`JfrPass.HARNESS_DRIVER_THREADS`: the
phase clock, the workload pacing loops, the log and TSV writers, the peer's own threads), and the
subject's threads are never exempt by name. `soak-hc-N` in particular is named and created by the
harness but is the executor behind the shared `HttpClient`, so sava's websocket listener and this
harness's notification consumers run there and a park on one is a subject stall and is reported; the
injected executor the churn driver hands a builder is the subject's for the same reason. The second
clause holds whatever the thread is called: a wait whose owning frame — the frame below the park
primitive, which is what `significantFrame` already returns — is `software.sava.rpc.json.*` or the
harness's own `ConsumerFactory` (which runs on sava's callback thread) is never acquitted by name —
`jdk.MethodTrace` > 50 ms), (j) verdict inputs. `jdk.ExecutionSample`
is read through `sampledThread`. `jdk.MethodTiming` invocations are cumulative; per-interval
rates are consecutive diffs; rows are never summed; rows are keyed on name *and* descriptor,
because one filter entry instruments every overload with its own counter (diffing two
overloads' interleaved rows under one name was measured printing negative rates). The RPC
tail's `error` column is the client-side outcome kind, never the peer's injected fault.

`SoakReport` writes exactly three files: `jfr-report.md`, `jfr-metrics.properties`,
`issue52.md` (`jfr-summary.txt` and `jfr-views.txt` are the runner's). Sections (f) and (g)
are each split into a whole-run half from the artifacts (`f1`, `g1`) and a ring half (`f2`,
`g2`), so a ring number is never read as a run total.

`jfr-metrics.properties` keys: `events_total, ring_coverage_pct, chunks, old_object_samples,
old_object_samples_with_root, old_object_top_types, method_filters_requested,
method_filters_resolved, socket_event_share_pct, exception_throttle_saturated, submit_failed,
pinned, pinned_max_ms, errors_thrown, thread_starts, thread_ends, vt_started, vt_ended,
thread_dump_stuck_threads, dominant_thread_flag, dominant_thread, dominant_thread_share_pct,
execution_samples, heap_after_gc_slope_kib_per_hour, heap_after_gc_first_kb, heap_after_gc_last_kb,
heap_after_gc_limit_kib_per_hour (the applied `max(limit, floor / hours)`),
gc_count, gc_pause_max_ms, fault_fidelity, fault_no_target, fault_unreached,
fault_kinds_unapplied (comma-separated bracketed tokens, `[A],[B]`, empty when none — the
bracketing is what makes a control's needle exact), decoding_failures (the
`rpc.completed.decode` counter), decoding_failures_unexplained (that count minus the
`peer-http.tsv` rows whose `faultKind` is `BAD_GZIP`, `GZIP_TRAILING`, `TRUNCATE_BODY` or
`BAD_CONTENT_LENGTH`, floored at 0: a reconciliation and not a per-request join, generous to the
library, so a positive value is a *floor* on decoding failures no injected fault accounts for —
reported, never gated), recovery_over_budget, swallowed_ping_probe_retirements and
swallowed_ping_other_retirements (W1-H(ii)'s two outcomes from the `ws.harness.w1h*Retirements`
counters: retired by the probe's own deadline, or by another detector inside the bound),
tail_lag_max_ms, recovery_judged_under_tail_lag, replay_rows_late (the peer-log tail's lag and
the two judgments it can taint; see the properties list),
i52_retirements, i52_retirement_rows, i52_claims, i52_claims_rows, i52_double_claims, i52_double_claims_unexplained, i52_misattributed,
i52_opportunities, i52_gap_p99_us, i52_retry_margin_min_us, i52_natural_win_rate,
i52_claims_unrouted_pct, i52_origin_unknown_pct, i52_trigger_met, i52_verdict,
i52_unobserved_delivery`. The `*_rows` pair is the TSV row count beside its total, so a row
shortfall the report refuses to read reaches the runner as a number rather than only as a
verdict word. Every key is written on every run; an undefined fidelity is `n/a`, and so is an
unfitted `heap_after_gc_slope_kib_per_hour`, which is **not** `0`: `Slopes` refuses to fit a
start-up ramp, so every run shorter than its own warm-up window reaches that arm, where a `0`
would read as a measured slope of zero. The runner already has an `n/a` branch for that key
which raises a NOTE and never a FAIL. A missing measurement never invents a FAIL.
`recovery_over_budget` comes from `sava.soak.Recovery` events when the ring has any, else from
the `recovery.overBudget` counter.

## 12. Events (`events.SoakEvents` and `issue52.Issue52Events`)

All `@Category({"sava","soak"})`, `@StackTrace(false)` unless noted, names below with `@Name`:

`sava.soak.Phase` (duration: name, index, detail); `sava.soak.Gauge` (periodic 10 s, every
`client.csv` column, including `overdueRequests` beside `inFlightRequests`); `sava.soak.SubscriptionEvent` (engine, channel, key, action
REGISTER|SEND_OBSERVED|CONFIRMED|REPLAYED|UNSUB_REQUESTED|UNSUB_ACKED|REFUSED|DROPPED_ZOMBIE,
subId, msgId, epoch); `sava.soak.NotificationDelivered` (duration, threshold 5 ms, stack:
engine, channel, key, epoch, sequence, payloadChars, consumerKind, threw, slowByDesign);
`sava.soak.NotificationAnomaly` (kind GAP|DUP|REORDER|ZOMBIE|FIRST_AFTER_ADOPT|LONG_GAP, engine,
key, epoch, expected, actual, connectionOrdinal); `sava.soak.RpcCall` (duration, threshold 25
ms: client, method, requestId, outcome
OK|RPC_ERROR|HTTP_ERROR|TIMEOUT|CANCELLED|DEADLINE|TRANSPORT|DECODE|MISMATCH,
httpStatus, responseBytes, gzip, encoding, route WRAPPED|NO_WRAP, peerDelayMillis, faultKind);
`sava.soak.RpcOutcome` (non-OK only plus one per 1000 OK, and additionally for a clean decoding
failure the taxonomy files as `TRANSPORT` because it never reached the predicate — committed
with outcome `DECODE` and the harness request ordinal, which is the key `peer-http.tsv` is
joined on for the report's decoding reconciliation, §11); `sava.soak.Recovery` (duration:
faultOrdinal, engine, originOrdinal, attemptsUsed, registrations, confirmed, complete);
`sava.soak.FaultObserved` (kind, ordinal, scope, target, detail); `sava.soak.Verdict` (status,
reasons, notes, issue52). Issue52: `sava.soak.ConnectAttempt` (duration: ordinal, engine,
endpoint redacted, outcome OPEN|FAILED|CANCELLED, failure, openOrder, buildThread,
completionThread), `sava.soak.Retirement` (instant, every `retirements.tsv` column),
`sava.soak.ManagerClaim`, `sava.soak.ManagerLog` (level, prefixClass, message ≤ 240 chars,
thrownClass, thread, originOrdinal), `sava.soak.Misattribution` (`@StackTrace(true)`:
retirementId, originOrdinal, successorOrdinal, gapUs, retryDelayMs, errorCountInflation,
backoffClass, forced). Origin threads are explicit `Thread` fields (eventThread is only the
committing thread).

## 13. Workloads (`ws`, `http`, `churn`, `control`) — first cut

- Engines (`EngineProfile`): `EXPONENTIAL` (manager, `exponential250to30s`, agave port),
  `ZERO_INITIAL_ESCALATING` (manager, helius port), `BARE` (raw engine, harness reconnect on
  onClose/onError with `connect()`, agave port), `OVERFLOW` (manager exponential,
  `maxMessageLength(1 << 16)`, helius port, takes `MESSAGE_OVERFLOW`); churn uses the 5th port.
  Managers via `WebSocketManager.createManager(tracker.backoff(b), tracker.instrument(prototype), onNew)`
  (production factory). `webSocket()` is called once at start and never polled.
- Prototype: `SolanaRpcWebsocket.build().uri(ws://127.0.0.1:<port>).webSocketBuilder(tracker.wrap(httpClient)).pingDelay(...).subscriptionAndPingCheckDelay(...).commitment(CONFIRMED)`.
- `SubscriptionPlan(seed, engine)`: deterministic ops over a 256-key table
  (`Seeds.keyTable(seed)`; on a live run `Seeds.cycled` over `SOAK_LIVE_ACCOUNTS`), `SUBSCRIBE |
  UNSUBSCRIBE | RESUBSCRIBE_SAME_KEY | SUBSCRIBE_DUPLICATE_PARAMS | NOOP` (the duplicate step
  subscribes once through the harness's registry and then offers the byte-identical subscribe to
  the library directly, bypassing the registry — the registry refused it first and the library
  never saw a duplicate, so the step could not catch a library that accepts them; counted as
  `ws.plan.duplicateOffered/Accepted/Threw`),
  channels account/logs/slot/root/program(filtered)/keyedProgram/signature/generic
  `transactionSubscribe`; paced by monotonic deadline at `SOAK_WS_CHURN_PER_MINUTE`.
- `ConsumerFactory`: FAST 70 % / SLOW 15 % (`LockSupport.parkNanos`) / THROWING 10 % (after
  recording the sequence) / HEAVY 5 % (verify checksum).
- `HttpWorkload`: one shared `HttpClient` (HTTP/1.1, connectTimeout 5 s, named platform
  executor `soak-hc-<n>` of 8 threads), three `SolanaRpcClient`s (plain, `compressResponses()`,
  `extendRequest`+`testResponse`), `SOAK_HTTP_CONCURRENCY` platform workers each with a
  semaphore of `SOAK_HTTP_INFLIGHT`, token bucket `SOAK_HTTP_RPS`, method mix 40 % getAccountInfo,
  15 % getLatestBlockHash, 10 % getSlot, 10 % getMultipleAccounts, 10 % getSignatureStatuses,
  5 % getHealth, 5 % getBlock, 3 % getProgramAccounts, 2 % getVersion; `SOAK_NOWRAP_FRACTION`
  through `SoakRawHttpClient extends JsonHttpClient` (`sendPostRequestNoWrap(bodyHandler, ...)`,
  no deadline property); `SOAK_CANCEL_FRACTION` cancelled at a seeded delay; one probe client per subject, built over
  the same caller-owned `HttpClient` and carrying `X-Soak-Probe` on every request (§7), which
  W3-D's wedge probes and the end-of-drain W5-B1 probe travel on; W4: `httpQuiet`
  (10 % rate) / `httpLoaded` alternation, OVERLAP fan-out of 8 `getProgramAccounts(large)`
  first at half of STEADY when that is sooner than 300 s and every 300 s thereafter (§9),
  coinciding with a peer BURST, plus W4-C: 32 `getSlot` with `requestTimeout` 500 ms
  during each fan-out (asserted within `2 × 500 ms + 2 s`; `http.deadlineLate` measured). A
  bare sentinel timer is scheduled beside each probe the way sava schedules its cancel
  (`ForkJoinPool.commonPool().schedule`, the pool the JDK also re-dispatches every `sendAsync`
  completion onto): a probe late beside a sentinel that was itself late past the slack is the
  pool, counted `http.w4c.poolStarved` and neither passed nor failed; late beside a timely
  sentinel it fails, naming both latencies and the pool's queue. `http.w4cSentinelLate` keeps
  the sentinel distribution. Measured 2026-09-22 before the sentinel existed: two probes whose
  bodies the peer held settled only at the harness's 6 s watchdog while the pool's gauges read
  idle, and a standalone reproduction against a stalled-body server settled at 1.0 s every
  time; the sentinel is what makes the next occurrence attributable.
- `ChurnWorkload`: W5a engine cycles every `SOAK_CHURN_PERIOD_SECONDS` (build, connect (10 s),
  up to 4 distinct registrations with the latch sized to the ones the engine accepted, one
  notification each (10 s) — one confirmation each on a live run, where the supplied accounts need
  not move — `close()`, 5 s quiesce, thread-count check,
  `executorServiceShutdown()` via probes when available); W5b client cycles over the shared
  `HttpClient` then one request on a new client.
- `HarnessControls` (implements `Workload`, added first to the list) owns the client-side half of
  the defect sheet — D7–D11, D13 and D14 — leaving the peer D1–D6 and D12. D7 tombstones a registration
  in the harness's own registry a couple of seconds before its unsubscribe goes on the wire, so
  every delivery inside that gap reaches a cancelled registration and W1-D fails
  (`ws.harness.deliveryToTombstoned`). It is a harness-side control on purpose: sava's own
  tombstones filter a *peer-side* zombie, which is what the F7 fault control demonstrates, so the
  peer behaviour could only ever pass. Also D8 (64 unjoined daemon
  platform threads) and D9 (256 unclosed sockets to the peer's HTTP port), both installed a few
  seconds into STEADY rather than at start-up, because the thread and descriptor baselines are
  taken at the STEADY boundary and an injection before them is baselined away; D10 (from a few seconds into STEADY, retains into a static list in 5 s ticks at the rate that
  fills a quarter of the heap by the end of STEADY, with the cap counted — a fixed rate filled the
  cap during warm-up and the slope over the fitted window read as a plateau), D11 (suppress the
  websocket driver), D13 (in-flight 1 at 200 rps, consulted by `HttpWorkload.create` through
  `HarnessControls.httpInflight/httpRps` — `run.env` still shows the configured values), D14 (a
  fake subject code source: `RunIdentity` captures identity before any workload exists, so
  `soak.sh` passes `-Dsoak.subject.codeSource=file:///nonexistent/fake-subject/sava-rpc.jar`
  when `SOAK_CONTROL=D14`).
- The forced #52 controls: under `SOAK_CONTROL` ∈ {`I1-forced`, `I1-natural`, `I1-jdk25`} the
  `ZERO_INITIAL_ESCALATING` engine is *repurposed* as the forced engine — a fifth `EngineProfile`
  would take the churn workload's port (index 4 of `SOAK_WS_PORTS`) and the oracle slot above the
  four. It runs on `HarnessBackoffs.constantZero()` with a tracker built `forced = true`, so no
  row it emits can meet the revisit trigger, and takes an injected in-adopt retirement on **every**
  attempt up to its ceiling, in all three modes: a client-side control has no other retirement
  source, so a wider step stalls on the first un-injected attempt and the run measures nothing. The
  arming happens once and the ceiling is the tracker's own (§10). `I1-forced` additionally blocks the JUL
  handler on the attempt-failed record until the successor's `buildAsync` is observed and defers
  the build future's completion until `onOpen` returned; `I1-jdk25` leaves the JDK's own
  future-before-`onOpen` order alone.
- `stall-callback` (`HarnessControls.STALL_CALLBACK`, staged in `ConsumerFactory`): the same
  delay into STEADY as the other deferred controls arms a one-off stall, and the next delivery on
  any consumer parks its thread — the subject's own delivery thread — until close, counted as
  `ws.harness.stalledConsumers`. Parked with the factory as the blocker, so a soak frame sits
  directly beneath the wait and the detector's harness exemption (§11) cannot read it as a pool
  thread idling. The engine whose listener is parked stops reading; the peer's queue overflow and
  the retirement that follows are the *consequence* the run also records, never the needle.
- Not yet wired: the live-mode channel allowlist (`WsWorkload` requires four local ports and
  does not read `SOAK_LIVE_WS_URL`); and the
  W4 fan-out's "large" peer response (`SOAK_LARGE_FRACTION` biases the draw towards the two
  large-shaped methods; the peer sizes them from `SOAK_PGA_ACCOUNTS`/`SOAK_BLOCK_TXS`).

## 14. Runner (`soak.sh`) and controls

`soak.sh <profile> [--duration N] [--controls] [--selftest] [--control <id>] [--jfr-maxsize ..]`:
build (`../gradlew jar soakModulePath`, 600 s bound), create the run dir, write `run.env`,
start `PeerMain` (wait 120 s for `PEER_READY`, read ports into `run.env`), write
`fault-schedule.tsv` digest into `run.json`, start the client JVM with the verified flags
(`-XX:+UseG1GC -XX:+AlwaysPreTouch -Xms/-Xmx -XX:NativeMemoryTracking=summary -XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath -XX:FlightRecorderOptions:repository=...,stackdepth=96,old-object-queue-size=4096
-XX:StartFlightRecording:name=soak,settings=...,disk=true,maxsize=...,maxage=...,dumponexit=true,filename=...,method-timing='...',report-on-exit=thread-count,report-on-exit=native-memory-committed,report-on-exit=exception-count
-Xlog:jfr+methodtrace=debug:file=... -Djava.util.logging.config.file=... --add-opens ... -p MP -m ...`,
with `SOAK_JVM_EXTRA` split on whitespace or commas (a `controls.tsv` override is one token, so its
arguments are comma-separated) and appended **last** among the VM options and before
`-p`/`-m`, so a row can override a flag set above it and the value can never land on `Main`'s argv;
it appears in the dry-run launch line and in `summary.md`'s Run section when non-empty),
gate on `[warning][jfr,` in the first 200 stdout lines (INVALID) and on `New filter installed`
plus one `Timing entry added` per filter entry in `jfr-methodtrace.log` (FAIL), wait for
`READY` (120 s), sample RSS/NMT, take `jcmd JFR.view` snapshots every 10 min, cross-watch both
JVMs every second, at STEADY end `JFR.dump` (no roots), at QUIESCE end the final NMT sample
(the client exits on its own once its phases complete, so a sample taken after the monitor loop
cannot attach) then two `GC.run` 5 s apart then `JFR.dump path-to-gc-roots=true` (600 s),
SIGTERM the client (120 s then SIGKILL) and the
peer (60 s), `jfr summary`, `SoakReport` (900 s), verdict from `jfr-metrics.properties` +
`counters.properties` + `properties.tsv` + csv only (never markdown), `summary.md`, `status`,
remove `.running`. Whole-runner watchdog `duration + 1800 s`. Lock file `build/soak/.lock`.

Method-timing filter (every name verified in the sources):
`software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket::connect;...::checkCycle;...::adopt;...::retireConnection;software.sava.rpc.json.http.client.JsonHttpClient::sendPostRequest;software.sava.rpc.json.http.client.JsonHttpClient::readBody;software.sava.services.solana.websocket.WebSocketManagerImpl::ensureWebSocket;software.sava.services.solana.websocket.WebSocketManagerImpl::beginFailure;jdk.internal.net.http.HttpClientImpl::sendAsync`
(validate profile adds `SolanaJsonRpcWebsocket::onText` once to measure its rate). Under `D11`
the websocket driver is suppressed, so `WebSocketManagerImpl` is never loaded and its entries
cannot resolve: the runner drops them from the filter for that control rather than failing the
run on a gate that is measuring the control's own premise.

Verdict outcomes and exit codes: PASS 0, FAIL 1, PASS-WITH-FINDING 2 (every gate passed and
the #52 trigger met), INCOMPLETE 3, INVALID 4. INCOMPLETE is a bounded stage breached (the
client's own exit 3) or a run that stopped outside its schedule: a client exit code the client
never returns itself (a signal's 143 or 137, a ctrl-c's 130), no exit code recorded at all (the
runner never finished), or a `phases.tsv` carrying an `ABORTED` row or no `SHUTDOWN` row — the
shutdown hook writes the counters and the ledger on a kill so a late interruption looks complete
to every other gate, and a copied run with exactly those artifacts re-reported as PASS before the
runner read either signal (review, 2026-09-24; the `interrupted` control row pins it). FAIL
reasons and INVALID conditions: see
`README.md` (from the judged design §9). `#52` never contributes a FAIL. The gates this section
fixes as contracts, because a reason's wording or a denominator is an interface:

- **Thread and descriptor margins** are judged on the **peak** of `client.csv` rows from the
  first STEADY row (the baseline) through the last row before the first SHUTDOWN row, and
  `summary.md` reports `thr_peak`/`fd_peak` beside `thr_last`/`fd_last`. SHUTDOWN rows are
  excluded because `Phases` notifies every workload of SHUTDOWN and samples again before the
  close loop, so a driver that releases threads and sockets on that notification would hand the
  margins back the growth they exist to catch; the last row was measuring teardown. A descriptor
  column of `-1` (no `UnixOperatingSystemMXBean`) is a stated skip, not a comparison of `-1`
  against `-1 + margin`.
- **Starvation** is `harness.opsSkipped.inflightCap / http.ops.attempted` — an HTTP-only
  numerator over an HTTP-only population. `harness.opsAttempted` counts websocket plan steps
  too, and dividing by that mixed population let a starved HTTP driver read as healthy because
  the websocket plan was busy. A missing or zero denominator is a stated NOTE, never a silent
  pass, and a non-zero `harness.opsSkipped.deadline` (the reserved counter) is a NOTE because no
  gate reads it.
- **The DRAIN residual gate reads `overdue_rpc`, not `inflight_rpc`.** What the drain owes is that
  nothing is still running past its **own** bound: *"N request(s) still in flight at the end of
  DRAIN"* now counts operations outstanding past the bound their route promises (the exchange
  deadline plus slack for a default route, the harness bound for the no-wrap route), and requests
  still inside their own deadline are the NOTE *"N request(s) still inside their own deadline at
  DRAIN end"*, which judges nothing. A `client.csv` with no `overdue_rpc` column makes the gate a
  **stated skip** naming itself, never a silent pass. Before the split, a `getProgramAccounts`
  drawn in the closing minutes of STEADY failed a validate run for honouring its own contract.
- **NOTE** on `ws.retirement.overflowCap` and on `ws.retirement.rejectEscalated` non-zero, each naming
  its count and what it is (§8): both are subtracted from the unexplained-retirement gate, and the
  second is the observation the owner is asked to read.
- **NOTE** on `ws.retirement.peerOverflow` non-zero, naming the count: those connections were
  closed 1011 by the peer's own outbound-queue overflow, they are already subtracted from the
  unexplained-retirement gate, and the count is in the NOTES block so it can never contribute a
  FAIL.
- **INVALID** additionally on `harness.peerStats.unavailable` non-zero: the peer's
  `/__soak/stats` was unreadable at shutdown, so `logDropped` is unknown and the record cannot be
  certified complete. **NOTE** on `harness.tsv.dropped` non-zero, naming the count, since every
  table derived from those rows is short by that many. There is no `ws.retiredSubIds.residue`
  gate: nothing writes that counter, and the report states the residue is not measured.
- **FAIL** additionally on `rpc.deadline.missed`: *"N default-route exchange(s) settled past the
  exchange deadline"* — W3-B's bound is the client's own promise, and a run could otherwise print
  an overshoot and still call itself green. The unapplied-kind reason prints one bracketed token
  per kind (§6), matching the report's `fault_kinds_unapplied`, and an already-bracketed value is
  left alone rather than bracketed twice.

The runner's least-squares fits drop `max(SOAK_WARMUP_SECONDS, 60 s, span/10)` of samples,
the same rule as the report's `Slopes`, because the JIT keeps the code cache growing for the
first minute whatever the profile's warm-up says. `AlwaysPreTouch` is there because the RSS slope is a native-retention lens: without it a 512m
heap is committed at start but its pages are first touched as allocation reaches them, which
read as +287 MiB of RSS over the first three minutes of a validate run and failed the slope
gate on heap paging. The pre-touch cuts the other way under memory pressure: macOS compresses the
untouched-again zero pages, resident size drops (measured 678 → 411 MiB inside a warm-up, 681 →
216 MiB on another row) and the climb back as the heap is used fits as growth while NMT's
committed total is flat. The runner therefore judges the RSS slope only when no sample fell more
than the noise floor below the run's first, pre-touched sample; otherwise the slope is a note and
the native-memory and heap-floor slopes carry the retention verdict. Two more rules, both from
the first live runs (2026-09-23): the slope is fitted over STEADY only — quiesce, drain and the
final dump (a full collection with path-to-gc-roots, the NMT and thread dumps) come after it, and
a +58 MiB step there turned a flat 26-minute window into 97 MiB/h — and a rise that NMT
attributes to the code cache plus metaspace over the same window is a note, not a verdict: JIT
compilation and class loading are bounded and are not the subject retaining anything, and a
first run over TLS compiles code no local run ever touches (22 MiB of code cache in 30 minutes).
Whatever remains above the floor is still a FAIL, with both numbers in the reason.

`controls.tsv` (`id	kind	expected	property	overrides	metric	mechanism`): D1–D14, F1–F8,
I1-sync, I1-forced, I1-natural, I1-jdk25, I2, I3 as described in `README.md`. The two asserting
columns:

- `property` is a property id, which the run's own `properties.tsv` must show failed or
  UNEXERCISED; or a `key=value` pair, which `jfr-metrics.properties` must carry; or any other
  substring, which must appear in `summary.md`'s **verdict-bearing prose** (the INVALID /
  INCOMPLETE / FAIL reason lines, never the ledger dump every run prints — grepping the whole
  file scored every defect control a hit whatever the verdict). A FAIL reason's wording is
  therefore an interface.
- `metric` is an expression over the run's own machine-readable artifacts, every term of which
  must hold: `<key><op><value>` over `jfr-metrics.properties` (`>= <= != == = > <`, numeric when
  both sides are numbers, exact string otherwise), `present:<key>` (measured: neither empty nor
  `n/a`), `schedule:<KIND>` (`fault-schedule.tsv` plans that kind), `peer:<LINE>` (`logs/peer.log`
  carries exactly that line). A missing file or key is a miss: the row claimed a measurement and
  there is none.

**Both columns are scored on PASS rows too**, not only on FAIL/INVALID ones — a PASSing run has
no verdict prose, so an injection that quietly did nothing could otherwise pass by producing a
clean run. `kind` is `defect` | `fault` | `i52` | `selftest`; a `selftest` row runs
`soak.sh --selftest` bounded at 300 s instead of a soak and is scored on its exit code alone
(`I1-sync` and `I2` are that kind), and `SOAK_DRY_RUN=1 soak.sh --selftest` prints its launch
line and starts nothing, which the "starts nothing" promise now requires.

`soak.sh --controls` runs each row as its own `control` profile run into
`runs/controls-<stamp>/<id>/`. The sheet sets no duration of its own: a row is a `validate` run plus
its `overrides` column, so it costs the validate duration — plus that row's peer and client start,
final JFR dump and `SoakReport` pass — times the number of rows. That is the price of measuring a
control under the knobs it guards; the shorter row it replaces was cheap and was evidence about a run
nobody performs. The sheet runs over its own copy of the rows, `controls.tsv` in the sheet
directory, both as provenance and because an edit to the source file during a three-hour campaign
was read mid-line by the row loop (measured: a phantom row). Each writes `controls.md` (expected vs
actual, `seen`, `met`, `ok`)
beside `controls.env`, the machine-readable half carrying the miss count and the missed ids; any
miss exits non-zero. `soak.sh --controls-rescore <controlsDir>` re-judges an existing sheet
without re-running it: a run directory is the evidence and a verdict is the runner's reading of
it, so a verdict rule that changed (a gate's floor, a FAIL that became a note) is re-applied
through `soak.sh --report` per row and the same scoring, the sheet says it was re-scored and when
(`CONTROLS_RESCORED_AT` in `controls.env`, each row's original exit code kept beside the new one;
a row a launch gate ended before `run-exit.env` was written is kept at its recorded verdict, since
the gate's reading is the run's and cannot be re-derived from artifacts it never produced), and
nothing a row measured changes — a code change under `sava-rpc` still needs a fresh sheet. A
campaign records the controls run id and is INVALID `stale controls`
when the newest sheet is **red** (a miss, or no `controls.env` to say otherwise), predates the
sava revision under test, or predates an uncommitted edit under `sava-rpc/src/main` — recency
alone says nothing about whether the oracle could see a defect. Both halves are judged against
the subject the run *saw*, the launch-time stamps `run.json` records (`subjectCommitTime`,
`subjectNewestMtime`), never against the tree at report time: a commit made while a campaign
runs was not under test (measured 2026-09-23, when four library commits landed during a campaign
launched on the very revision its sheet was run on). A run from before the stamps is dated by
its `gitRev`, and its uncommitted half is a note, not a verdict. The sheet's side of the
comparison is when its rows were *measured*: the launch of its oldest row (each row's
`run.json`), so a row re-run later refreshes only itself and the sheet stays as old as the oldest
row it carries — never `controls.md`'s modification time, which a re-score rewrites without
measuring anything (review: rewriting the Markdown turned a stale-controls rejection into an
acceptance).

What the `F*` rows plan, since a restricted schedule is part of each row's claim (§6), stated in
seconds of expected traffic on the kind's own counter: F1 `STALL_BODY` once per hold-length divided
by the holds the peer can park (`STALL_HOLD_SECONDS / (peerHttpThreads / STALL_HOLD_SHARE)`, so
every planned stall is one the peer can stage — a denser row counted its own over-budget stalls
against fidelity); F2 `SWALLOW_PING` every 10 s of connection traffic, i.e. every connection, which is the
density the two-phase probe needs; F3 `SWALLOW_SUBSCRIBE` every 10 s of subscribe traffic, so
each four-resend escalation has its own window; F4 `TCP_RESET` every 5 s, the storm the row
names; F5 `MESSAGE_OVERFLOW` every 10 s on ws port index 3 only, the `OVERFLOW` engine and the
only place W2-B is evaluated; F6 `CONNECT_REFUSE` every 20 s, i.e. every other connection —
refusing them all would leave the ws properties UNEXERCISED rather than testing reconnect
pacing; F7 `ZOMBIE_NOTIFICATION` every 10 s; F8 `HTTP_503` in bursts of 8 consecutive request
ordinals every 20 s, so "the calls after the burst succeed" has a burst and an after. Each with
**nothing else planned**. A CONN-scope `F*` row also widens the `conn` domain to the placements it
asks for: that domain is derived from the run's *destructive budget* (§6), and one of these rows
deliberately retires a connection every few seconds outside it, so a plan that stopped at the
derivation would stop injecting a fraction of the way in. `I1-*` and `I2` are contractually peer-fault-free and `I3` is
contractually the ordinary schedule. Every control run's schedule digest moved when these landed,
so a `controls.md` written before them describes plans that no longer exist.
