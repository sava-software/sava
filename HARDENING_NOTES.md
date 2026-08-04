# Per-suite hardening notes

Scope decisions and deliberate exceptions for the mutation suites. The process
contract is in `AGENTS.md`; the policy behind it is sava-build's `HARDENING.md`.
This file is for "why is this suite shaped the way it is" — read it when
touching a suite's registration, not on every task.

Suites target by package wildcard with explicit exclusions, never an allowlist,
so a new class in a covered package is mutated by default. Packages without a
suite are deliberate scope decisions rather than omissions.

## sava-core

| Suite | Target | Notes |
| --- | --- | --- |
| `pitestBorsh` | `core.borsh.*` | Baseline empty — keep it that way. |
| `pitestEd25519` | `core.crypto.ed25519.*` | |
| `pitestEncoding` | `core.encoding.*` | |
| `pitestTx` | `core.tx.*`, `core.accounts.lookup.*` | Carries the largest triaged population. |
| `pitestToken2022` | `core.accounts.token.*` | |
| `pitestMeta` | `core.accounts.meta.*` | |
| `pitestDecimal` | `core.util.*` | |
| `pitestCrypto` | `core.crypto.*` | Excludes the `ed25519` subpackage, which has its own suite — the `crypto.*` wildcard spans dots. |
| `pitestVanity` | `core.accounts.vanity.*` | Widened from a `Subsequence*` allowlist 2026-08-04; 8 audited timeouts are the price. See below. |
| `pitestAccounts` | `core.accounts.*` | Top-level only: every sub-package with its own suite is subtracted, since the wildcard spans dots. |
| `pitestSysvar` | `core.accounts.sysvar.*` | Parsers over untrusted account data. |
| `pitestPbkdf` | `core.accounts.pbkdf.*` | Tests pin PBKDF2 to `MIN_ITERATIONS` and lock around Argon2id; keep that or the suite cost multiplies by mutant count. |
| `pitestPrimitives` | `core.rpc.*`, `core.programs.*`, `core.serial.*`, `core.zk.*` | The small cross-cutting types belonging to no larger package. |

### `pitestVanity` — the allowlist, retired 2026-08-04

This suite used to allowlist `core.accounts.vanity.Subsequence*` instead of taking
the package, on the argument that the mask workers search an unbounded loop, so
every mutant breaking the match predicate would run to the PIT timeout rather than
fail fast — a timeout window per such mutant. The note closed by naming the fix:
"giving the workers a bounded-attempts seam so a broken predicate fails fast;
`maxSearches` already exists for exactly that and could let the suite widen to the
package."

