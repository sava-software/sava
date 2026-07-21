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

- **Run `./gradlew :sava-core:qualityGate` / `:sava-rpc:qualityGate` after changing
  main sources in that module** — unit tests plus every PIT suite, each diffed
  against its accepted baseline in `<module>/config/pitest/`. It is the definition
  of "safe to commit". While iterating, run only the suite that owns the code you
  touched.
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
- **A suite's percentage is not a target.** An accepted mutant with a written
  reason is finished work, not a gap. `decimal` sits at 81% because four of its
  22 mutants are documented equivalents — that is an accurate number, and driving
  it to 100% was tried on 2026-07-20 and reverted. Before trying to raise a
  figure, check whether what remains is uncovered code or already-closed triage.
- **Allocation and timing harnesses are a last resort.** They re-run once per
  mutant, need a `volatile` sink so escape analysis cannot delete what they
  measure, and flap when the margin is thin. Reserve them for properties that are
  a stated design goal — see the decimal notes in
  `sava-core/config/pitest/README.md` for a worked example of when not to.
- A new unkilled mutant has exactly three legal outcomes: **kill it** with a test
  (prefer asserting the property it breaks over restating the implementation),
  **refactor** it out of existence, or **accept it** with a written reason in the
  module's `config/pitest/README.md`. Never run `-PupdateMutationBaseline` just to
  make the build pass.
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

## Keeping this file honest

The mutation suite list above is checked by the build: `:sava-core:docsInSync` and
`:sava-rpc:docsInSync` fail when a registered suite is not named here, and both are
wired into `qualityGate`. Prefer that pattern over prose reminders for anything else
derivable from the build.

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
  contract summarises.
- **`<module>/config/pitest/README.md`** — the accepted-mutant baselines and the
  written reason for every one of them.
