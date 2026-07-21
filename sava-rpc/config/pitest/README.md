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
`readInputStream` early returns, and `wrapResponseParser`'s null check. These
are guards whose two branches converge on the same observable result for every
input a test can construct — the empty-collection and null cases are covered,
and the mutants that survive flip a check whose other side produces an
identical request or an identical parse.

**`checkResponse` status-range boundaries** (SURVIVED): **unreachable
in-harness**, not equivalent — this is the worked example in HARDENING.md's
acceptance-categories note. A real 199 *would* distinguish the `< 200` mutant,
but the JDK client treats 1xx as interim responses and never surfaces one as a
final status; a mock server replying 199 kills the connection before the guard
runs. Reaching it needs a raw-socket stub speaking HTTP/1.1 by hand — that
named escape hatch is what a later reader re-checks before assuming the
acceptance still holds.

**Pass-through accessors on `ReadHttpResponse`** (`request`,
`previousResponse`, `sslSession`): the record delegates to the wrapped response,
and the tests assert delegation against a stub whose own values are null or
empty — so returning null/empty directly is indistinguishable. Killing them
needs a stub with a non-empty `SSLSession` and a real `HttpRequest`, which buys
nothing.

**`gzipBufferSize` / `newPostRequest` `VoidMethodCallMutator`**: removing the
DEBUG log call and a header append that the request builder overwrites anyway.
Neither is observable.

## Triaged mutants — ws suite

Seeded 2026-07-21 at 50% detected (247 entries) when the suite was added over
`software.sava.rpc.json.http.ws.*` alongside the `NanoClock` seam, then worked
the same day to 69% (153 entries: ~127 SURVIVED, 26 NO_COVERAGE, plus flip
insurance) by driving the unsubscribe flows per channel, the connect/onClose
lifecycle, the sendText/sendPing failure callbacks, the interface-default
subscribe overloads, and the dispatch edge branches (unknown methods,
unsubscribed slot/root notifications, array-less fragmented buffers). The
check-loop executor is injectable (package-private on
`SolanaRpcWebsocketBuilder`; null creates the classic dedicated thread), so
the constructor-driven tests run with a `RecordingExecutor` and no background
thread exists to race clock-stepped assertions — which is what made the
resend-cycle ping assertion and the `handlePendingSubscriptions` counter kill
possible.

**Accepted with reasons** (triaged, closed):

- **`connect` timing mutants** (`connect` boundary/order/`MathMutator`,
  `lambda$connect$0` `lastWrite` write): the deferred branch runs on
  `CompletableFuture.delayedExecutor`, for which there is no seam — the
  mutants are distinguishable only by wall-clock timing of the connection
  attempt or by racing the executor thread. The named escape hatch is a
  schedulable-executor seam on `connect()`; the immediate branch's `lastWrite`
  write *is* pinned (`connectBuildsImmediatelyWhenIdle`).
- **Error-callback `EQUAL_ELSE` forks** (`lambda$sendText$0:681`,
  `lambda$sendPing$0:971`): forcing the handler branch with a null handler
  NPEs inside `whenComplete`, which the CompletionStage swallows —
  unobservable. The handler-present side of each fork is pinned by the
  failure-injection tests.
- **Logging only**: `VoidMethodCallMutator` on the log calls in the
  null-handler error paths, `onClose`, `onPong`, and the reason-blank message
  choice in `onClose:1011` (both branches close).
- **Defensive scans unreachable through the public API**
  (`removeDanglingSub` match path, `queueUnsubscribe:332`,
  `unsubscribe:639–641`): the match requires a subscription present in
  `subscriptionsBySubId` but absent from its channel map, which no public
  call sequence constructs — the map-first branch always wins. Every miss
  dimension (key, channel, commitment, notification method) is pinned by
  tests; only the impossible match is accepted.
- **Check-loop rows covered only by racing threads** (`run:187` EQUAL_IF,
  `run:188`/`run:191` VoidMethodCall — flip insurance, each observed flipping
  between detected and SURVIVED across identical runs): the builder-path
  tests create websockets with real internal executors, so the loop interior
  is executed by threads racing the test scheduler; the deterministic inline
  run tests cover only the interrupt- and closed-exit paths. The loop's
  `TIMED_OUT` rows come from the same place.
- **close() executor-ownership branch** (`close` EQUAL_ELSE on
  `internalExecutor`, `shutdown()`/`signal()` VoidMethodCall): the injected
  side — never shut down what the caller owns — is pinned by
  `executorServiceDefaultsNullAndAnInjectedOneIsNotShutDownByClose`; the
  internal executor's `shutdown()` and the wake-up `signal()` have no
  observable effect from outside (the signal only shortens how long the loop
  waits before noticing `closed()`).

**Remaining untriaged debt** (~115 rows): concentrated in `onWholeMessage` /
`onText` parse-branch survivors, `close`'s field-clearing breadth,
`handlePendingSubscriptions`' CAS choreography, and `queueSubscription`'s
signal/lock mutants (check-loop timing only). The 26 NO_COVERAGE rows are
mostly the same defensive-scan interiors and DEBUG-level log suppliers
(`lambda$onPing$0`/`lambda$onPong$0` run only with DEBUG enabled), plus
`Builder.clock`'s throwing default, reachable only by a Builder
implementation that does not override it.

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
  allocation routing only — `valueOf` is cheaper than widening the bits. The
  agreement is not just prose: sava-core's
  `ByteUtilTests.toUnsignedBigIntegerAgreesWithValueOfWhereCallersBranch`
  sweeps `valueOf` vs the widening over 10k seeded non-negative values plus
  boundaries on every build. See the decimal suite notes in sava-core for why
  the allocation-bound technique that would kill these was tried and reverted.
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
