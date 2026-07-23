# sava — agent process contract

Short and always relevant. Reference material lives in separate docs, listed at the
bottom; read those only when a task actually needs them.

## Repo scope

Work in this repo is scoped to the three sava modules: **sava-core, sava-rpc, sava-vanity**.
`settings.gradle.kts` may composite the JSON parser source via
`includeBuild("../json-iterator")` during local development, which makes its sources and
gradle tasks reachable from here — but json-iterator is a separate project that owns its
own tests, PIT targets, and fuzz harnesses. When sava work surfaces a coverage gap or a
suspected bug in json-iterator, report it to the user; do not add tests, harness code, or
fixes in that repo from a sava task. The same applies to `../sava-build`, which owns the
convention plugins and `HARDENING.md`.

sava parses account and transaction data **client side**. Do not reach for the RPC
`jsonParsed` encoding to avoid writing a parser — it gives up the typed layouts this
library exists to provide, and only covers programs the node happens to know.
`RpcEncoding` has no `jsonParsed` constant on purpose. Generated serialization helpers
for specific programs live in the sibling `idl-clients` project; check there before
adding program-specific parsing here.

## This is a published library

Consumers depend on current behaviour, including behaviour that looks like a defect.
When you find one:

- **Pin it with a test, document it at the declaration, and report it — do not
  change it.** Several surprising behaviours are load-bearing and deliberate; see
  `CONVENTIONS.md`.
- Additive fixes (a new overload, a clamp on an unvalidated input) are fine.
  Changing what an existing signature returns is not, without the user's say-so.
- Deprecate with `@Deprecated(forRemoval = true)` and a javadoc `@deprecated` that
  says what changed and how to keep the old behaviour.

## Quality gate & mutation ratchet

<!-- hardening-template sha256:2c504992c917 -->

Full policy: sava-build's `HARDENING.md`. The parts that bite most often:

- **Scale verification to the change.** Iterate with the module's `test` task;
  before handing off, run only the `pitest<Suite>`(s) whose mutated code the
  change can reach — including a dependent module's suite when it calls the
  changed API, and the owning suite for test-only edits, since a weakened
  test is exactly what the ratchet catches.
- **The full `qualityGate` is the pre-release check, owned by the local
  release checklist.** CI deliberately runs only `check` (serialized PIT is
  too slow for hosted runners) — run the gate locally before deciding to
  release and don't wire it into CI to "fix" that. Once an
  `arcmutate-licence.txt` sits at the repo root, suite runs reuse prior
  results (`[history]` in the summary marks reuse) and the gate takes
  `-PnoMutationHistory` so release numbers are re-earned from scratch.
- Suites: sava-core has `pitestBorsh`, `pitestEd25519`, `pitestEncoding`, `pitestTx`
  (`tx` + `accounts/lookup`), `pitestToken2022`, `pitestMeta`, `pitestDecimal`
  (`core/util`), `pitestCrypto` and `pitestVanity`; sava-rpc has `pitestResponses`,
  `pitestClient` and `pitestWs`. Per-suite scope decisions and exceptions are in
  `HARDENING_NOTES.md`.
- Every `pitest<Suite>` run prints its own summary — `killed/total (%)` plus the
  survived/uncovered split — and warns if the suite is mutating its own test
  sources. Read that line before planning a pass. `pitest<Suite>Debt` prints the
  same debt grouped by class, largest first, with the delta against the baseline
  (falling back to the baseline when no full report is present) — use it to pick
  the next cluster instead of re-ranking the CSV by hand.
- **Iterate with `-PmutateOnly=<class-glob[,glob]>`** while killing a cluster:
  it narrows the mutated classes (the tests still run in full, so coverage is
  unchanged) and turns a suite-length loop into seconds. The report it writes is
  stamped `.scoped` and the ratchet, all three refresh flags and the mode snapshots
  refuse it, so re-run the suite unscoped before refreshing anything.
- **`SURVIVED` and `NO_COVERAGE` are different problems.** The first is a judgement
  call about equivalence; the second is an untested line and is mechanical work.
  Never accept a `NO_COVERAGE` mutant as "equivalent" — you have not observed it.
  A `SURVIVED` mutant no deterministic harness can reach is accepted as
  **unreachable in-harness**, naming what would reach it — worked example in the
  client triage of `sava-rpc/config/pitest/README.md`.
- **A suite's percentage is not a target.** An accepted mutant with a written
  reason is finished work, not a gap — `decimal`'s 81% is four documented
  equivalents, an accurate number whose "fix" was tried and reverted (casebook:
  the allocation harness that flapped). Check whether what remains is uncovered
  code or already-closed triage before trying to raise a figure.
