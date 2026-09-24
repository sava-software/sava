# sava RPC soak harness

Opt-in, long-running soak harness for **this library's own** HTTP and websocket RPC
clients. sava-rpc parses account data, transactions and RPC responses that arrive from
untrusted nodes, so hardening it means driving those parsers and their connection
lifecycles hard, over hours, against controlled local peers that inject faults on a
recorded schedule — malformed frames, truncated bodies, resets mid-handshake, swallowed
subscriptions, notifications for ids the client already cancelled. That is the point: a
peer that behaves that way exists, and the harness gets there first.

It also collects the decision evidence GitHub issue #52 asks for — whether one websocket
retirement can be charged to the wrong connection attempt — by exercising ravina's
`WebSocketManagerImpl` around sava, from the outside, through public seams only.

Findings land as deterministic regression tests in the owning module, never as changes
here to make a run pass (`../AGENTS.md`). This is an operational tool: it is **not** part
of `check`, PIT or fuzzing, it is not published, and it is not a release gate.

`DESIGN.md` is the implementation contract — packages, ownership, config keys, wire
formats, artifact formats. This file is how to run it and how to read what it produces.

## Build shape

A standalone Gradle build, exactly like `../jmh`: `includeBuild("..")` makes the composite
substitute `software.sava:sava-rpc` and `software.sava:sava-core` — including the copies
`ravina-solana` asks for transitively — with the **local** projects, so a soak always runs
against the working tree. `ravina-solana` itself resolves from GitHub Packages at the
version the `solana-version-catalog` platform pins, and the platform version is read from
`../gradle/sava.properties` rather than repeated here.

No sava-build plugin is applied. AGENTS.md requires the three sava-build pins (root
settings x2 and `jmh/build.gradle.kts`) to move together; a fourth pin here would be one
more thing to forget, and this build needs nothing sava-build provides.

### Why the sources live in `src-main/java`

The root build registers repo-root subdirectories as subprojects with gradlex
`java-module-dependencies`, and its rule is: a direct subdirectory is auto-included **iff**
it contains at least one `src/<sourceSet>/java/module-info.java`. A modular harness at
`soak/src/main/java/module-info.java` is therefore picked up by the root build as `:soak`
— verified: the root build then fails configuration outright with
`repository 'MavenRepo' was added by build file 'soak/build.gradle.kts'`. Renaming the
source root sidesteps the rule without touching the root `settings.gradle.kts`, because the
listing only ever looks under `<dir>/src/`. **Do not create `soak/src/`.**

### JDK selection

Toolchain detection alone takes whichever JDK 25 it finds first — on the development
machine that is GraalVM CE 25, not the `openjdk-25.0.2` whose JFR behaviour every fact
below was measured against. The toolchain is therefore vendor-pinned by default, with
escape hatches:

- `-PsoakJvmVendor=<match>` — a different vendor string, or `any` to take what is detected.
- `-PsoakJavaHome=<JAVA_HOME>` — launch a different JDK entirely (the compile toolchain is
  unaffected; use it to re-check a finding on another release).

`soakModulePath` writes `build/soak/module-path.txt` and `build/soak/java.txt`, which is how
`soak.sh` starts both JVMs itself — detached, with their own recording and native-memory
flags — instead of inheriting Gradle's daemon environment. Every run records which JDK it
used in its own `run.json`.

## Running

```sh
cd soak
./soak.sh --selftest              # attribution and detector self-test; green before anything else
./soak.sh --controls              # every row of controls.tsv; green before a pilot or campaign
./soak.sh validate                # minutes: shake out the instrumentation
./soak.sh pilot                   # an hour at production timings
./soak.sh campaign                # eight hours; run it in the background
./soak.sh --report <runDir>       # re-run the report and the verdict over an existing run
./soak.sh --help
```

Options: `--duration N`, `--jfr-maxsize S`, `--jfr-maxage S`, `--seed X`, `--heap-mb N`,
`--control <id>`. Any `SOAK_*` key from `DESIGN.md` section 5 overrides its profile default
from the environment; every resolved value is written to the run's `run.env`, which both
JVMs read and where an unknown `SOAK_*` key is a startup error for the client (the runner's
own `SOAK_DRY_RUN`, `SOAK_SKIP_BUILD` and `SOAK_NO_LOCK` are the only `SOAK_*` names the client
ignores).

