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
`software.sava.rpc.json.http.client.*`. 56 entries: 27 SURVIVED and 30
NO_COVERAGE (one shared baseline key), from a population of 501 (88%
detected).

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
the same day to 73% (138 entries: ~117 SURVIVED, 24 NO_COVERAGE, plus flip
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

- **`connect` classic-path body** (one `VoidMethodCallMutator` row): the
  scheduler seam (2026-07-21, package-private `scheduler(...)` on the
  builder) closed the old timing family — the window boundary, the
  remaining-delay arithmetic, and the deferred `lastWrite` write are all
  killed against a `RecordingScheduler` with a stepped clock. What remains is
  the body of the default `CompletableFuture.delayedExecutor` branch, taken
  only when no scheduler is injected: identical logic to the seam path that
  *is* pinned, distinguishable only by real waits. The 25ms
  `connectRunsOnceTheReconnectDelayElapses` covers its execution.
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
- **Check-loop rows covered only by racing threads** (`run:205/214/215/218/224`
  as of the scheduler-seam line shift, unioned in both SURVIVED and
  NO_COVERAGE — flip insurance, each observed flipping across identical
  runs): the builder-path tests create websockets
  with real internal executors, so the loop interior is executed by threads
  racing the test scheduler; the deterministic inline run tests cover only
  the interrupt- and closed-exit paths. The loop's `TIMED_OUT` rows come from
  the same place. NOTE `-PupdateMutationBaseline` rewrites this file from a
  single run and silently drops these unions — re-append them after any
  refresh.
- **`queueSubscription` lock/signal choreography**: removing the
  `newSubscription.signal()` (or its lock/unlock pair) only changes when the
  background check loop wakes — every send is also driven synchronously by
  the message cycle, so no deterministic test can see the difference. The
  inner `putIfAbsent` duplicate branch is unreachable single-threaded: the
  outer `containsKey`/`get` guard already filtered duplicates, and only a
  concurrent subscribe between guard and put could reach it.
- **`ensureCapacity` growth arithmetic** (`MathMutator` on
  `(length << 1) + 2`): every variant still feeds `Math.max(newCapacity,
  minCapacity)`, so the buffer ends at least `minCapacity` and parsing is
  unaffected — allocation-size only. The growth path itself (doubling and
  the straight-to-minCapacity jump) is exercised by
  `oversizedFragmentedNotificationGrowsTheBuffer`.
- **close() executor-ownership branch** (`close` EQUAL_ELSE on
  `internalExecutor`, `shutdown()`/`signal()` VoidMethodCall): the injected
  side — never shut down what the caller owns — is pinned by
  `executorServiceDefaultsNullAndAnInjectedOneIsNotShutDownByClose`; the
  internal executor's `shutdown()` and the wake-up `signal()` have no
  observable effect from outside (the signal only shortens how long the loop
  waits before noticing `closed()`).

**Remaining untriaged debt** (~70 rows at 73% detected): what is left of the
`onWholeMessage`/`onText` parse-branch survivors after the reordered-params,
offset-buffer, and buffer-growth passes (2026-07-21: those plus post-close
dispatch tests killed `close()`'s ten field-clearing mutants — the reopen
tests alone had let them survive because the resend throttle masks an
uncleared map — and ~10 dispatch rows), plus
`handlePendingSubscriptions`' CAS choreography and assorted singles. The 24
NO_COVERAGE rows are mostly defensive-scan interiors and DEBUG-level log
suppliers (`lambda$onPing$0`/`lambda$onPong$0` run only with DEBUG enabled),
plus `Builder.clock`'s throwing default, reachable only by a Builder
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
