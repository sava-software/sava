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
fixes in that repo from a sava task.

## Quality gate & mutation ratchet

The process contract for main-source changes (full policy: sava-build's
`HARDENING.md`):

- **Run `./gradlew :sava-core:qualityGate` / `:sava-rpc:qualityGate` after changing
  main sources in that module** — unit tests plus every PIT suite, each diffed
  against its accepted baseline in `<module>/config/pitest/`. It is the definition
  of "safe to commit".
- While iterating, run only the suite that owns the code you touched. sava-core has
  `pitestBorsh`, `pitestEd25519`, `pitestEncoding`, `pitestTx` (the `tx` and
  `accounts/lookup` packages), `pitestToken2022`, `pitestMeta` (`accounts/meta`),
  `pitestDecimal` (`core/util`), `pitestCrypto` (`core/crypto`, excluding the
  `ed25519` subpackage which has its own suite) and `pitestVanity`; sava-rpc has
  `pitestResponses`. Suites target by package wildcard — a new class in a covered
  package is mutated by default.
- `pitestVanity` is the one deliberate exception: it allowlists
  `accounts.vanity.Subsequence*` rather than taking the package, because the mask
  workers search in an unbounded loop and every mutant that breaks the match
  predicate runs to the PIT timeout instead of failing fast. The workers stay
  covered by `MaskWorkerTests` without being mutated. Reasoning is recorded at the
  registration site in `sava-core/build.gradle.kts`.
- sava-vanity has a test source set (`EntrypointTests`, covering the system-property
  parsing and key-path resolution) but no PIT suite; it is an application module
  whose module-info exports nothing.
- A new unkilled mutant has exactly three legal outcomes: **kill it** with a test
  (prefer asserting the property it breaks over restating the implementation),
  **refactor** it out of existence, or **accept it** with a written reason in the
  module's `config/pitest/README.md`. Never run `-PupdateMutationBaseline` just to
  make the build pass.
- **Randomized tests use fixed seeds** (`BorshTests`): the ratchet needs
  deterministic kills; exploration belongs to the fuzz targets. Don't reintroduce
  unseeded `new Random()`.
- Fuzz findings become a committed seed input **and** a named regression test,
  never just a fix (the existing convention — see the per-surface hardening
  sections in `AGAVE_SYNC.md`).

## Keeping this file honest

The mutation suite list above is checked by the build. `:sava-core:docsInSync` and
`:sava-rpc:docsInSync` fail when a registered `hardening.mutation` suite is not named
here, and both are wired into `qualityGate` — so adding a suite without documenting it
is a red build rather than silent drift. Prefer that pattern over prose reminders for
anything else derivable from the build.

Machine-specific context (local clone paths, environment notes) belongs in
`AGENTS.local.md`, which is git-ignored. Keep this file portable.

## Where the detail lives

- **`AGAVE_SYNC.md`** — the Agave sync map: reference repositories and how to use them,
  per-surface canonical sources (Token-2022 extensions, HTTP RPC, WebSocket, errors,
  response records), the per-package hardening notes (tx, token-2022, borsh, encoding,
  ed25519), Alpenglow, known gaps, last-verified sync points, and the sync task
  checklist.
- **sava-build's `HARDENING.md`** — the full mutation-testing and fuzzing policy this
  contract summarises.
- **`<module>/config/pitest/README.md`** — the accepted-mutant baselines and the written
  reason for every one of them.