Two of those keys behave less simply than the rest:

- `SOAK_JVM_EXTRA` is appended verbatim to the **client** JVM's own command line, split on
  whitespace, and is empty on every profile. It is a per-row measurement aid — `D10` needs more
  collections than its heap gives it in a short run — and never campaign tuning: a run that shaped
  the subject's GC would be measuring a JVM nobody deploys. Whatever it holds is visible in the
  run's `run.env`, in the launch line `SOAK_DRY_RUN=1` prints, and in `summary.md`'s Run section.
- `SOAK_DESTRUCTIVE_PER_HOUR` is not only a cap on retiring faults: it also sizes the `conn`
  trigger domain, so lowering it narrows which connection ordinals a fault may be keyed on at all,
  and a value far below `SOAK_FAULT_MIN_PER_KIND × (CONN kinds) / ports` starts omitting CONN kinds
  outright (they appear in the peer's `SCHEDULE_OMITTED` line). The runner's own `by_profile` row
  wins over the compiled default for any run `soak.sh` starts, so the two tables must move together.

A first validate run takes about four minutes end to end: the build, the peer, 150–240 s of
phases, a 30 s root-walk dump and the report. `--duration 150` is the shortest run whose STEADY
still fits a fault storm. The OVERLAP fan-out is placed at half of STEADY when that is sooner
than its own 300 s period — the same rule the storm windows use — so a validate or control run
does fire one and W4-C is evaluated rather than skipped; the HTTP quiet/loaded alternation
(120 s) still wants the full 240 s or a pilot to alternate more than once.

`SOAK_DRY_RUN=1` resolves everything, prints the `run.env` it would write and both launch
lines, and starts nothing — the fastest way to see what a profile actually does.

The runner takes `build/soak/.lock`: two soaks in one repository would fight over the build,
the recording repository and the jcmd attaches. Never start a second Gradle invocation in
this root while one is running.

### Profiles

| | validate | pilot | campaign |
|---|---|---|---|
| duration / warm-up | 240 s / 30 s | 1800 s / 360 s | 14400 s / 900 s (release check: `SOAK_DURATION_SECONDS=28800`) |
| heap (`-Xms` = `-Xmx`) | 512m | 1024m | 1024m |
| recording ring / max age | 256m / 2h | 256m / 2h | 1g / 5h (256m per hour of run, age = hours + 1; a 28800 s run gets 2g / 9h) |
| socket event threshold | 0 ms (per frame) | 20 ms (tail) | 20 ms (tail) |
| websocket timings | shortened, recorded | sava defaults | sava defaults |
| fault rate (ops between faults) | 20 | 120 | 300 |

`validate` uses `config/sava-soak-validate.jfc` and `config/soak-logging-validate.properties`
and adds `SolanaJsonRpcWebsocket::onText` to the method-timing filter, once, to measure its
call rate. That entry never joins a campaign: timing a method called millions of times per
second was measured costing about an eighth of all execution samples inside JFR's own
`TimedMethod.updateMinMax`.

`campaign` is not heavier than `pilot` — it is longer. Before the first one, run the
rollover rehearsal (`./soak.sh pilot --duration 7200 --jfr-maxsize 32m --jfr-maxage 30m`),
which makes the ring roll so the whole-run counters become load-bearing rather than
hypothetical.

### Live runs

`./soak.sh live` drives a real provider. It has **no defaults**: every `SOAK_LIVE_*` value is
required and the run refuses to start without it, mainnet additionally requires
`SOAK_LIVE_CONFIRM=mainnet`, and no local peer is started. The client reads `SOAK_LIVE=1` and
omits the websocket attempt-ordinal header (`X-Soak-Attempt`) entirely. Nothing of the
harness reaches somebody else's node (decided 2026-09-23): the `X-Soak-Call` nonce and the
`X-Soak-Probe` marker exist for the local peer's log and fault schedule, so a live run sends
neither, and the extended subject passes through `extendRequest` with an identity operator so
that seam stays exercised. Every peer-established (grade C) property is `NOT_EVALUATED` with
that reason stated, because nobody else's node is an oracle. Two more things a live run does
differently: the plan's generic channel (sava's caller-defined subscribe API, exercised against
the local peer's synthetic `transactionSubscribe`) is skipped and counted
(`ws.harness.liveGenericSkipped`), because a real node has no such method; and
`pending_confirm` reads the engine's own grants (`Subscription.subId()`) rather than the peer
log it does not have.

```sh
SOAK_LIVE_HTTP_URL=... SOAK_LIVE_WS_URL=... SOAK_LIVE_CLUSTER=devnet \
SOAK_LIVE_RPS=5 SOAK_LIVE_CONCURRENCY=4 SOAK_LIVE_ACCOUNTS=keys.txt ./soak.sh live
```

The recording settings disable `jdk.InitialEnvironmentVariable`, `jdk.InitialSystemProperty`
and `jdk.SystemProcess` for **every** profile, not only this one: a default JFR recording
stores environment variables, system properties and every command line on the machine
verbatim, which was measured putting an API key into a `.jfr` people attach to issues.

### Negative controls

`./soak.sh --controls` runs every row of `controls.tsv` as its own bounded run and
writes `controls.md` with expected against actual; any miss exits non-zero. A row is a
`validate` run plus its own overrides column — the sheet sets no duration of its own, because a
control is the negative of the validation it guards and is evidence about it only when it was
measured under the same knobs — so the sheet's wall-clock cost is roughly the validate duration
times the number of rows, plus each row's peer and client start, final dump and report pass. A
green campaign is only evidence if the oracle could have seen a defect, so:

- **defect controls** break the client's correctness (the peer answers a replayed subscribe and
  records nothing about it, duplicates or reorders notifications, reports a program notification
  under a key the subscription was never granted, flips a byte inside a continuation frame,
  diverts another connection's sequence onto this one, serves a different body under gzip than
  under identity; the harness tombstones a registration before its unsubscribe reaches the wire,
  or parks one notification consumer on the subject's own delivery thread for the rest of the
  run) and must FAIL **on the named property**, read out of the run's own
  `properties.tsv` — a run that failed for an unrelated reason is not counted as a hit. Each one
  stages the *evidence its oracle reads*, not a peer behaviour a correct client neutralises: a
  defect control that relies on a correct client failing to defend itself is not a control. That is
  why the replayed-subscribe row answers on the wire and logs nothing rather than staying silent
  (silence is a request the engine escalates out of, which supersedes the episode), why the
  dedupe row writes the duplicate request row itself, and why the zombie-delivery row moved into
  the harness — sava's own tombstones filter a peer-side zombie, so the peer version could only
  ever pass;