**It already had one.** `BaseMaskWorker.searchExhausted(attempts)` is that seam,
and its javadoc names tests as the reason it exists ("callers that cannot afford
that — tests, and any caller wanting a deadline — cap the number of key pairs
generated"). `MaskWorkerTests` has been driving every worker with a finite
`maxSearches` all along. The argument was stale rather than wrong: it described
the code before the seam landed and was never re-measured afterwards.

Measured on the widened suite: **235 mutants, 73% detected, 9 timed out** — not a
timeout per predicate-breaking mutant. Eight of those nine are one family with one
cause (the `for (;;)` search has exactly two exits, and each mutant disables the
cap that is the only one a test can reach), audited in
`sava-core/config/pitest/README.md`. Eight timeout windows is the whole price of
mutating the package, and it closed the last ownership gap in this module.

Reusable lesson: **an exception argued from a code property expires when that
property changes.** This one outlived its cause by however long it took someone to
re-run the measurement — which is the same rule the `pitestClient` transport
expiry taught, applied to a scope decision instead of an acceptance.

### `pitestDecimal` — plain `STRONGER` on purpose

`EXPERIMENTAL_BIG_DECIMAL` / `EXPERIMENTAL_BIG_INTEGER` were tried here and
generate **nothing**. They only rewrite the `(BigDecimal)BigDecimal` arithmetic
methods — add, subtract, multiply, divide, remainder, min, max, abs, negate,
plus — and this package's only arithmetic is `movePointLeft`/`movePointRight`,
which take an `int`. No package in the repo currently has a call site those
mutators can reach, so do not re-enable them on a hunch.

## sava-rpc

| Suite | Target | Notes |
| --- | --- | --- |
| `pitestResponses` | `json.http.response.*` | Debt free — keep it that way. |
| `pitestClient` | `json.http.client.*` | Coverage debt cleared 2026-07-31; see below. |
| `pitestWs` | `json.http.ws.*` | Seeded at 50%, worked to 73% same day; see below. |
| `pitestEncoding` | `json.*`, `json.http.request.*` | Added 2026-08-04 to close ownership; subtracts the three sibling suites' packages, since the wildcard spans dots. |

### `pitestClient` — the debt is deliberate and documented

Registered over the whole package and worked from 54% to 89% rather than being
narrowed to fit. What remained after that was two dozen survivors (triaged for
equivalence) and 39 `NO_COVERAGE` — almost all the `sendPostRequestNoWrap` /
`sendGetRequestNoWrap` / `sendGetRequest` transport paths, which
`RpcRequestTests` never enters because it routes everything through
`sendPostRequest`.

**Coverage debt cleared 2026-07-31** by the scaffolding the acceptance named:
`JsonHttpClientTransportTests` (a local echo server answering with the method
and path it saw, so payload assertions pin the built request end to end;
wrapped-vs-no-wrap pinned by whether the parser receives a
`ReadHttpResponse`), plus `StubHttpResponse`-driven tests for the
never-constructed parser controllers and ordinary `registerRequest` cases for
the `Transaction`-taking simulate overloads. 581/601 (97%), zero
`NO_COVERAGE`, 20 baseline rows. The same pass withdrew the `checkResponse`
`< 200` "unreachable in-harness" acceptance — a 199 is constructible with the
stub the suite already owned; the raw-socket escape hatch was never needed.
The log-and-rethrow tails surfaced by the new coverage were first read as
logging-only, then killed the same day through the JUL backend (`TestLogs`,
the ws funnel's technique) once a second read noticed the logged status and
body are the *only* record of what the provider sent — the rethrown exception
is the JSON parser's own. One `# dead null arm` acceptance remains from that
family. Reasons are grouped by family in
`sava-rpc/config/pitest/README.md`.

Exclusions must name `*Check*` and `Stub*` as well as `*Test*`: test sources share
this package and shared fakes are named for their role. Trailing wildcards
throughout, per HARDENING.md — `*Check` would stop matching the moment a drift
check grows a nested helper. The verify task warns if this regresses.

**The builder's omitted-value branch — killed 2026-07-23.** The template's new
"build the subject inside the test body" rule sent a re-read through the one
`SolanaRpcClientBuilder` row in the baseline
(`createClient,31,RemoveConditionalMutator_EQUAL_ELSE`), which had been carried
with no written reason in the README. It was not equivalent and not the
field-initializer trap either: `SolanaRpcClientBuilderTests` already builds a
client per `@Test`, but *every one of them* passed an explicit `httpClient(...)`
so it could assert the instance came through, leaving the `this.httpClient ==
null` fallback — what an unconfigured client actually talks over — driven by no
test. `anOmittedHttpClientDefaultsToANewOne` builds without one and asserts the
client supplied its own; the class went to 29/29. The generalisation worth
keeping: a builder test that always configures a value cannot see the default it
replaces, so each defaulted field needs one build that omits it. `pitestClient`
is now 538/601 with a 63-row baseline (was 64), pruned via
`pitestWsBaselinePrune` (then spelled `-PpruneMutationBaseline`).

### `pitestWs` — the clock seam, and the background-thread ceiling

Registered 2026-07-21 together with the `NanoClock` seam
(`SolanaRpcWebsocket.Builder#clock`, mirroring ravina's
`software.sava.services.core.NanoClock`): `SolanaJsonRpcWebsocket`'s reconnect
throttle and ping pacing previously read `System.currentTimeMillis()` directly,
so the package could not meet the determinism requirement and was left without
a suite. The clock lives in the `ws` package deliberately, so this suite
mutates it; `NanoClockTests` (ported from ravina) covers it, and the reconnect
tests step a `TestClock` over the throttle and ping windows instead of waiting.

Seeded at 50% detected (247 entries), worked the same day to 73% (138 entries)
— per-family acceptances and the remaining debt are in
`config/pitest/README.md`. The check-loop executor is injectable
(package-private setter on `SolanaRpcWebsocketBuilder`, reached by casting the
builder — deliberately not public API; null creates the classic dedicated
single-thread executor, tracked with `internalExecutor` so `close()` shuts
down only what it owns and merely signals an injected executor's loop to
return its thread). Landing the seam also fixed two real defects in the loop:
it busy-spun — lock, throttled no-op, unlock — for the whole window between a
subscription's send and its confirmation, and it never exited after `close()`,
stranding the non-daemon thread. Constructor-driven tests inject a
`RecordingExecutor` (captures the loop task, never runs it), so no background
thread races clock-stepped assertions; builder-path tests still run real
internal executors, which is where the loop's remaining flip-insurance rows
come from. `connect()`'s deferred branch has its own seam — a package-private
`scheduler(ScheduledExecutorService)` on the builder, null defaulting to the
classic `CompletableFuture.delayedExecutor` (the shared JDK delayer; the
check-loop executor cannot host deferred connects because its single thread
is occupied by the loop for the websocket's lifetime). With a
`RecordingScheduler` the window boundary, remaining-delay arithmetic, and
deferred `lastWrite` write are pinned deterministically, leaving one
classic-path body row accepted. Fakes are named `Recording*` and excluded
alongside `*Test*` (which also matches `TestClock`).

## sava-vanity

Has a test source set (`EntrypointTests` — system-property parsing, key-path
resolution) but no PIT suite. It is an application module whose `module-info`
exports nothing, so its helpers are package-private by choice rather than by
accident.

## What the ratchet cannot see (sava's instance)

Per the inventory rule in sava-build's HARDENING.md: the repo-specific edges
of what a clean ratchet proves here, kept in one place (adopted 2026-07-31
from a downstream Rust adaptation's practice).

- **A same-key swap is invisible** (line-less baseline keys, adopted at the
  post-21.5.19 bump): kill one mutant and introduce a new one at the same
  `class,method,mutator,STATUS` in one change and the multiset is unchanged —
  the new mutant inherits the old row's acceptance. The tripwire is the
  line-drift advisory (row-level once every row carries a `# line` tag and
  counts match): when it names a key whose README argument no longer reads
  against the current code, treat it as the swap until shown otherwise.
- **Kills come only from `targetTests`.** `LiveMainNetDriftCheck`, the
  `integ.sh` example flows, and the jmh benchmarks are invisible to PIT —
  code exercised only through them reads `NO_COVERAGE` despite being
  exercised for real.
- **Unowned packages — closed 2026-08-04, this edge is gone.** The candidate
  plugin's `mutationOwnershipAudit` (advisory alone, mandatory under
  `hardeningCertify`) put a number on what "deliberate scope decision" had been
  costing: **56 production classes** owned by no suite — 45 in sava-core
  (`accounts` 11, `accounts.vanity` 11, `accounts.sysvar` 9, `accounts.pbkdf` 6,
  `rpc` 3, `serial` 2, `programs` 2, `zk` 1) and 11 in sava-rpc
  (`json.PrivateKeyEncoding` and its nests, `json.PublicKeyEncoding`,
  `json.http.SolanaNetwork`, and six `json.http.request` enums). All 56 were
  closed by **targeting** them — four new sava-core suites, one new sava-rpc
  suite, and the `pitestVanity` widening — so the repo carries **no**
  `declineExclusionAudit` record and the audit reports 0 explicitly declined in
  both modules. The cost was 413 seeded `# untriaged` rows in sava-core and 17
  in sava-rpc, itemised in each module's `config/pitest/README.md`; that debt is
  now visible instead of invisible, which is the whole point. What a clean gate
  still says nothing about: **sava-vanity's application module**, which registers
  no suite at all.
- **The ws suites' timing seams have a background-thread ceiling** — the
  check-loop and ping-pacing rows detectable only under load are the audited
  timeout set, not ordinary kills (the audited-set section above).
- **`pitestClient`'s 39 `NO_COVERAGE` transport rows — expired 2026-07-31.**
  The escape was attempted and it worked: the whole family fell to the
  transport harness (`pitestClient` section above), a same-day demonstration
  of why unreachable-has-an-expiry-date. The suite's remaining blind spot is
  the ordinary one — its 20 surviving rows are argued acceptances; the
  parse-failure diagnostic logs are asserted through the JUL backend and no
  longer count among them.
- **Excluded main-source classes**: `Integ` (git-ignored scratch driver) is
  the one deliberate production-class exclusion; fuzz harnesses are
  test-source and auto-excluded by the plugin.
- **No JPMS services** (verified 2026-07-22), so the class-path/module-path
  divergence PIT introduces is currently moot — revisit if a service is ever
  declared.

## Per-package hardening history

The per-surface notes — what each package's fuzz corpus covers, which invariants
are asserted where, and the reasoning behind long-standing accepted mutants —
live in the hardening sections of `AGAVE_SYNC.md`, alongside the canonical
sources they mirror.

## Mutator-set trials

`STRONGER` is the default everywhere. Per HARDENING.md ("the mutator set bounds
what the ratchet can see"), `EXPERIMENTAL_BIG_INTEGER` was trialed on
2026-07-21 against every suite whose mutated code mentions
`BigInteger`/`BigDecimal`:

| Suite | Generated without | With | Fires |
|---|---|---|---|
| core `borsh` | 1070 | 1070 | 0 |
| core `encoding` | 1072 | 1072 | 0 |
| core `decimal` | 22 | 22 | 0 |
| core `token2022` | 688 | 688 | 0 |
| rpc `client` | 498 | 498 | 0 |
| rpc `responses` | 524 | 524 | 0 |

Zero fires: this code constructs, parses, and compares Big values but performs
no `add`/`multiply`-family arithmetic on them in mutated classes (the grep hits
are `List.add` and friends). Enabling a mutator that cannot fire is baseline
churn for nothing, so it stays off. **Re-trial if Big arithmetic is introduced**
— fixed-point/fee math of the kind that made it fire 114 times in idl-clients'
`orca` suite.

`EXPERIMENTAL_NAKED_RECEIVER` was trialed 2026-07-22 against every suite, per
the shared HARDENING.md's fluent-API blind spot (a call returning its receiver
type is an expression, invisible to `VoidMethodCallMutator` — casebook: the
EXPERIMENTAL_NAKED_RECEIVER trials). Trials run with the build-file hook:
`-PtrialMutators=STRONGER,EXPERIMENTAL_X` overrides every suite for a run.

| Suite | Without | With | Fires |
|---|---|---|---|
| core `borsh` | 1070 | 1070 | 0 |
| core `ed25519` | 946 | 946 | 0 |
| core `encoding` | 1072 | 1072 | 0 |
| core `token2022` | 688 | 688 | 0 |
| core `crypto` | 12 | 12 | 0 |
| core `decimal` | 22 | 25 | 3 |
| core `meta` | 212 | 213 | 1 |
| core `tx` | 972 | 978 | 6 |
| core `vanity` | 113 | 117 | 4 |
| rpc `client` | 501 | 601 | 100 |
| rpc `responses` | 524 | 607 | 83 |
| rpc `ws` | 541 | 592 | 51 |

Enabled on the seven firing suites (`mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"`
at each registration); the zero-fire suites stay plain `STRONGER` — their mutated
code returns primitives, arrays, and records, not fluent receivers. 219 of the
248 fires died against existing tests on the first run; new tests killed 14
more, and 15 were accepted with reasons (9 in `client`'s documented
never-entered transport family, 1 `responses` position-equivalent, 5 `ws`
fallback-funnel redundancies — each in its module's `config/pitest/README.md`).
The kills closed three real gaps, all same-day: response parsers' unknown-field
`ji.skip()` branches were only ever exercised with nothing after the skipped
value (`skippedValuesLeaveTheIteratorAligned` now trails every skip with a
field that must still parse), websocket notification parsing was never fed
reordered-field messages (`reorderedNotificationFields`,
`unknownGenericSubscriptionIdUnsubscribes` — which also killed three
previously accepted rows), and `InstructionRecord.toString`'s
`indent(4).stripTrailing()` formatting was covered but unasserted.

## Environment verifications

- **No services anywhere, so PIT's class-path world cannot diverge (2026-07-22):**
  this repo's `test` tasks run on the module path (gradlex whitebox test suites)
  while PIT minions run on the class path — the shared casebook's `ServiceLoader`
  trap ("PIT's world is the class path") — but no sava `module-info` carries a
  `provides`/`uses` clause, no source set ships a `META-INF/services` entry, and
  nothing calls `ServiceLoader`. Re-verify when introducing a service; a real one
  then needs the dual declaration (`module-info` **and** `META-INF/services`) per
  the shared HARDENING.md.
- **JUnit 6.1.2: `@Execution` and `@TestInstance` are both `@Inherited`** —
  verified in the resolved jar's bytecode, 2026-07-21. `RpcRequestTests`'
  base-level annotations therefore reach its nine concrete subclasses, and
  parallel execution is not enabled in any module, so the abstract-base
  wandering-count cause in the shared casebook does not apply to this repo.
  Re-verify on a JUnit major bump before restructuring any tests over it.

## Multiset baseline migration — 2026-07-23

The plugin's ratchet compared baseline rows as a *set* until 2026-07-23; it now
compares multisets, because one compound condition emits a mutant per operand or
branch direction at the same `class,method,line,mutator` key (and a single
`MathMutator` key can cover two different operations on one line). A collapsed
row meant a killed sibling could regress to `SURVIVED` and be absorbed by its
already-accepted twin — a hole in the ratchet, not an ergonomic nit.

Migrating all twelve suites materialized **18 previously absorbed copies**, and
nothing else: every one sat at a coordinate already in the baseline, no suite
gained a new coordinate.

*Postscript (line-less keys, 2026-08-01):* the multiset doctrine is unchanged
and the key it applies to is now `class,method,mutator,STATUS` — lines left
identity entirely, so the copies above are told apart by their `# line` tags
rather than by the key. Sibling copies still repeat legitimately and still
must never be hand-deduped.

| Suite | Copies | Where |
| --- | --- | --- |
| `ed25519` | 1 | `car25519:367` — the two `long -` → `+` bias terms |
| `encoding` | 4 (+1) | `Base58` `toLimbs:116/129/142`, `beginMutableEncode:377`; the extra is a `limbsLength:94` copy that had been reading `TIMED_OUT` |
| `client` | 1 | `checkResponse:32` — **killable, not equivalent** (below) |
| `ws` | 9 | `removeDanglingSub:363` (×2), `lambda$queueUnsubscribe$0:384`, `onClose:1061`, `programSubscribe:555`, `handlePendingSubscriptions:1004`, `ensureCapacity:928` |
| `responses` | 3 | `toJsonIntArray:57`, `RpcCustomError.parseError:58/66` |

Seventeen were the opposite operand of an already-triaged condition and joined
their existing family notes. The eighteenth was not: `client`
`BaseJsonRpcResponseParser.checkResponse:32`'s second
`RemoveConditionalMutator_ORDER_IF` is the `>= 300` operand, and the harness had
no case where an HTTP failure status carries a well-formed `result` envelope.
`resultEnvelopeUnderANonSuccessStatusIsRejected` pins that contract (the status
vetoes the body) and killed both it and the `ConditionalsBoundaryMutator` row on
the same line — the 300 case separates `>= 300` from `> 300`. So the migration
converted one silent acceptance into a test and shrank the `client` baseline by
a row. Details in `sava-rpc/config/pitest/README.md`.

`ws` was refreshed with the union writer, not the full
rewrite: its accepted `checkCycle:235` `unlock()` row read
`TIMED_OUT` in the migration run (removing an `unlock()` in a `finally` wedges a
later test rather than failing it), and a plain refresh would have dropped
flip insurance on the strength of a load-dependent detection.

## Timeout budgets — sized to the tests, 2026-07-23

PIT's default per-test allowance is `recorded time × 1.25 + 4000ms`, paid on
every hanging-mutant detection. Ranked by duration, no test in any sava suite
comes close to justifying that constant:

| Suite | Tests | Sum | Slowest |
| --- | --- | --- | --- |
| `client` / `responses` | 310 / 347 | ~1.0s | 0.246s (`clampingDoesNotTruncateLargeBodies`) |
| `ws` | 111 | 0.16s | 0.055s (`systemSleepBlocksForAtLeastTheRequestedDuration`) |
| `encoding` | 43 | 0.48s | 0.202s (`testReferenceCrossValidation`) |
| `ed25519` / `crypto` | 12 / 31 | ~0.5s | 0.212s (`isNotOnCurveMatchesReferenceForRandomEncodings`) |
| `borsh`, `tx`, `token2022`, `meta`, `decimal`, `vanity` | 20–83 each | ≤0.07s | ≤0.009s |

Both modules therefore run `timeoutFactor = 2.0; timeoutConst = 1500` via
`mutation.configureEach`. The factor went **up** while the constant went down on
purpose: load inflates a test in proportion to its own runtime, so proportional
headroom is the safe kind, and even the slowest suite keeps ~8× its quiet
runtime.

Measured, all twelve suites re-run against the migrated baselines: **every
status identical** to the default-timeout run, and the whole set finished in
3m06 against 8m47 (both `--continue`, both modules in parallel, so treat the
totals as indicative). The clean single-suite A/B is `encoding`, run alone
back-to-back with the report deleted between: **27s → 17s (-37%)**, same
1048/1072, same 24 survived, same 2 timed out. `client` (41s → 33s) and `ws`
(57s → 45s) came in at the ~20% the shared casebook predicts. `SURVIVED -> TIMED_OUT` drift in the verify output (the plugin stashes
each run's statuses under `<module>/.pitest-history/<suite>.statuses` and names
each newcomer's origin) is the signal that the constant went too low; raise it
back before suspecting the code. One standing exception: `encoding`
`Base58.limbsLength:94`, where the mutant inflates the allocation estimate and
so flips between `SURVIVED` and `TIMED_OUT` on its own — both copies sit in the
baseline, so the ratchet holds either way and the warning naming that row is
expected rather than a signal to retune. `ws` is the suite to watch — its `checkCycle`
`unlock()` mutant and the `run` while-condition are detected *by* timing out.

## Plugin knobs and generated scaffolding — what this repo uses

- **`recompileExcludes = listOf("Integ.java")`** (sava-rpc): `Integ.java` is a
  git-ignored scratch driver in `src/main/java`, so without the exclusion the
  PIT/Jazzer recompiles compile a different source set here than in CI. The
  suite's `excludedClasses` already kept it out of the mutant population; this
  keeps it off the tool class path too.
- **`-PmutateOnly=<class-glob>`** is the iteration loop for killing a cluster
  (tests still run in full; the report is stamped `.scoped` and cannot touch a
  baseline). `pitest<Suite>Debt` ranks the remaining debt by class.
- **`generateTestSupport` stays off.** The generated `ConcurrencyHarness` /
  `Ports` / `LoopbackHttpServer` / `JulRecorder` set has no consumer here: no
  sava test starts a thread — the ws determinism story is seams
  (`RecordingExecutor`, `RecordingScheduler`, `TestClock`), not concurrency
  harnesses — and the client tests drive `StubHttpResponse` rather than a
  socket. Enable it if a suite ever needs a real server or a parked-thread
  assertion; do not turn it on speculatively.

## Arcmutate incremental analysis — licensed 2026-08-03

Support landed in the sava-build plugin 2026-07-21 (licence requested from
arcmutate the same day — free for open-source projects). The signed OSS
certificate arrived 2026-08-03 and is committed at the repo root as
`arcmutate-licence.txt` with a `.gitignore` allow-rule, which is safe because it
is a signed certificate rather than a secret: its only fields are `expires`,
`keyVersion`, `packages` (`software.sava.*`), `type` (`OSSS`) and `signature`,
with no private subscription download endpoint anywhere in it — do not paste one
into this repo either. It expires 15/08/2027. Activation is the file's
presence: history files appear at
`<module>/.pitest-history/<suite>.hist` (git-ignored, survive `clean`), and each
assisted summary carries a `[history]` marker. Anything that writes or certifies
the record disables reuse by itself — `hardeningCertify` automatically, the
per-suite writer tasks by refusing a history-assisted report — so
`-PnoMutationHistory` is only the explicit override for other fresh runs. Wiring
was verified end-to-end with a dummy
licence file before the real one: config cache invalidates on the file appearing,
`com.arcmutate:base:1.7.1` resolves, PIT runs with `+arcmutate_history`, and
arcmutate's signature check rejects the dummy. A DIY changed-classes-only mode was
considered and declined: subsumed by history, and the savings did not survive
arithmetic (the coverage phase and JVM floor dominate every suite under ~30s).

**The licence changes the mutant population, and every baseline here predates it.**
Measured 2026-08-04 on identical `ws` code, holding everything but the certificate
constant: **605 mutants unlicensed, 573 licensed** — a 32-mutant difference, of
which 7 are rows this repo has accepted (all `RemoveConditional*` survivors in
`ensureCapacity`, `handlePendingSubscriptions` ×2, `lambda$queueUnsubscribe$0` ×2,
`onText`, `onWholeMessage`). The licensed run reports them as unmatched and offers
them as prune candidates; they are not kills, and pruning them would delete
argued acceptances that reappear the moment the certificate is absent or expires.
Two consequences: read the first licensed run of any suite as a toolchain diff, and
note that `-PnoMutationHistory` under the *pinned* 21.5.20 plugin removes
`com.arcmutate:base` outright (fixed in candidate `181b4c5`), so mode snapshots,
convergence runs and mode-flip-insurance evidence taken with the licence present and
that pin are measuring a third population again — re-derive them after the bump.

## Convergence check — 2026-07-21 (pre-release)

Ran HARDENING.md's convergence method across all 11 suites: two solo passes
with report directories deleted between (so Gradle could not serve a stale
report), then both modules' `qualityGate`, diffed per-mutant on
`(class, method, line, mutator)` with duplicate keys compared as counted sets.

**6,129 mutants × 2 comparisons: zero flips crossing the
`SURVIVED`/`NO_COVERAGE` boundary**, and the accepted-row sweep found all 179
baseline rows matching a real unkilled mutant in every run — no stale
acceptances widening the gate. Two flips within detected statuses, neither
able to move the ratchet:

- `ed25519` `Ed25519Util.M:563` (duplicate-keyed `MathMutator` pair): one of
  the two reported `RUN_ERROR` instead of `KILLED` under full-gate load.
- `vanity` `SubsequenceRecord.formatCharOptions:148`: `TIMED_OUT` ↔ `KILLED`
  between solo runs — ordinary timing jitter on a detected mutant.

## Mode comparison — 2026-07-22 (scripted)

The hand-run procedure above is now sava-build's
`pitestModeSnapshot -PpitestMode=<label>` / `pitestModeCompare` pair; first
scripted runs, per module (quiet all-suites pass stashed as `solo`, then
`qualityGate` stashed as `gate`):

- **sava-core** (9 suites): zero boundary flips, zero dead baseline rows, one
  benign flip — `formatCharOptions:148` `KILLED` ↔ `TIMED_OUT` again, the
  same known flapper, classified as unable to move the ratchet.
- **sava-rpc** (3 suites): zero boundary flips; one benign flip —
  `client` `BaseJsonRpcResponseParser.checkResponse:31` `EQUAL_IF` read
  `RUN_ERROR` solo / `KILLED` gate (first `RUN_ERROR` seen in a *quiet* run;
  one-off until it repeats on the same mutant). The dead-row sweep named
  exactly the six ws flip-insurance halves (`run:205/214/215/218/224`) —
  every key read identically in both modes, so the quiet halves matched
  nothing. Evidence recorded in the ws README; the unions stay until
  repeated re-measures stay quiet (casebook: flip insurance that outlived
  its cause).

## ws check-loop flip family — resolved by removing the cause (2026-07-22)

Rather than wait out the re-measure criterion, the cause was removed the same
day: the `run` loop's body is now the package-private `checkCycle(long
awaitNanos)` seam (`awaitNanos <= 0` never parks), and three deterministic
lifecycle tests drive the interior inline — retry-window resend, socketless
no-op, and the unhandled-exception funnel, whose ERROR record is asserted
through System.Logger's JUL backend (`testModuleInfo` gained
`requires("java.logging")`; `RecordingWebSocket` gained a synchronous
`throwText`). The refactor shifted every ws line below `run()`: the churn
classifier read it as `123 shifted, 0 newly covered, 1 unexplained` — the one
unexplained being the `unlock()` removal, which moved methods (`run` →
`checkCycle`) and was accepted with a reason (cross-thread-only observable;
a timing harness one call does not earn). Baseline went 140 → 130: all
eleven `run`-family rows out (six insurance halves, five live halves — four
now killed, one relocated and accepted). Post-refactor
`pitestModeCompare` (solo vs `qualityGate`): **zero flips of any kind, zero
dead rows** across all three suites, and ws's permanent 6-stale warning is
gone. The `run` while-condition forced-always-true mutant remains `TIMED_OUT`
(detected, stable in both modes) — nontermination is inherently PIT-timeout
territory.

Two transient failures were later root-caused from the Gradle daemon logs
(`~/.gradle/daemon/<version>/daemon-<pid>.out.log` keeps full build output
even when the invoking shell discarded it — check there before calling any
failure unexplained):

- The first solo `pitestEncoding` invocation of the day exited 1 with no
  report: PIT's coverage minion started, waited 10s on its socket for the
  controller's handshake, hit `SocketTimeoutException`, and died
  (`MINION_DIED`). Known intermittent PIT failure mode, upstream, no exposed
  knob for the handshake timeout (`--timeoutConst` is per-mutant, unrelated).
  Remedy: re-run the suite. Every subsequent run (solo ×2, full gate) was
  identical, so it cannot poison a result — only fail a build.
- A `:sava-core:test` run (2026-07-20) failed with `java.io.EOFException`:
  the forked test-worker JVM died abruptly — no `hs_err` dump, no crash
  report, so external kill or hard abort, cause unrecoverable. One-shot.

Neither is a sava defect. Both signatures, the daemon-log recovery recipe, and
the decision *not* to auto-retry `MINION_DIED` in the plugin (declined
2026-07-21 — at ~1 per 100 suite runs a retry mostly masks environment
sickness) are recorded in sava-build's HARDENING.md under "Transient
infrastructure failures". The verify task's missing-report error now says the
run may have just failed (it previously said "run pitestEncoding first",
burying the real error above it).

## Fuzz corpus replay (resolved 2026-07-21; generated 2026-07-22)

The shared doc expects committed seed corpora to be replayed inside `check`.
sava-core's `fuzz/token2022` (2 seeds) and `fuzz/txSkeleton` (4 seeds) were
read only by their fuzz harnesses, so they could rot between fuzz runs.
First closed with a hand-written replay test per corpus, following
json-iterator's `TestFuzzCorpusReplay`; those were deleted 2026-07-22 in
favour of the plugin's `generateFuzzReplayTests`, which now generates an
equivalent-or-stricter test per `seedCorpus` target (classpath-resource
resolution, regular files only, fails — not skips — on a missing or empty
corpus). The generated test lands in the harness's package, so the `*Test*`
wildcards feed it to the matching PIT suite and the corpus doubles as a
mutant oracle — verified 2026-07-22: `pitestToken2022` and `pitestTx` green
against unchanged baselines after the deletion. Seed provenance moved to
`sava-core/src/test/resources/fuzz/README.md`, next to (never inside) the
corpus directories. New seeds — including minimized fuzz findings — replay
automatically.

### `base58` and `borsh` gain regression corpora, 2026-07-25

sava-build `575d292` made `generateFuzzReplayTests` name every target that
declares no `seedCorpus`; here that was `base58` and `borsh`, both skipped
deliberately because their formats — ASCII text, and a `u32` length prefix —
are reachable from scratch, so seeds buy no coverage.

That reasoning was right and is beside the point. It answers the **bootstrap**
question (does a mutator need help finding valid inputs?) when the rule these
targets were failing is the **regression** one: a fuzz finding is closed by a
committed seed *plus* a named test, and a target with no corpus has nowhere to
put the seed. Committing crash reproducers and replaying them in ordinary CI is
the settled practice elsewhere — OSS-Fuzz/ClusterFuzz regression testing, Go's
`testdata/fuzz` — and it is independent of whether the format needs
bootstrapping. So both targets now carry a corpus (6 and 7 seeds), and the
build-file comments say which job each corpus does, because the old comments
would otherwise read as an argument against seeds that are there for a
different reason.

Measured, not assumed:

- **No mutation gain.** `pitestEncoding` 1048/1072 with 24 survived and 2 timed
  out — identical to the pre-corpus numbers; `pitestBorsh` stayed 1070/1070 with
  its baseline still empty. The `encoding` survivors are the documented
  allocation-sizing and chunking equivalents, which no input can kill, so this
  was the expected result rather than a disappointment. The one baseline row
  reported stale is the standing `limbsLength:94` `SURVIVED`/`TIMED_OUT`
  flipper — **not** pruned, per the rule above.
- **Every seed earns its place.** `fuzzBase58Minimize` 6 → 6 and
  `fuzzBorshMinimize` 7 → 7, both 0-removed, each seed contributing distinct
  coverage features. The two `Minimize` tasks also now run at all, which they
  could not before.
- The replay is cheap and real: `BorshFuzzSeedReplayTest` is the
  largest-coverage test in its suite at 320 blocks.

The value delivered is a home for future findings and deterministic
`check`-time execution of both harnesses — not a tighter ratchet. Do not expect
seeds to move these two baselines.

### Three new fuzz targets: ed25519, responses, ws — 2026-07-31

The fuzz roster grew from four targets to seven, and a weekly `fuzz.yml`
workflow (ported from ix-proxy's) now soaks all of them on a schedule; the
seed corpora and what each seed pins are in each module's
`src/test/resources/fuzz/README.md`.

- **`fuzzEd25519`** (sava-core) — differential over the first 32 bytes as
  both a point encoding and a keygen seed: `isNotOnCurve` against
  BouncyCastle where it has a verdict and a BigInteger decompression
  reference where it does not (non-canonical y, BC's small-order reject set),
  `generatePublicKey` against BouncyCastle's, sign-bit invariance throughout.
  A regression corpus in the base58/borsh sense: the structured subspaces are
  finite and enumerated as seeds; coverage guidance has nothing to steer by
  in branch-poor limb arithmetic, so the campaign's value is volume against
  rare-carry and signed-digit-recoding bugs, which have no branch signature.
- **`fuzzResponses`** (sava-rpc) — the http half of the untrusted-node
  surface: a selector byte routes the body through the same
  envelope-gate-plus-parser controllers `SolanaJsonRpcClient` holds, over 14
  parser families. Contract "garbage in -> RuntimeException out", plus one
  asserted invariant: the controllers are shared static state applied
  concurrently in production, so the same body must classify identically on
  repeat application — a disagreement is a stateful parser predicate.
- **`fuzzWs`** (sava-rpc) — hostile framing against `onText`'s fragment
  reassembly (carved split points and all four CharBuffer copy flavors) and
  the notification dispatch behind it, with every channel subscribed and
  confirmed in a fixed preamble. Fresh websocket per input, or crashes become
  input-order-dependent. Beyond Jazzer's own no-hang/no-Error checks, the
  harness asserts the reassembly state machine always recovers: after the
  fuzzed frames and a flush, a canonical notification must either dispatch or
  draw the unknown-id auto-unsubscribe — exactly one, never neither.

Finding from the same review, closed same day: `ensureCapacity`
(SolanaJsonRpcWebsocket) doubled the reassembly buffer with no upper bound,
so a server that never sends a `last` frame grew it until OOM. The fuzzer
could not have surfaced this — a carve harness's growth is bounded by its
input length — which is why it was recorded as a review finding rather than
waited for. Closed by `maxMessageLength`: builder-configurable
(`Builder.maxMessageLength`, default 2^26 chars — a 128 MiB buffer, ~5x a
10 MiB account's base64), enforced on the whole prospective message in
`onText` regardless of framing. Overflow is connection-fatal, not a parse
failure: partial message dropped, connection aborted, routed through the
same `onError` seam a transport error takes (default log-and-close, or the
caller's reconnect policy). Pinned by `MessageSizeCapTests` (both boundary
sides, recovery after abort, builder validation and fluent identity). The
`fuzzWs` maxLen of 16384 sits far under the default cap, so the harness
still reaches the growth arithmetic and its large seed replays it inside
`check`.

Ratchet effects of the same pass, all verified green: the ws and client
suites gained `*Fuzz*` exclusions (the sava-core suites always had them —
harnesses are killers, not mutant population); the ws seed replay joining
`pitestWs`'s targetTests newly covered previously-unobserved `onText`
interiors, and the six unexplained rows it surfaced were all killed with
named tests (`notificationDispatchFlushesPendingSubscriptions`,
`dispatchFailureReachesExceptionSubscribers`,
`wholeArrayBackedNotificationDispatchesInPlace`,
`largeArrayLessNotificationGrowsTheBuffer`,
`slicedNonLastFragmentAccumulatesFromItsArrayOffset`) rather than accepted —
the ws baseline shrank 139 → 131 rows across the refresh and a follow-up
prune, including all four old `onText` NO_COVERAGE rows. The
`Builder.maxMessageLength` members are abstract rather than
default-with-throw (Jim: no external Builder implementation exists to keep
compiling — only the unpublished helius client, which wraps rather than
implements), so unlike `Builder.clock`'s default they contribute no
unreachable interface-default mutant at all; the row its briefly-default
getter seeded was pruned once the member went abstract.
The refresh's PAIRING OUTLIER warnings on `close`/`handlePendingSubscriptions`
were verified as crosswise pairing of ambiguous same-mutator siblings — the
per-method line-less multiset is identical on both sides of the diff.

Review follow-ups (same day, from the commit review): `ensureCapacity` now
clamps the doubling overshoot to `maxMessageLength` — the `onText` gate
guarantees `minCapacity <= cap`, so the clamp never under-allocates, and
without it the buffer could reach nearly twice a caller-set cap (the
javadoc's "128 MiB buffer" only held for the default). Footprint-only:
`Math.min` generates no mutants under STRONGER, so the fix cost a pure
34-row drift refresh. `fuzz.yml` (here and in ix-proxy) gained `--continue`
— a crashing target no longer skips the rest of the weekly soak, and the
nonzero exit still fires the findings upload — plus a step-level
`timeout-minutes` scaled from `max-fuzz-time`: a job-level timeout marks the
job *cancelled*, which skips the `if: failure()` upload, while a step-level
one fails the step and the upload fires. Declined: shrinking the reassembly
buffer after a large message — high-water retention is arguably right for
streams that regularly carry large accounts, and the clamp bounds the worst
case at the cap. The inverted `BREAKING CHANGE` footer on 1611541 (test/CI
prerequisites, not a consumer-visible change — while bcf991b's actual
interface addition carries no note) is published history: remediate by
editing the changelog in the release-please PR rather than rewriting main.
Also declined: `default` bodies for the two `SolanaRpcWebsocket.Builder`
methods bcf991b added. Adding an abstract method to a published interface
breaks external implementors at compile and link time, and release-please's
always-bump-patch ships it as a patch — but every implementor of `Builder`
is one we develop, so the additive form buys nothing over the plain
abstract declaration. Treat `Builder` as sava-implemented: if that ever
stops being true, this is the decision to revisit.