- **Allocation and timing harnesses are a last resort**, reserved for properties
  that are a stated design goal — same casebook entry; the local decision is in
  the decimal notes of `sava-core/config/pitest/README.md`.
- A new unkilled mutant has exactly three legal outcomes: **kill it** with a test
  (prefer asserting the property it breaks over restating the implementation),
  **refactor** it out of existence, or **accept it** with a written reason in the
  module's `config/pitest/README.md`. Never run `-PupdateMutationBaseline` just to
  make the build pass. Sweepable equivalence claims are verified empirically with
  the range recorded, not argued in prose.
- **Pure line drift passes on its own.** Editing above a mutated method moves its
  rows; when every new baseline entry is a same-status shift of a stale one *and*
  the per-`(class, method, mutator, status)` population is unchanged, the verify
  passes with a notice and the refresh can wait for a convenient moment. Anything
  mixed in — a newly covered row, an unexplained one, changed counts — still fails
  and is triage first, refresh after. `-PnoDriftTolerance` restores the strict
  diff for a certifying run, alongside `-PnoMutationHistory`.
- **After a pass that killed baseline rows, shrink with `-PpruneMutationBaseline`,
  not `-PupdateMutationBaseline`.** Prune drops rows matching nothing this run and
  writes nothing new, so no coin-flip from a single run can be baked in; it keeps
  rows whose coordinate `TIMED_OUT` or is still unkilled at another status, and
  names both. The verify's stale-entry hint still says "refresh with
  `-PupdateMutationBaseline`" — prefer prune when the only news is *fewer*
  survivors. The three flags are mutually exclusive and the build refuses a
  combination. Never hand-roll a pruning script: matching rows without the status
  field deletes the wrong one (casebook: the status-blind prune).
- **Identical baseline rows are sibling mutants, not duplicates.** One compound
  condition emits several mutants at the same `class,method,line,mutator`
  coordinate — one per operand or branch direction — and the comparison is a
  multiset, so never hand-dedupe a baseline CSV: a collapsed row lets a killed
  sibling regress to `SURVIVED` invisibly. When one sibling survives, the verify
  prints the killed sibling's test; the survivor is that test's opposite branch
  direction and is triaged as its own mutant.
- Widening or adding a suite is expected to go red first. Register it, then work the
  population down and triage what is left — do not narrow the target or drop the
  registration to keep the build green.
- Test sources share packages with the code they mutate, and shared fakes are named
  for their role (`RecordingFoo`, `StubFoo`, `FooDriftCheck`) so they match no
  `*Test*` pattern. The verify task warns when this happens; fix the exclusions
  rather than triaging mutants in your own fakes.
- **Randomized tests use fixed seeds**; never sleep in a test. The ratchet needs
  deterministic kills and PIT re-runs the suite per mutant. Exploration belongs to
  the fuzz targets.
- **Do not rely on PIT's timeout to detect a mutant.** `TIMED_OUT` counts as
  detected and is load-dependent — the same mutant can flip to `SURVIVED` when
  its suite runs alone. Verify a changed baseline in both modes; union in only
  rows observed to flip, never every timed-out row. The verify stashes each run's
  `TIMED_OUT`/`SURVIVED` coordinates (`.pitest-history/<suite>.statuses`, machine
  local) and names each newcomer's origin on the next run: `KILLED -> TIMED_OUT`
  is the benign flavour and gets a count, `SURVIVED -> TIMED_OUT` gets a warning —
  a mutant nobody killed now reading as detected purely through load. Never let a
  refresh drop those rows from the baseline.
- **A wandering unkilled count is a defect to chase, not re-ratchet past** — a
  lucky run bakes in a row later runs fail on. Causes and the convergence
  method: `HARDENING.md`. The abstract-base annotation cause is ruled out here
  (JUnit 6.1.2 verification in `HARDENING_NOTES.md`); coverage attributed to
  field initializers still applies — exercise factories inside a `@Test`. The
  2026-07-21 convergence record in `HARDENING_NOTES.md` is the stability
  baseline.
- **Build the subject under test inside the test body, not in a field.**
  `RpcRequestTests` is `PER_CLASS` and builds its `SolanaRpcClient` in the
  constructor, so that construction's coverage attaches to whichever test runs
  first and wiring mutants can never pair with the test that drives what they
  wire. `SolanaRpcClientBuilderTests` is the counterweight — it builds a client
  per `@Test` — and a `SURVIVED` builder or constructor mutant is this pattern
  until proven otherwise: a missing *omitted-value* build, not an equivalence.
  That is how `SolanaRpcClientBuilder.createClient`'s `httpClient` fallback was
  killed on 2026-07-23 (every test had passed an explicit client, so the default
  branch was never observed).