- **verdict-machinery controls** break the parts no amount of client correctness can
  compensate for: leaked threads, leaked descriptors, a static retention drip, a suppressed
  driver, a fault kind that is never applied, a starved harness, a run pointed at the
  published jar instead of the working tree, and a deliberately misspelled method-timing
  entry;
- **fault controls** inject faults the client is supposed to survive and must PASS. Each one
  replaces the whole schedule with its own kind at a stated density and nothing else, so the
  property it names is read against that fault alone rather than against whatever the ordinary
  schedule happened to land beside it; the `no-faults` row is the other end of that idea — rate
  0 really does plan nothing, and the row asserts both an undefined fidelity and a peer that
  printed `SCHEDULE_PLANNED=0`, because a rate of 0 that quietly planned a dense schedule would
  also have passed;
- **#52 controls** cover the synchronous negative control and its positive control, the
  forced reproduction, the natural (unblocked) repetition rate, the JDK-25-shaped ordering
  control, the detector's synthetic self-test, and the measurement the issue actually asked
  for, which asserts no expectation at all. Two of them are `selftest` rows: they run
  `soak.sh --selftest` under a bound and are scored on its exit code rather than on a soak.

Every row also carries a `metric` column — an expression over the run's own machine-readable
artifacts (`jfr-metrics.properties` keys, `fault-schedule.tsv` kinds, exact `peer.log` lines) —
and both it and the `property` needle are scored on **PASS** rows as well as failing ones, so an
injection that quietly did nothing cannot pass by producing a clean run. `controls.md` reports
`seen` and `met` beside `ok`, and `controls.env` carries the same verdict machine-readably.

