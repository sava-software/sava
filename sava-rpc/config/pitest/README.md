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

## Triaged mutants — client suite

Seeded 2026-07-20 when the `client` suite was added over
`software.sava.rpc.json.http.client.*`. 56 entries: 27 SURVIVED and 29
NO_COVERAGE, from a population of 498 (89% killed).

Unlike the `responses` suite below, this one **does** carry debt. It was
registered deliberately red and worked down from 54% over several passes; what
remains is recorded here rather than hidden by narrowing the suite.

**Transport paths not driven by the harness** (29 NO_COVERAGE — the bulk):
`sendPostRequestNoWrap`, `sendGetRequestNoWrap`, `sendGetRequest` and the
`applyResponse` tail of `BaseJsonResponseController`. `RpcRequestTests` drives
requests through `sendPostRequest`, so the unwrapped and GET variants are never
entered. Killing these needs new harness scaffolding — a local server exercising
the GET and no-wrap routes — rather than another `registerRequest` line. The
remaining `simulateTransaction` / `simulateTransactionWithInnerInstructions`
entries are `Transaction`-plus-accounts overloads in the same position.

**Fast-path and defensive conditionals** (SURVIVED): `joinKeys` and
`ProgramAccountsRequestRecord.toJson` null/empty guards, `readBytes` and
`readInputStream` early returns, `wrapResponseParser`'s null check, and the
`checkResponse` status-range boundaries. These are guards whose two branches
converge on the same observable result for every input a test can construct —
the empty-collection and null cases are covered, and the mutants that survive
flip a check whose other side produces an identical request or an identical
parse.

**Pass-through accessors on `ReadHttpResponse`** (`request`,
`previousResponse`, `sslSession`): the record delegates to the wrapped response,
and the tests assert delegation against a stub whose own values are null or
empty — so returning null/empty directly is indistinguishable. Killing them
needs a stub with a non-empty `SSLSession` and a real `HttpRequest`, which buys
nothing.

**`gzipBufferSize` / `newPostRequest` `VoidMethodCallMutator`**: removing the
DEBUG log call and a header append that the request builder overwrites anyway.
Neither is observable.

## Triaged equivalent mutants (accepted with reasons)

Triaged 2026-07-18 — all 8 `responses` baseline entries are accepted
equivalents, with no debt. That was true of the whole module until the
`client` suite was added on 2026-07-20; its 56 entries are triaged above but
include real coverage debt, so the module-wide claim no longer holds. The
`responses` baseline itself should stay debt free.

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
  build the same `BigInteger` for every long, so the signed branch is
  allocation routing only — `valueOf` is cheaper than widening the bits. See
  the decimal suite notes in sava-core for why the allocation-bound technique
  that would kill these was tried and reverted.
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