- **Kill rates are bounded by the mutator set.** Big-value arithmetic is method
  calls, invisible to `STRONGER`. `EXPERIMENTAL_BIG_INTEGER` fired zero times
  in every candidate suite here (trial table in `HARDENING_NOTES.md`) — left
  off; re-trial if Big arithmetic is introduced. Fluent calls returning their
  receiver are expressions, invisible to `VoidMethodCallMutator`;
  `EXPERIMENTAL_NAKED_RECEIVER` fired in seven suites here and is enabled on
  exactly those (same trial table). Trial new mutators per suite with
  `-PtrialMutators=`; enable only what fires and record the numbers.
- **PIT minions run on the class path** while this repo's `test` tasks run on
  the module path, so `module-info` services would diverge between the two.
  Ruled out here 2026-07-22 — no sava module declares or consumes services
  (`HARDENING_NOTES.md`); a first real service needs the dual
  `module-info` + `META-INF/services` declaration, and a harness whose result
  depends on which task ran it is never committed.
- Fuzz findings become a committed seed input **and** a named regression test, never
  just a fix — and every committed corpus is replayed by a unit test inside `check`
  (`Token2022CorpusReplayTests`, `TransactionSkeletonCorpusReplayTests`), so a new
  seed replays automatically and the corpus cannot rot between fuzz runs. Dedupe
  with `fuzz<Target>Minimize` rather than by hand; both corpora here were verified
  already-minimal on 2026-07-23 (a 0-removed no-op), and every seed is named for
  its provenance, so a future run that proposes *removing* one is deleting a
  documented regression input — read the diff before keeping it.
- **When one thing has two representations, fuzz the differential.** An
  encode/decode round trip, an eager view beside a lazy overlay: assert the two
  *agree* rather than that neither crashes — crash-only fuzzing cannot see a wrong
  answer. The existing harnesses all carry such invariants; keep new ones to that
  bar.

## Verifying your own work

The failure modes here are ones that look like success:

- **Count failures, not passes.** `grep -c PASSED` happily reports 26 while a 27th
  test fails next to it. Check for ` FAILED` explicitly.
- **A green `clean build` can mean nothing ran** — the build cache short-circuits it
  to ~1s. If the timing looks too good, re-run with `--rerun-tasks` and confirm the
  test count.
- **Read the API before asserting against it.** Most wrong assertions in practice
  are a guessed accessor or overload; the record header or interface block answers
  it in one read. `CONVENTIONS.md` lists the ones that have already caught people.
- When a test you believe in will not go green, **suspect the code before you soften
  the assertion**. Every defect found in this repo's hardening passes surfaced that
  way, not from a mutant kill.
- **No failure is "unexplained" until the daemon log says so.** `~/.gradle/daemon/
  <version>/daemon-<pid>.out.log` keeps a failed build's full output even when the
  shell discarded it. One-shot `MINION_DIED` / worker-`EOFException` failures are
  transient — re-run; both diagnoses are in the shared casebook.

## Keeping this file honest

The mutation suite list above is checked by the build: `:sava-core:docsInSync` and
`:sava-rpc:docsInSync` fail when a registered suite is not named here, and both are
wired into `check` (so CI enforces them) as well as `qualityGate`. The task is
defined once by the `sava.docs-in-sync` convention plugin in `gradle/plugins`
(repo-local plugins, included from `settings.gradle.kts`). Prefer that pattern
over prose reminders for anything else derivable from the build.

The quality-gate block itself is checked the same way from the other direction:
the hardening plugin's `agentsTemplateInSync` task (also in `check`) fails when
sava-build's agent-instructions template changes until the block here is re-diffed
against it and the `hardening-template` marker above is updated to the digest the
failure prints. Sync or **act on** each changed bullet before updating the marker
— a new template requirement may mean new code, not just new prose.

Machine-specific context (local clone paths, environment notes) belongs in
`AGENTS.local.md`, which is git-ignored. Keep this file portable.

## Where the detail lives

- **`CONVENTIONS.md`** — API behaviours that are not guessable from a signature: how
  absence is represented (four different ways), unsigned/sentinel handling, argument
  order traps, wire-field vs accessor renames, and the existing test harnesses.
- **`HARDENING_NOTES.md`** — per-suite scope decisions, deliberate exceptions, and
  the per-package hardening history.
- **`AGAVE_SYNC.md`** — the Agave sync map: reference repositories, per-surface
  canonical sources, Alpenglow, known gaps, last-verified sync points, and the sync
  task checklist.
- **sava-build's `HARDENING.md`** — the full mutation-testing and fuzzing policy this
  contract summarises; its `HARDENING_CASEBOOK.md` holds the observed incidents
  behind each rule — read an entry before arguing with its rule.
- **`<module>/config/pitest/README.md`** — the accepted-mutant baselines and the
  written reason for every one of them.