A campaign is `INVALID` with the reason `stale controls` when the newest sheet is red (any
control missed, or no `controls.env` to say otherwise), predates the sava revision under test,
or predates an uncommitted change under `sava-rpc/src/main`. A controls run is evidence about
this subject only if it was green **and** it saw this code. The subject is the one the run
*saw*: `run.json` records the source's commit time and newest file time at launch
(`subjectCommitTime`, `subjectNewestMtime`), and the report judges the sheet against those, so
a commit made while a campaign runs does not invalidate it (measured 2026-09-23). A run from
before those stamps is dated by its `gitRev` and carries a note that an uncommitted edit at
launch cannot be excluded.

A sheet is measurement plus judgment, and only the judgment belongs to the runner. When a verdict
rule changes (a gate's floor, a FAIL that became a note) and the code under test did not,
`./soak.sh --controls-rescore build/runs/controls-<stamp>` re-derives every row's verdict from
the artifacts that row recorded (`soak.sh --report` per row) and re-judges the sheet with the same
scoring, marking it re-scored in `controls.md` and `controls.env` and keeping each row's original
exit code beside the new one. Nothing a row measured changes, so a change under `sava-rpc` still
needs a fresh sheet; what this removes is a three-hour re-run to move one row's reading.

## Artifacts

Every run writes `build/runs/<profile>-<yyyyMMdd-HHmmss>/`:

| path | writer | what it is |
|---|---|---|
| `run.env` | soak.sh | `KEY=VALUE`, first line the exact command that produced the run |
| `run-exit.env` | soak.sh | start, finish and both exit codes, written when the processes have exited; `--report` reprints them from here instead of inventing them |
| `run.json`, `run-client.json` | soak.sh, Main | git rev and dirty flag, JDK, GC, `.jfc` digest, schedule digest, module path (runner); subject and consumer code sources, the timings actually used and every resolved key (client) |
| `status` / `.running` | soak.sh | one word; `.running` is removed only once `summary.md` is complete |
| `config/` | soak.sh | the exact recording and logging settings used, copied in |
| `logs/` | both JVMs | `client.log`, `peer.log`, `jvm-stdout.log` (the launch-warning gate), `jfr-methodtrace.log` (the filter gate), `jfr-dump.log`, `soak-report.log` |
| `jfr-repo/`, `jfr/` | JVM, jcmd | the chunk repository, then `soak-steady.jfr`, `soak-final.jfr` (root walk) and `soak-exit.jfr` (dumponexit, the fallback) |
| `views/<elapsed>.txt` | soak.sh | `jcmd JFR.view` snapshots every ten minutes — mid-run evidence with no ring dump |
| `fault-schedule.tsv`, `faults-applied.tsv` | PeerMain | what was planned and what actually landed, with fidelity |
| `peer-ws.tsv`, `peer-http.tsv`, `peer-summary.json` | PeerMain | every frame and every response the peer wrote |
| `client.csv`, `counters.properties`, `properties.tsv`, `phases.tsv` | Main | the gauge series, the whole-run totals, the correctness ledger, the phase boundaries |

`client.csv` carries `overdue_rpc` beside `inflight_rpc`, and the pair is worth reading carefully:
`inflight_rpc` is the harness's own load, bounded by its permits, while `overdue_rpc` is the late
subset — outstanding past the bound that operation's own route promises (the client's exchange
deadline plus slack for a default route, the harness's bound for the caller-body-handler route,
which carries no library deadline). A long `getProgramAccounts` drawn near the end of STEADY is
still in flight when the drain ends *by contract*, so only `overdue_rpc` is a residue. Everything
that reads this file reads it by header name, never by column position.
| `issue52/`, `issue52.md` | Main, SoakReport | retirements, claims, and the per-retirement table behind the `ISSUE52:` line |
| `findings/finding-NN/` | Main | one per failed property: `note.md` with the grade, the guarantee and the first example (the replay input, frame capture and recording window are not written yet) |
| `rss.csv`, `nmt.csv`, `nmt/` | soak.sh | the two process-wide retention series |
| `jfr-summary.txt`, `jfr-views.txt` | soak.sh | `jfr summary` and the final `jfr view`s over the recording the report used |
| `jfr-report.md`, `jfr-metrics.properties` | SoakReport | the recording, read once, beside the whole-run artifacts |
| `summary.md` | soak.sh | the verdict and every number behind it |

