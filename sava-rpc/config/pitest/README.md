# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. Full policy — the three legal
outcomes for a new survivor, determinism requirements, targeting rules —
lives in sava-build's `HARDENING.md`.

Never refresh with `-PupdateMutationBaseline` just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below. Line numbers are part of the baseline key, so edits to a
mutated file shift entries — confirm the verify task's paired stale/"new"
rows are the shifted old ones before refreshing.

## Triaged equivalent mutants (accepted with reasons)

Triaged 2026-07-18 — all 8 baseline entries are accepted equivalents; there
is no untriaged debt in this module.

- `RpcCustomError.parseError` (both overloads), `changed conditional
  boundary` on the long→int clamp: at the exact `Integer.MIN_VALUE` /
  `MAX_VALUE` boundaries the mutant returns `Unknown` directly, and the
  original reaches the switch `default`, which is also `Unknown` — with
  identical iterator handling (`ji.skip()` on both routes). No defined
  custom-error code sits at an int boundary, so the routes cannot diverge.
  The killable near-misses — codes aliasing real ones under `(int)`
  truncation, `code ± (1L << 32)` — are pinned by
  `ParseCustomErrorCodeTests`.
- `Lamports.amount`, boundary/forced-true on `lamports < 0`: both branches
  build the same `BigInteger` for every long — non-negative values render
  identically through `Long.toUnsignedString` (at zero it is `"0"`), so the
  signed branch is allocation routing only.
- `JsonUtil.parseEncodedData`, forced-true on `ji.readArray()` plus the
  `NO_COVERAGE` null-return below it: both sit on the single-element-array
  branch, which always throws (the known parser quirk recorded in
  `AGENTS.md` — real providers send `[data, encoding]` pairs). Both routes
  reject, and the branch's return value is never observable. Killing these
  means changing the quirk, which is deliberately unchanged.
- `JsonUtil.parseEncodedData`, removed `System.Logger::log` call on the
  unsupported-encoding fallback: logging only.
- `JsonUtil.toJsonIntArray`, capacity math `(data.length << 2) + 2`:
  `StringBuilder` sizing only; the builder grows as needed.

Shrinking the baseline is always an improvement; growing it requires a
reason here.
