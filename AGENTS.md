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
  (`core/util`), `pitestCrypto` and `pitestVanity`; sava-rpc has `pitestResponses`
  and `pitestClient`. Per-suite scope decisions and exceptions are in
  `HARDENING_NOTES.md`.
- Every `pitest<Suite>` run prints its own summary — `killed/total (%)` plus the
  survived/uncovered split — and warns if the suite is mutating its own test
  sources. Read that line before planning a pass.
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
  rows observed to flip, never every timed-out row.
- **A wandering unkilled count is a defect to chase, not re-ratchet past** — a
  lucky run bakes in a row later runs fail on. Causes and the convergence
  method: `HARDENING.md`. The abstract-base annotation cause is ruled out here
  (JUnit 6.1.2 verification in `HARDENING_NOTES.md`); coverage attributed to
  field initializers still applies — exercise factories inside a `@Test`. The
  2026-07-21 convergence record in `HARDENING_NOTES.md` is the stability
  baseline.
- **Kill rates are bounded by the mutator set.** Big-value arithmetic is method
  calls, invisible to `STRONGER`. `EXPERIMENTAL_BIG_INTEGER` fired zero times
  in every candidate suite here (trial table in `HARDENING_NOTES.md`) — left
  off; re-trial if Big arithmetic is introduced.
- Fuzz findings become a committed seed input **and** a named regression test, never
  just a fix.

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
wired into `check` (so CI enforces them) as well as `qualityGate`. Prefer that
pattern over prose reminders for anything else derivable from the build.

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