## The verdict

Five outcomes, and the exit code is the outcome:

| exit | status | meaning |
|---|---|---|
| 0 | `PASS` | every gate passed |
| 1 | `FAIL` | the library, or the run, did something it promised not to |
| 2 | `PASS-WITH-FINDING` | every gate passed **and** the issue #52 trigger was met |
| 3 | `INCOMPLETE` | a bounded stage was breached; the run says nothing either way |
| 4 | `INVALID` | the run cannot be judged at all |

`INVALID` outranks `INCOMPLETE`, which outranks `FAIL`. The verdict is computed from
`jfr-metrics.properties`, `counters.properties`, `properties.tsv`, `client.csv`, `rss.csv`
and `nmt.csv`, plus the launch gates and the process exit codes. It never parses a Markdown
report, and no `FAIL` reason may come from a number only the recording can supply — the ring
is bounded, so on a long run it holds only the last hours. Ring-only numbers appear as
notes, labelled.

**FAIL** — any grade A, B or C property failed, or was `UNEXERCISED` with no stated skip; a
request/response mismatch; a default-route exchange that settled past the client's own exchange
deadline; a virtual-thread submit failure or a `jdk.JavaErrorThrow`; a
retirement with no fault to explain it; a workload that drove nothing; `OutOfMemoryError`,
a heap dump, or `Exception in thread` in the client log; fault fidelity below 0.90, or a
scheduled fault kind never applied (each named as its own bracketed token, so a control can
assert the kind and not just the sentence); a recovery record over budget (a window the next
connection-level fault displaced while still inside its budget carries no verdict and is
counted as `recovery.superseded` instead); threads, descriptors or
unfinished virtual threads past their margins (threads and descriptors judged on the peak
between the start of STEADY and the last row before SHUTDOWN, because the rows after SHUTDOWN
credit teardown for the growth the margin exists to catch, and a descriptor count of `-1` is a
stated skip); an RSS, gated native-memory or after-GC heap
floor slope past its limit (the RSS slope is a note, not a verdict, when resident size fell more
than the noise floor below the run's first, pre-touched sample: macOS compresses idle pages, a
pre-touched heap's zero pages first, and a JVM that then uses them reads as growth, so the
native-memory and heap-floor readings are the retention gates that do not see the OS; the heap
floor is read after full collections only — a young or a mixed pause's after-heap carries what the
old generation has not yet given back — and the harness forces one full collection at each end of
STEADY, so that gate is the rise between those two readings against the heap floor's own noise
floor (`SOAK_HEAP_FLOOR_NOISE_KIB`, separate from RSS's, which carries a JVM's start-up drift),
while the slope the report fits is informational; each least-squares fit drops the warm-up, at
least 60 s or a tenth of the window; a fit
with too few surviving samples is `n/a` and a note, never a `0` that reads as a measured zero,
because refusing to fit a start-up ramp is what keeps that number honest);
registrations still retained at the end of the drain, or
requests still outstanding past their own bound (the drain gate reads `overdue_rpc`; requests
still inside their own deadline are a note and judge nothing, and a `client.csv` without that
column makes the gate a stated skip rather than a silent pass); a thread whose significant frame is
unchanged across three
consecutive thread dumps **and** which shows no sign of having run in between (the frame alone
reported sava's own check loops, which park for their whole life and are healthy — and only a named
list of harness *driver* threads is exempt: `soak-hc-N` is named by the harness but is the executor
sava's websocket listener and the notification consumers run on, so a park there is the subject's
stall and is reported under its own name); a
method-timing entry that resolved to nothing; no recording; a failed report; and
the three #52 harness-health gates (claims that could not be routed, claims with an unknown
origin, and double claims with no successor activity between them — that last one is a
harness bug, not a library finding).

