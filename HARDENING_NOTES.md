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
| `pitestVanity` | `core.accounts.vanity.Subsequence*` | **Deliberate allowlist.** See below. |

### `pitestVanity` — the one allowlist

The mask workers search in an unbounded loop, so every mutant that breaks the
match predicate runs to the PIT timeout instead of failing fast. PIT scores
`TIMED_OUT` as killed, so the ratchet stays correct, but a whole-package suite
would cost a timeout window per such mutant.

The mask logic — where a wrong answer is silent rather than a hang — is what the
suite targets. The workers stay covered by `MaskWorkerTests` without being
mutated. The reasoning is repeated at the registration site in
`sava-core/build.gradle.kts`.

Closing this properly would mean giving the workers a bounded-attempts seam so a
broken predicate fails fast; `maxSearches` already exists for exactly that and
could let the suite widen to the package.

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
| `pitestClient` | `json.http.client.*` | Carries real coverage debt; see below. |
| `pitestWs` | `json.http.ws.*` | Seeded 2026-07-21 with untriaged debt; see below. |

### `pitestClient` — the debt is deliberate and documented

Registered over the whole package and worked from 54% to 89% rather than being
narrowed to fit. What remains is 27 survivors (triaged for equivalence) and 29
`NO_COVERAGE` — almost all the `sendPostRequestNoWrap` / `sendGetRequestNoWrap` /
`sendGetRequest` transport paths, which `RpcRequestTests` never enters because it
routes everything through `sendPostRequest`.

Clearing those needs new harness scaffolding — a local server exercising the GET
and no-wrap routes — not another `registerRequest` line. Reasons are grouped by
family in `sava-rpc/config/pitest/README.md`.

Exclusions must name `*Check*` and `Stub*` as well as `*Test*`: test sources share
this package and shared fakes are named for their role. Trailing wildcards
throughout, per HARDENING.md — `*Check` would stop matching the moment a drift
check grows a nested helper. The verify task warns if this regresses.

### `pitestWs` — seeded with untriaged debt, and one background-thread flip

Registered 2026-07-21 together with the `NanoClock` seam
(`SolanaRpcWebsocket.Builder#clock`, mirroring ravina's
`software.sava.services.core.NanoClock`): `SolanaJsonRpcWebsocket`'s reconnect
throttle and ping pacing previously read `System.currentTimeMillis()` directly,
so the package could not meet the determinism requirement and was left without
a suite. The clock lives in the `ws` package deliberately, so this suite
mutates it; `NanoClockTests` (ported from ravina) covers it, and the reconnect
tests step a `TestClock` over the throttle and ping windows instead of waiting.

Seeded at 50% detected (247 baseline entries) — untriaged debt, worked from
here; the breakdown is in `config/pitest/README.md`. The websocket starts a
background subscription thread in its constructor, and coverage of that
thread's loop races the test scheduler: `run:177` was observed to flip
detected↔SURVIVED between identical runs and stays in the baseline as flip
insurance. Fakes are named `Recording*` and excluded alongside `*Test*`
(which also matches `TestClock`).

## sava-vanity

Has a test source set (`EntrypointTests` — system-property parsing, key-path
resolution) but no PIT suite. It is an application module whose `module-info`
exports nothing, so its helpers are package-private by choice rather than by
accident.

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

## Environment verifications

- **JUnit 6.1.2: `@Execution` and `@TestInstance` are both `@Inherited`** —
  verified in the resolved jar's bytecode, 2026-07-21. `RpcRequestTests`'
  base-level annotations therefore reach its nine concrete subclasses, and
  parallel execution is not enabled in any module, so the abstract-base
  wandering-count cause in the shared casebook does not apply to this repo.
  Re-verify on a JUnit major bump before restructuring any tests over it.

## Arcmutate incremental analysis — wired, awaiting licence

Support landed in the sava-build plugin 2026-07-21 (licence requested from
arcmutate the same day — free for open-source projects). Activation is
dropping the `arcmutate-licence.txt` at the repo root: history files appear at
`<module>/.pitest-history/<suite>.hist` (git-ignored, survive `clean`), each
assisted summary carries a `[history]` marker, and the pre-release gate runs
with `-PnoMutationHistory`. Wiring was verified end-to-end with a dummy
licence file: config cache invalidates on the file appearing,
`com.arcmutate:base:1.7.1` resolves, PIT runs with `+arcmutate_history`, and
arcmutate's signature check rejects the dummy — so the only untested step is
a valid certificate. A DIY changed-classes-only mode was considered and
declined: subsumed by history, and the savings did not survive arithmetic
(the coverage phase and JVM floor dominate every suite under ~30s).

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

## Fuzz corpus replay (resolved 2026-07-21)

The shared doc expects committed seed corpora to be replayed inside `check`.
sava-core's `fuzz/token2022` (2 seeds) and `fuzz/txSkeleton` (4 seeds) were
read only by their fuzz harnesses, so they could rot between fuzz runs.
Closed with a replay test per corpus (`Token2022CorpusReplayTests`,
`TransactionSkeletonCorpusReplayTests`), following json-iterator's
`TestFuzzCorpusReplay`: list the resource directory, fail on an empty corpus,
feed every seed through `fuzzerTestOneInput`. Each lives in its harness's
package, so the `*Test*` wildcards feed it to the matching PIT suite and the
corpus doubles as a mutant oracle. New seeds — including minimized fuzz
findings — replay automatically.