**INVALID** — the harness skipped more than its own limit of its HTTP operations, counted
against the HTTP driver's own draws (it was starved, so the subject was not driven); the peer
dropped log lines, or its `/__soak/stats` could not be read at shutdown so whether it dropped
any is unknown (either way its record of what it did cannot be certified complete); the client
refused to start because the subject was not the working tree, the
schedule digest did not match, or a self-test failed; the JVM warned about the recording
options at launch; or a campaign with stale controls.

**NOTES** are reported beside the verdict and can never contribute a `FAIL`. Two of them come up
often enough to name here: connections the *peer* closed 1011 because its own per-connection
outbound queue filled — an `overflow` row in `peer-ws.tsv` is the peer admitting it closed the
socket, so a retirement standing next to one is not the client's, and those retirements are already
subtracted from the unexplained-retirement gate — and the overdue-request gate reporting itself as a
stated skip when `client.csv` carries no `overdue_rpc` column. Two more retirement classes are
notes for the same reason: the overflow engine enforcing its own configured message cap on a
message the schedule did not send (the peer's periodic large notification is above that cap), and
an engine escalation of a subscribe the peer had refused `-32603` as unanswered — the refusal is
the cause, and whether a re-send should have preceded the escalation is the observation the note
asks the owner to read (`REJECT_ESCALATED` anomaly rows).

**Grades.** Only A (a public contract), B (a documented invariant) and C (something the
controlled peer establishes) can fail. D is measurement and never fails a run. The named
non-properties carry grade `NONE` and are listed so that "we deliberately do not assert
this" is written down rather than assumed — notifications may legitimately gap across a
reconnect, and a run that treated that as a defect would be reporting the protocol, not a
bug.

**Issue #52 never contributes a FAIL.** A misattribution under a positive, escalating
backoff without harness forcing is `PASS-WITH-FINDING` and an `ISSUE52: REVISIT` line: a
finding to take to the owner, not a broken harness, and not a run the library failed.

## What JDK 25.0.2 actually does

The plan arrived carrying instrumentation facts measured on JDK 26 in another project. Six
of them do not hold here and the design was corrected on all six:

1. **`jdk.ThreadSleep` does fire for virtual threads.** `Thread.sleepNanos` brackets both
   the virtual and the platform branch. It is kept enabled — the one cheap blocking event
   that survives virtualisation. (`jdk.ThreadPark` genuinely does not: `LockSupport` branches
   to `parkVirtualThread` before the VM event, so park-blocking on a virtual thread is
   invisible and `jdk.MethodTiming` on the suspect method is the substitute.)
2. **`jdk.VirtualThreadPinned`'s default threshold is 20 ms, not 1 ms.** The settings file
   lowers it explicitly. On this JDK monitors no longer pin, so any hit is a real finding —
   a native or VM frame — rather than noise.
3. **`old-object-queue-size` is a `-XX:FlightRecorderOptions` option.** On
   `-XX:StartFlightRecording` it is warned about and silently ignored. The asymmetry matters:
   `FlightRecorderOptions` fails the JVM on an unknown key, `StartFlightRecording` only
   warns — which is why the runner greps the JVM's first output lines for `[warning][jfr,`
   and calls the run `INVALID` rather than soaking for eight hours against a quietly wrong
   recording.
4. **The `Alt-Svc` `URISyntaxException` noise floor does not exist.** There is no `Alt-Svc`
   code in `java.net.http` on 25.0.2 at all. Nothing is suppressed and nothing is
   pre-registered as expected noise; the validate run measures this JVM's own floor.
5. **The G1-versus-ZGC `jdk.OldObjectSample` rule did not reproduce.** Yield swung across
   heap sizes for both collectors on the same leak program. Runs use the machine default
   (G1), name it explicitly so the record is self-describing, and treat a sample count of
   zero as `INCONCLUSIVE` — never as "no leak". The `D10` control is the viability proof for
   whatever heap the campaign actually uses.
6. **`jdk.SocketRead` and `jdk.SocketWrite` carry a throttle as well as a threshold**, and
   the throttle is the real cap. Dropping the threshold alone changes nothing above the
   default rate. The settings file raises the throttle and drops the stack, which is the
   same three JDK frames every time.

Three things nobody asked about changed the design more than any of those:

- **A default recording is a credential leak.** Environment variables, system properties and
  every process command line on the machine are recorded verbatim. Four events are disabled
  for every profile, which also removes roughly half a megabyte per chunk.
- **`HttpClient.sendAsync`'s `thenApply` stage runs on `ForkJoinPool.commonPool()`** — where
  sava's `JsonHttpClient` parses every response — and that is the same pool sava uses to
  schedule its own response deadlines. Large parses and tight timers share one resource, so
  the gauge records commonPool parallelism, queue depth and active count, and the W4-C
  sub-workload deliberately overlaps big `getProgramAccounts` parses with 500 ms request
  timeouts.
- **`WebSocket.Listener.onText`/`onBinary` run on the HttpClient's executor**, which is where
  sava reassembles and parses, so a slow consumer starves the client directly. The harness
  gives the shared client a **named platform** executor so that work stays visible to
  `jdk.ThreadPark` and `jdk.ThreadDump`. `onOpen` does **not** run there — it was measured on
  a commonPool worker — so no thread name is ever asserted for it, and every attempt records
  whether its future settled before or after `onOpen`.

Two more that shape the runner: `jdk.MethodTiming`'s `invocations` is **cumulative since
recording start**, so the report diffs consecutive rows and never sums them; and a bad
method-filter entry is accepted **in silence**, which is why every entry must produce a
`Timing entry added for <entry>` line in `jfr-methodtrace.log` before the load starts, and
why `controls.tsv` carries a `filter-typo` row whose whole job is to prove that gate fires.

## How a finding becomes a regression test

1. A `FAIL` writes `findings/finding-NN/note.md` (property id, grade, the guarantee, the
   first example; up to three examples travel in `properties.tsv`). The replay input
   (`replay.json`: seed, profile, dialect, fault ordinal and kind, connection ordinal, key,
   engine timings), the peer's frame capture (`frames.bin`, from `/__soak/capture`) and the
   recording window (`window.txt`) are the intended next step and are not written yet; today
   the same facts are recovered by joining the example's `engine`/`attempt`/`msgId`/`subId`
   against `peer-ws.tsv`, `faults-applied.tsv` and `issue52/retirements.tsv`, and the
   validate profile's `client.log` carries every frame the engine wrote at FINE.
2. `SoakReplay replay.json` (not written yet) is meant to re-run *only* that scenario — one
   engine, one key, one connection, that one fault — bounded, and report reproduced or not. A
   finding that does not reproduce is a flake candidate and is **not** promoted.
3. The regression test is written **in sava-rpc's own test source set**, never here:
   `sava-rpc/src/test/java/software/sava/rpc/json/http/ws/` using the existing
   `RecordingWebSocketBuilder` / `RecordingWebSocket` / `TestClock` fakes, with the captured
   frames as literal input, fixed seeds and no sleeps. If the finding is parser-shaped the
   frame also becomes a committed seed under `sava-rpc/src/test/resources/fuzz/`, replayed by
   the corpus test inside `check`.
4. Per `../AGENTS.md`'s published-library rule: state the property and its independent
   oracle; if the oracle contradicts current behaviour, **pin it with a test, document it at
   the declaration, and report it** — do not change what an existing signature returns. Then
   run the owning mutation suites as reachability dictates.
5. The soak harness itself is never the regression test.

No finding is reported to the owner until it reproduces and its frames have been read. A
hand-written RFC 6455 peer is a new, untested thing standing between the subject and every
conclusion; a framing bug in the harness would look exactly like a client defect. The first
validate runs bore this out: of seven properties that failed on the first run, none was the
library's — a peer log label, a view keyed on a per-port connection id, a token bucket that
burnt the slots it refused, one-shot signature subscriptions counted as owed a replay, and an
overflow fault landing on engines whose cap it did not exceed. Each is recorded in
`DESIGN.md` §8 as the oracle decision it forced.

## What the harness cannot see

The #52 evidence is recovered from the outside, through public seams. That works, and its
limits are the argument for the API the issue proposes:

- **Fenced claims are silent.** The manager logs nothing when it declines to charge a
  failure, so "fenced" is inferred from the frame — the route demonstrably ran and no
  backoff call followed — not observed.
- **The manager's private copy of an attempt is invisible.** Whether a successor's install
  succeeded, and whether a misattributed claim cancelled it, are inferred from the state
  timeline and the successor's outcome.
- **Attempts that never build have no identity.** One superseded before `buildAsync`, or
  killed by `close()` while pending, never reaches the builder seam; it appears only as a
  claim with no ordinal.
- **Rejoin is invisible.** `connect()` returning a copy of an unsettled attempt calls no
  builder method; the harness infers it from a later acknowledged open of an older ordinal.
- **Cause is parsed, not typed.** Escalation causes come from exception message prefixes and
  log text. A wording change silently reclassifies rows.
- **Same-thread synchronous delivery is an implementation fact, not a contract.** The whole
  attribution rule depends on retirement being a single funnel with no executor hop before
  the user callback. The self-test and a `StackWalker` classifier pin it to this engine
  version and fail loudly on drift; they cannot make it a promise.
- **Observer effect.** Log handlers, `StackWalker` and event commits on the retiring thread
  widen the very gap being measured, so every run reports how much of the gap was the
  harness's own time, and blocking handlers are used only in explicitly forced controls.
- **The forced reproduction is forced by construction.** On JDK 25 the build future settles
  before `onOpen` runs, so a peer-driven retirement has no future route at all; realising the
  residual condition needs a deferred completion, an injected in-adopt retirement and a
  blocking handler. The injection is armed once, as a series of attempt ordinals fixed at arming
  time, and is refused outright on an engine that is not forced, so an injected retirement can never
  be read as natural evidence; the fired count and whether the arming hit its ceiling
  (`injectedRetirements`, `injectionCapReached`) are both reported, because an injector that stopped
  at its budget and one that never fired otherwise leave the same empty win rate. The natural win
  rate it measures is a property of this machine's timing, not of production.

**What `connectAttempt()` would settle.** The proposed additive
`ConnectionAttempt { ordinal(); connected(); retired(); }` with an exactly-once `retired()`
that settles after `connected()` and before the legacy callback, and a sealed cause, turns
every inference above into contract: origin identity, the future-versus-callback double
observation, the superseded and closed silences, rejoin, and cause classification. The
harness's report columns are deliberately shaped so each one can be compared one-to-one
against that handle if it is ever built. **Do not implement it to make the harness work** —
the harness exists to produce the evidence for deciding whether to.

## Owner decisions still open

- Whether `--add-opens` into sava-rpc's websocket package stays on for local runs. It is
  launch-only and reads package-private test probes; every number derived from it is grade D
  and reports a stated skip when the flag is absent. Off by default would turn `W5-B2` and
  the retained-registration gauges into stated skips.
- Whether `soak/` is tracked in the repository, like `jmh/`, with a short AGENTS.md paragraph
  mirroring http-servers' "operational tool outside the hardening gates".
- The campaign's socket-event setting, after the validate run measures the socket share of
  the ring: per-frame visibility, or tail-only. The default stays tail-only until measured.
- The live-run parameters — cluster, endpoint, rate, concurrency, keys — and whether any
  `X-Soak-*` header may ever reach a provider. Nothing is compiled in.
- Whether `MESSAGE_OVERFLOW` and `DUP_SUBID`, which retire a connection deliberately, belong
  in the campaign at all or only in validation.
- Where findings in json-iterator or ravina are recorded. They are separate projects that own
  their own tests; a soak task never edits them.
