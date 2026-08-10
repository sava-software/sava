# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,mutator,STATUS` — line numbers are metadata, carried as
a trailing `# line N` tag every refresh rewrites, so editing above a mutated
method churns nothing. Full policy — the three legal outcomes for a new
survivor, determinism requirements, targeting rules — lives in sava-build's
`HARDENING.md`.

## Newly adopted suite — 2026-08-04, seeded debt, not acceptance

`encoding` (targets `json.*` and `json.http.request.*`, subtracting the three
sibling suites' packages) was registered to close `mutationOwnershipAudit`: every
compiled production class in this module now sits in some suite's target
universe, with no `declineExclusionAudit` anywhere. It measured 62 mutants, 72%
detected, and its first baseline was seeded from the full unkilled population —
17 rows (12 `SURVIVED`, 5 `NO_COVERAGE`), all `# untriaged`. That is debt made
explicit, not equivalence: nothing below argues those rows yet. `RpcEncoding` is
the one to read first, since its missing `jsonParsed` constant is a deliberate
API invariant (`AGENTS.md`) rather than an omission.

Never run a `pitest<Suite>BaselineUpdate` task just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below. A failure classifies each new row (`newly covered` vs shares an
accepted key vs unexplained) and closes with a churn tally. A key unkilled at
a line no row's `# line` tag names draws the line-drift advisory: the code an
acceptance argues about moved, or a new mutant sits under an old acceptance
(the line-less key's one documented blind spot) — re-read the argument below,
then let the next refresh rewrite the tag.

Arguments below name **methods and constructs, not line numbers**: prose
anchors are not machine-checked and rot silently on the first refactor (the
ws family's did, wholesale, before 2026-08-01). The authoritative anchor is
each row's `# line` tag in the CSV, which every refresh rewrites and the
line-drift advisory checks. Cite a line here only where it is the historical
record of a past state.

**Identical rows are sibling mutants — never dedupe these files.** One
compound condition emits a mutant per operand or branch direction at the same
`class,method,mutator,STATUS` key (and one `MathMutator` key can cover
different operations across a method — `ensureCapacity`'s line 928 was a
shift *and* an add — the `# line` tags telling the copies apart), so a key
legitimately repeats. The comparison is a multiset: the
copies were collapsed until 2026-07-23, which let a killed sibling regress
unnoticed behind its accepted twin. Migrating these baselines materialized 13
copies — 9 in `ws`, 3 in `responses`, all inside the families below, and one
in `client` that turned out to be **killable**, not equivalent (see the
`checkResponse` entry). When one sibling survives and another is killed, the
verify names the killing test: the survivor is that test's opposite branch,
and it is triaged as its own mutant rather than assumed covered.

## Triaged mutants — client suite

Seeded 2026-07-20 when the `client` suite was added over
`software.sava.rpc.json.http.client.*`. 56 entries: 27 SURVIVED and 30
NO_COVERAGE (one shared baseline key), from a population of 501 (88%
detected). The 2026-07-31 transport-harness pass resolved the coverage debt:
578/601 detected (96%), 23 rows, all SURVIVED, zero NO_COVERAGE.

Unlike the `responses` suite below, this one **does** carry debt. It was
registered deliberately red and worked down from 54% over several passes; what
remains is recorded here rather than hidden by narrowing the suite.

**Transport paths not driven by the harness — RESOLVED 2026-07-31, the whole
family (38 NO_COVERAGE, the baseline's bulk) left in the refresh.** The escape
this entry named — a local server exercising the GET and no-wrap routes — is
`JsonHttpClientTransportTests`: an echo server answering with the method and
path it saw, so a parser asserting the payload asserts end to end which HTTP
request the route built, plus wrapped-vs-no-wrap pinned by whether the parser
receives a `ReadHttpResponse`. The never-constructed parser controllers
(`JsonRpcBytesValueParseController`, `FullContextJsonRpcResponseParser`, the
`applyGenericResponse` bytes variant) needed no server at all —
`StubHttpResponse` drives them in `JsonRpcResponseParserTests` and
`GenericJsonResponseParserTests`. The `simulateTransaction` /
`simulateTransactionWithInnerInstructions` `Transaction` overloads and the
`BigInteger` `getProgramAccounts` overload were ordinary `registerRequest`
cases after all, distinguished by their options objects. What the coverage
surfaced became the `# logging only` family below; everything else was an
ordinary kill.

**Log-and-rethrow diagnostics — killed 2026-07-31, same day they surfaced.**
The parse-failure tails (`BaseJsonResponseController.applyResponse:42–43`,
`BaseJsonRpcResponseParser.parseRpcException:22`) log and rethrow; the first
triage read them as logging-only because the rethrow is pinned by identity.
The second read noticed the log line is load-bearing after all: the rethrown
exception is the JSON parser's own and carries no record of the exchange, so
the logged status and body are the *only* copy of what the provider sent —
the same only-observable standing as the ws check-loop funnel. Both tails are
now asserted through the JUL backend (`TestLogs`, the funnel's technique),
including message content, which kills the log-call removals and the
forced-empty side of the message's null-guard ternary. One row remains —
baseline label `# dead null arm`
(`applyResponse:43`, `RemoveConditionalMutator_EQUAL_ELSE`): forcing the
`body == null` guard false inside the log argument is equivalent *in
context*, because `checkResponse` returns before the parser runs whenever the
body is null, so the catch block never sees one — the guard's null arm is
defensive dead code there, unreachable by construction rather than untested.

One `# fast-path guard` row — `BaseSolanaJsonRpcClient.joinKeys`
`RemoveConditionalMutator_EQUAL_IF` — has **no counterpart in the licensed
population** and is reported unmatched on every run, the same way nine `ws` rows
are (see the `ws` note on that). It is kept for the same reason: the licensed
toolchain simply does not generate it, no test killed it, and it returns the
moment the certificate is absent. The 2026-08-04 PIT 1.25.9 version-migration
refresh proposed dropping it along with two `checkResponse` / `readBytes` rows
that the licensed run *did* kill; only the two kills were taken.

**Fast-path and defensive conditionals** — baseline label `# fast-path guard`
(SURVIVED): `joinKeys` and
`ProgramAccountsRequestRecord.toJson` null/empty guards, `readBytes` and
`readInputStream` early returns, and `wrapResponseParser`'s null check. These
are guards whose two branches converge on the same observable result for every
input a test can construct — the empty-collection and null cases are covered,
and the mutants that survive flip a check whose other side produces an
identical request or an identical parse.

**`checkResponse` status-range boundaries** — **withdrawn 2026-07-31, the
`< 200` side was never unreachable.** The acceptance read "the JDK client
never surfaces a 1xx as a final status; reaching the guard needs a raw-socket
stub speaking HTTP/1.1 by hand" — but the transport was never the only way
in: the gate's contract is over any `HttpResponse`, and `StubHttpResponse`
constructs a 199 directly, exactly as every other envelope-gate case in
`JsonRpcResponseParserTests` is driven. The 199 case joined
`resultEnvelopeUnderANonSuccessStatusIsRejected` (and the generic
controller's `nonSuccessStatusesAreRejectedWithStatusAndBody`), and both
suites' `< 200` rows are ordinary kills. Same lesson as the
`ReadHttpResponse` withdrawal below: the unreachability was the fixture
strategy's, not the code's — re-read "needs a harness we don't have"
acceptances against every fixture the suite already owns.

The `>= 300` side had already been killed the same way. The multiset migration
(2026-07-23) materialized a second `RemoveConditionalMutator_ORDER_IF` copy at
`checkResponse:32` that the old set-based baseline had absorbed: the `>= 300`
operand, which is distinguishable by a case the harness simply never had — an
HTTP failure status carrying a well-formed `result` envelope, where the real
code lets the status veto the body. `resultEnvelopeUnderANonSuccessStatusIsRejected`
(300/400/500/503 with `{"result":"ok"}`) pins that contract and killed the
sibling *and* the `ConditionalsBoundaryMutator` row at the same line — the 300
case distinguishes `>= 300` from `> 300`. Worked exactly as the casebook's
sibling entry predicts: the survivor at an accepted coordinate was the opposite
operand, and it was not equivalent, only untested.

**Pass-through accessors on `ReadHttpResponse`** (`request`,
`previousResponse`, `sslSession`) — **withdrawn 2026-07-24, they were never
equivalent.** The acceptance read "the tests assert delegation against a stub
whose own values are null or empty, so returning null/empty directly is
indistinguishable; a real `HttpRequest` and a non-empty `SSLSession` buy
nothing" — but the indistinguishability was the *fixture's*, not the code's: a
stub returning the mutator's own replacement value withdraws that mutant before
the tests are consulted. `StubHttpResponse` now answers with a real
`HttpRequest`, a 302 predecessor and the never-connected `SSLSession` from
`SSLContext.getDefault().createSSLEngine()`, and
`readHttpResponseDelegatesEverythingButTheBody` asserts delegation by identity;
all three rows are ordinary kills and left the baseline. Read every remaining
"the stub returns null anyway" acceptance the same way.

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

- **`connect` classic-path body** — baseline label `# classic delay path`
  (one `VoidMethodCallMutator` row): the
  scheduler seam (2026-07-21, package-private `scheduler(...)` on the
  builder) closed the old timing family — the window boundary, the
  remaining-delay arithmetic, and the deferred `lastWrite` write are all
  killed against a `RecordingScheduler` with a stepped clock. What remains is
  the body of the default `CompletableFuture.delayedExecutor` branch, taken
  only when no scheduler is injected: identical logic to the seam path that
  *is* pinned, distinguishable only by real waits. The 25ms
  `connectRunsOnceTheReconnectDelayElapses` covers its execution.
- **Error-callback `EQUAL_ELSE` forks — killed 2026-08-01, acceptance
  withdrawn.** The `whenComplete` handlers of `sendText` and `sendPing` were
  accepted on the argument that forcing the handler branch with a null handler
  NPEs inside `whenComplete`, which the `CompletionStage` swallows, leaving
  nothing to assert. That was a limit of the harness, not of the code: with no
  user handler installed the failure is logged, and the log line is the only
  record it happened. `WsDiagnosticLogTests` asserts that record, so both forks
  are ordinary kills and no row carries this label any more. Kept as history —
  the argument to re-read before accepting the next "unobservable callback".
- **Logging only — mostly killed 2026-08-01; one row left.** The family read
  "removing a log call is unobservable", which was true only while the harness
  had no way to read a log. It does now: `WsDiagnosticLogTests` captures the
  JUL backend (the check-loop funnel's technique, generalised) and asserts
  *content* — the ping/pong payload, the close reason and code, and the
  `sendText`/`sendPing` failures raised with **no** user handler installed,
  where the log line is the only record the event happened at all. The lazy
  `() -> new String(...)` payload suppliers read as `NO_COVERAGE` only because
  nothing enabled DEBUG; enabling it made them ordinary kills. Eleven rows
  left the baseline. The survivor — baseline label `# logging only` — is
  `onClose`'s `reason == null` operand (`EQUAL_ELSE`): a null and a blank
  reason take the same branch and log the same record, so no assertion over
  the output can separate them. Two lessons, both already in the casebook:
  *ask what information exists only on the mutated path*, and a
  level-gated log line is uncovered until the test turns the level on.
- **Defensive scans — the scan runs, the match cannot** — baseline label
  `# defensive scan`
  (`removeDanglingSub`'s match path, the `queueUnsubscribe` compute lambda,
  and `unsubscribe`'s generic-subscription scan). The split matters, because
  the two halves are accepted on different grounds and the row's status says
  which: the **15 `SURVIVED` rows are executed** — tests drive the scan on its
  miss path every time, and those mutants perturb a comparison whose answer is
  `false` either way — while the **6 `NO_COVERAGE` rows are the match branch's
  interior**, never executed by any test, and accepted as unreachable *by
  construction* rather than as equivalents. Both scans are the fallback
  taken when the map-first lookup misses, and the match requires a
  subscription present in `subscriptionsBySubId` but absent from its channel
  map. No public call sequence constructs that, and the *mechanism* is worth
  naming because it is what a future edit could break:
  `queueUnsubscribe(Subscription)` removes from **both** maps — the by-sub-id
  entry and, via its caller, the channel entry — so the two cannot diverge.
  Every miss dimension (key, channel, commitment, notification method) is
  pinned by tests; only the impossible match is accepted.

  Since that is a claim about code rather than about a harness limitation, it
  is pinned executably by `unsubscribingTwiceFindsNothingDangling`: a second
  un-subscribe of an already-confirmed, already-removed subscription must
  report `false`. Leave a by-sub-id entry behind and the scan finds it, the
  test fails, and the family is re-triaged — rather than silently becoming
  reachable while this section still says it is not. *(If the invariant is
  ever made structural instead, both scans become dead weight and could be
  deleted outright — an owner decision, like the confirmation arm above.)*
- **Check-loop interior — RESOLVED 2026-07-22, flip insurance deleted for
  cause.** The `run` loop's interior was reachable only by threads racing the
  test scheduler, and its keys (`run:205/214/215/218/224`) were unioned in
  both SURVIVED and NO_COVERAGE as flip insurance. The loop body is now the
  package-private `checkCycle(long awaitNanos)` seam (`awaitNanos <= 0` never
  parks), driven deterministically by the lifecycle tests: the retry-window
  resend, the socketless no-op, and the unhandled-exception funnel (closed
  *and* logged — asserted through the JUL backend, so the funnel cannot go
  silent). All eleven family rows left the baseline in the refresh; the one
  remaining loop-family key is below.
- **`checkCycle` `unlock()` removal** — baseline label `# unlock in finally`
  (`checkCycle`, VoidMethodCall): `unlock()` sits in a `finally`; its removal is observable only by a second
  thread blocking on the lock, i.e. a timing harness — a last resort this
  single call does not earn. The loop's lock/await lines are otherwise
  deterministically covered by the checkCycle tests. Accepted as unreachable
  in-harness; a deterministic kill would need an injectable lock or a
  package-private hold-count probe.
- **`run` while-condition forced always-true** (`RemoveConditionalMutator`
  `EQUAL_IF`): a loop that never exits is caught by PIT's timeout —
  `TIMED_OUT`, detected, not baselined — and with the interior now covered
  deterministically that detection is stable across modes (verified by
  `pitestModeCompare` below). Not an acceptance; recorded so the next reader
  does not hunt for a missing baseline row.
- **Notification fast-forwards rescued by the reset-fallback funnel** — baseline
  label `# fast-forward funnel`
  (`NakedReceiverMutator` on the `skipRestOfObject()` calls in `publish` (two)
  and `onWholeMessage`, and the `skip()` in `publishGeneric`, 2026-07-22):
  each drops the scan-ahead before
  `skipUntil("subscription")`. `skipUntil` stays inside the current object
  and returns null at its `}`, so without the fast-forward it exhausts the
  result object, misses, and the `reset(paramsMark)` fallback re-scans from
  the params mark and finds the id anyway — the funnel produces the identical
  dispatch. The funnel itself is pinned: `reorderedNotificationFields` drives
  the fallback directly (subscription-before-result, value-before-context)
  and killed the fallback-line mutants, and
  `unknownGenericSubscriptionIdUnsubscribes` pins the miss branch. Only the
  redundant fast-forwards are accepted. (The `onText` NakedReceiver row this
  entry once handed to the buffered-path family was killed since and is no
  longer in the baseline.)
- **Confirmation-error arm, dead by construction** — baseline label
  `# dead confirmation arm` (`onWholeMessage`, `EQUAL_ELSE` on
  `sub.jsonRpcException() != null`, 2026-08-01): the arm can never run. A
  top-level `error` field is claimed by the enclosing
  `skipUntil("error") != null` branch, which is the *only* thing that sets
  `SubConfirmation.jsonRpcException`, so inside its `else` that field is
  always null. Proven rather than argued: `unsubscribeConfirmationIsAcceptedSilently`
  covers the test and **kills** the forced-true mutant (dereferencing the null
  exception NPEs into the method's catch, which reports to the exception
  subscribers), while forcing it false stays equivalent — skipping a branch
  that does nothing. Its body (the `-32602` check and the WARNING) is
  `NO_COVERAGE` for the same reason and is not baselined as equivalent.
  The `// May happen due to stale/duplicate un-subscription requests` comment
  records an expectation the routing does not bear out: that case *is*
  handled, one branch up, by the `Invalid subscription id` suppression — this
  arm is a vestigial second copy. **Open decision for the owner:** delete the
  arm (removing four rows, the ratchet's second legal outcome) or keep it as
  defence in depth against a future routing change; accepted meanwhile so the
  finding is recorded rather than pending.

- **Lock/signal choreography** — baseline label `# lock signal wake`
  (`queueSubscription`, and the identical pair in `slotSubscribe`,
  `rootSubscribe` and the generic `subscribe`): removing the
  `newSubscription.signal()` (or its lock/unlock pair) only changes when the
  background check loop wakes — every send is also driven synchronously by
  the message cycle, so no deterministic test can see the difference. The
  argument is about the choreography, not the method, so it covers every site
  that runs it; they were only ever separate rows because the key is
  per-method.
**Channel-guard operands — partly killed 2026-08-01 by message-id continuity.** The
`sub == null || !sub.containsKey(commitment)` guards survived every
duplicate-subscribe test for a structural reason: forcing an operand true lets
the duplicate fall through to `queueSubscription`, whose own `putIfAbsent`
check *also* returns false, so the return value is identical either way. The
difference is upstream of the check — `queueSubscription` opens with
`msgId.incrementAndGet()`, so the mutant **burns a message id** on its way to
returning false. `aRejectedDuplicateSubscribeConsumesNoMessageId` subscribes,
duplicates and subscribes again per channel, then asserts the ids actually
written to the socket are consecutive. Reusable whenever two paths agree on
their result: look for a side effect one of them takes on the way there.

The technique did not clear the family, and the baseline is the honest record:
three `RemoveConditionalMutator_EQUAL_ELSE` rows still survive on these guards —
`signatureSubscribe` (one) and `programSubscribe` (two siblings, one per operand
of the `||`) — and they remain `# untriaged` rather than being folded into the
kill claim above. The test only reaches the channels it drives, so extending it
to those two is the obvious next pass; do not relabel them into an argued family
until something actually argues them.

- **The dead duplicate guard** — baseline label `# duplicate guard dead`
  (`queueSubscription` and generic `subscribe`'s `putIfAbsent` checks and
  their `return false`; the `BooleanTrueReturnVals` rows on
  `return queueSubscription(...)` in `accountSubscribe`, `logsSubscribe`,
  `signatureSubscribe`, `subscribeToTokenAccounts` and `programSubscribe`):
  one argument covering the family. Each channel method already filters
  duplicates with its own `sub == null || !sub.containsKey(commitment)`
  guard — pinned by the duplicate-subscribe assertion in every channel's
  test — so the inner `putIfAbsent` duplicate branch is unreachable
  single-threaded; only a concurrent subscribe between guard and put could
  reach it. The evidence is in the baseline rather than in this prose: those
  inner `return false` rows are **`NO_COVERAGE`**, so the methods provably
  never return false, which is exactly why forcing their callers'
  `return queueSubscription(...)` to `true` is equivalent. If a concurrency
  seam ever makes the duplicate branch reachable, those rows stop being
  uncovered and the whole family comes back for triage.
- **`ensureCapacity` growth arithmetic** — baseline label `# capacity math`
  (two sibling `MathMutator` rows on the widened
  `((long) length << 1) + 2`): every variant still feeds
  `Math.clamp(newCapacity, minCapacity, maxMessageLength)`, so whatever the
  arithmetic produces, the buffer ends at least `minCapacity` — parsing is
  unaffected and only the allocation size moves. The clamp's own bounds are
  mutant-proof from here: a mutated shift cannot reorder `minCapacity` and
  `maxMessageLength`, and the `onText` gate already guarantees
  `minCapacity <= maxMessageLength`, so no variant can make `clamp` throw. The
  growth path itself (doubling and
  the straight-to-minCapacity jump) is exercised by
  `oversizedFragmentedNotificationGrowsTheBuffer`, and the upper bound by
  `MessageSizeCapTests`.
- **close() executor-ownership branch** — baseline label `# executor ownership`
  (`close` EQUAL_ELSE on
  `internalExecutor`, `shutdown()`/`signal()` VoidMethodCall): the injected
  side — never shut down what the caller owns — is pinned by
  `executorServiceDefaultsNullAndAnInjectedOneIsNotShutDownByClose`; the
  internal executor's `shutdown()` and the wake-up `signal()` have no
  observable effect from outside (the signal only shortens how long the loop
  waits before noticing `closed()`).

**Remaining untriaged debt** (49 rows of 105; 82% detected as of the
2026-08-04 licensed run): what is left of the
`onWholeMessage`/`onText` parse-branch survivors after the reordered-params,
offset-buffer, and buffer-growth passes (2026-07-21: those plus post-close
dispatch tests killed `close()`'s ten field-clearing mutants — the reopen
tests alone had let them survive because the resend throttle masks an
uncleared map — and ~10 dispatch rows), plus
`handlePendingSubscriptions`' CAS choreography and assorted singles. The 12
NO_COVERAGE rows are mostly defensive-scan interiors and DEBUG-level log
suppliers (`lambda$onPing$0`/`lambda$onPong$0` run only with DEBUG enabled).

**Nine rows this suite keeps that the licensed toolchain never observes** — baseline
label `# unlicensed only` for the two whose *sole* argument is this, the rest
carrying their own family label. Every baseline here was first recorded with
open-source PIT; the committed `arcmutate-licence.txt` generates a smaller
population (605 → 573 mutants for `ws`, measured 2026-08-04; the absolute counts move
with the source — the licensed population was 580 after the 2026-08-08 websocket
liveness work, and the unlicensed one has not been re-measured since),
so a handful of accepted rows have no licensed counterpart and the verify reports
them as unmatched on every run. **They are not stale and must not be pruned** —
the mutants return the moment the certificate is absent or expires (15/08/2027),
and deleting them would drop argued acceptances that a later unlicensed run would
then re-raise as unexplained newcomers.

Six have no licensed mutant at their key at all: `ensureCapacity`
`ORDER_IF`, `onText` `ORDER_IF`, `lambda$queueUnsubscribe$0` `EQUAL_IF` (two
siblings), and `logsSubscribe` / `programSubscribe` `EQUAL_IF`. Three sit at keys
the licensed run does reach but with fewer survivors — two
`handlePendingSubscriptions` `EQUAL_IF` and one `onWholeMessage` `EQUAL_IF`.
The `logsSubscribe`/`programSubscribe` pair is the worked example of the trap: an
earlier pass deleted them as "since killed" because a licensed run stopped
reporting them, and the licensed report proves the opposite — **zero**
`RemoveConditional*_EQUAL_IF` mutants exist for either method, so nothing killed
them. A row leaves this baseline only when the same licensed mutant is observed
and *killed*; absence is not evidence.

`Builder.clock`'s uncovered default was the last of those NO_COVERAGE rows, and
it is gone — **not refactored around the ratchet, but removed from the public
interface entirely, 2026-08-04, by owner decision.** The route there is worth
recording because two of the three options were wrong.

The pair `clock(NanoClock)` / `clock()` shipped on the public
`SolanaRpcWebsocket.Builder` in **25.8.2** as default methods, the setter throwing
and the getter returning `NanoClock.SYSTEM`. The getter's `NullReturnVals` mutant
was `NO_COVERAGE`, because the shipped `SolanaRpcWebsocketBuilder` overrides both
and nothing else implements the interface. A first pass made them **abstract** to
delete the mutant; that is a source break for any outside implementor and a
deferred `AbstractMethodError` for one already compiled, in a repo whose release
config is `always-bump-patch` — the ratchet's "refactor it out of existence" does
not license editing published API. A second pass restored the defaults and killed
the mutant with a stub implementing `Builder` without overriding `clock`.

The owner's call replaced both: **the clock never belonged on the interface.** It
is a test seam — the whole point is advancing time instead of waiting on the
reconnect throttle and ping pacing — and it now sits on the package-private
`SolanaRpcWebsocketBuilder` beside `executorService` and `scheduler`, which were
already impl-only for exactly this reason and already carry the "deliberately not
on the public interface" javadoc. Every caller was a package-private test, so
nothing outside this package loses anything it could reach. The impl's setters
became covariant (returning `SolanaRpcWebsocketBuilder`) so existing chains keep
reaching the seam, and the tests' `builder()` factory now returns the concrete
type; no test chain changed. The stub test went with the default it existed to
cover.

This *is* a removal from 25.8.2's published surface, and it is deliberate rather
than incidental — which is the distinction `AGENTS.md` draws. Population effect:
`ws` went 573 → 572 mutants, the baseline unchanged at 107 rows, because the row
that used to be here was already killed rather than accepted.
`Builder.maxMessageLength` was never a precedent for any of this: it was born
abstract after 25.8.2 and has never shipped.

## Triaged equivalent mutants (accepted with reasons)

Triaged 2026-07-18 — all 8 `responses` baseline entries are accepted
equivalents, with no debt. That was true of the whole module until the
`client` suite was added on 2026-07-20; its 56 entries are triaged above but
include real coverage debt, so the module-wide claim no longer holds. The
`responses` baseline itself should stay debt free.

- `RpcCustomError.parseError` (both overloads) — baseline label
  `# int clamp boundary` — `changed conditional
  boundary` on the long→int clamp: at the exact `Integer.MIN_VALUE` /
  `MAX_VALUE` boundaries the mutant returns `Unknown` directly, and the
  original reaches the switch `default`, which is also `Unknown` — with
  identical iterator handling (`ji.skip()` on both routes). No defined
  custom-error code sits at an int boundary, so the routes cannot diverge.
  The killable near-misses — codes aliasing real ones under `(int)`
  truncation, `code ± (1L << 32)` — are pinned by
  `ParseCustomErrorCodeTests`.
- `Lamports.amount` — baseline label `# allocation routing` — boundary/forced-true on `lamports < 0`: both branches
  build the same `BigInteger` for every long, so the signed branch is
  allocation routing only — `valueOf` is cheaper than widening the bits. The
  agreement is not just prose: sava-core's
  `ByteUtilTests.toUnsignedBigIntegerAgreesWithValueOfWhereCallersBranch`
  sweeps `valueOf` vs the widening over 10k seeded non-negative values plus
  boundaries on every build. See the decimal suite notes in sava-core for why
  the allocation-bound technique that would kill these was tried and reverted.
- `JsonUtil.parseEncodedData` — baseline label `# single element array` —
  forced-true on `ji.readArray()` plus the
  `NO_COVERAGE` null-return below it: both sit on the single-element-array
  branch, which always throws (the known parser quirk recorded in
  `AGENTS.md` — real providers send `[data, encoding]` pairs). Both routes
  reject, and the branch's return value is never observable. Killing these
  means changing the quirk, which is deliberately unchanged.
- `JsonUtil.parseEncodedData:37` — baseline label `# reset position equivalent`
  — `NakedReceiverMutator` on
  `ji.reset(mark2).skipRestOfArray()` (2026-07-22): dropping the `reset`
  makes `skipRestOfArray` re-skip the elements between the cursor (after the
  decoded data element) and `mark2` instead of starting there — either way
  the iterator stops after the array's `]`, so the terminal position is
  identical. The line is covered: `skippedValuesLeaveTheIteratorAligned`
  parses a field *after* the array and killed the sibling
  `skipRestOfArray`-drop on the same line; only the reset-drop is
  position-equivalent.
- `JsonUtil.parseEncodedData` — baseline label `# logging only` — removed
  `System.Logger::log` call on the unsupported-encoding fallback.
- `JsonUtil.toJsonIntArray` — baseline label `# capacity math` —
  `(data.length << 2) + 2`:
  `StringBuilder` sizing only; the builder grows as needed.

Shrinking the baseline is always an improvement; growing it requires a
reason here.

## Triaged mutants — ws suite, 2026-08-10 re-measure

The post-review engine is a different program from the one the 2026-07 baseline
described: the Connection refactor, wire-attempt ordinals, and the connect and
close ownership rules all landed since. Re-measured fresh (`-PnoMutationHistory`)
the suite reads 920/1163 detected (79%), up from 865 (74%) at the start of this
pass, with no-coverage rows down 40 -> 24 and timeouts 19 -> 9.

Fifty-five mutants were killed rather than argued, in priority order: the
lock-release contract on every entry point that takes the lifecycle lock; the
diagnostics that are the sole record of a settlement classification; a throwing
consumer on every channel plus the onSub callback; the orphan and retired-id
compensation rules; the escalation deadline's exclusivity and the age it
reports; and the validation of all three generic method names. Each is a place
where a regression could have shipped unseen.

**Reachability defended by single-flight** — `ownBuild`'s stale check
(`SolanaJsonRpcWebsocket.lambda$ownBuild$0`, both `RemoveConditionalMutator`
directions at the `generation != connectGeneration` operand). The hook releases
a socket produced by an attempt that has lost authority, and authority is lost
two ways: the instance closed, or the generation moved. Only the first is
reachable while a build is pending, and it is pinned by
`anImmediateBuildCompletingAfterCloseAbortsItsUnadoptedSocket`. The second is
not: a `connect()` arriving while an attempt is unsettled returns a copy of that
attempt without building, so the generation cannot move underneath a pending
build — and once a build settles, `connect()`'s own replacement path joins it and
aborts the unadopted socket before any successor is created. The operand is
belt-and-braces against a future where single-flight is relaxed, which is
exactly when someone will need it. *Attempted and withdrawn 2026-08-10: a test
driving two connects finds one handshake, not two — the single-flight gate is
the proof.*

## Timed-out mutants (audited set)

`TIMED_OUT` is detected — never baselined — but the watchdog observed
slowness, not wrongness: for these mutants the ratchet cannot see a weakened
covering assertion, so per HARDENING.md the summary's `N timed out
(load-dependent)` is an audited set, not a count. Membership is
machine-checked: `ws-timeouts.csv` holds the line-less `class,method,mutator`
keys with a `cause:` tag each, and the verify warns on any timeout outside
them. A member can also read `KILLED` on a given run.

Re-measured 2026-08-10 against the post-review engine: 11 timeouts, all
audited, down from 19. The set shrank by making the deadline the *test's*
rather than the watchdog's — the lesson worth carrying to the next member,
because a timeout is a detection nobody can audit for a weakened assertion.
Two families were retired outright:

- **The unlock family — killed, not audited.** Seven members (`checkCycle`,
  `deferredBuild`, `onOpen`, `rootSubscribe`, `lambda$ownBuild$0`, and both
  `lambda$sendSubscription$*`) shared one mechanism: an `unlock()` removed
  from a `finally`, after which a LATER locked entry parks and the run reads
  as slow rather than wrong. `WsLockReleaseContractTests` asserts the release
  contract directly — the lock is package-private for exactly this — so each
  became an ordinary kill. Only `SolanaJsonRpcWebsocket.checkCycle`'s stays below, and
  only because its kill races test ordering inside the minion.
- **The park-on-connect family — mostly killed.** A stranded token or a
  dropped completion bridge leaves a joining caller parked forever, and a
  5-second test budget lost that race to PIT's ~1.6s watchdog by
  construction. Shrinking `aDefaultDeferredConnectFiresThroughItsToken` to
  500ms (the real path takes ~25ms), asserting `isDone()` on the scheduled
  attempt the moment its task returns, and pinning that an immediate attempt
  is settled when `connect()` returns converted the majority into failed
  assertions. Three survive as racers.

Structural causes, one per member:

- `SolanaJsonRpcWebsocket.run`'s `while (!closed())` forced always-true (`RemoveConditionalMutator`
  `EQUAL_IF`) — **cause:liveness.** The loop that never exits: once close()'s
  signal is consumed the mutated path owns no finite completion, and a
  fixture timeout is a safety exit rather than a demotion.
- `SolanaJsonRpcWebsocket.run`'s `checkCycle(sleepNanos)` call removal (`VoidMethodCallMutator`) —
  **cause:liveness.** The loop body goes empty, leaving a busy spin on
  `!closed()` that never parks; the covering test's eventual close races it.
- `SolanaJsonRpcWebsocket.close`'s `msgId.set(Long.MIN_VALUE)` removal (`VoidMethodCallMutator`) —
  **cause:liveness.** The closed marker never sets, so every loop exit that
  waits on `closed()` vanishes — the never-exits family, transport side.
- `SolanaJsonRpcWebsocket.closed`'s `msgId.get() < 0` forced-false (`RemoveConditionalMutator`
  `ORDER_ELSE`) — **cause:liveness.** The accessor-side twin: `closed()` can
  never report true, so the same exit disappears.
- `SolanaJsonRpcWebsocket.close`'s settled-guard forced true (`RemoveConditionalMutator` `EQUAL_IF`)
  — **cause:liveness.** close() joins a build future that is null (an instant
  NPE, which kills when sampled first) or still pending — parking ahead of
  the very cancels that would have settled it. A self-inflicted wedge with no
  path-owned exit.
- `SolanaJsonRpcWebsocket.lambda$connect$2` — the deferred completion handler's `ex == null` forced-else
  and its immediate-branch twin `SolanaJsonRpcWebsocket.lambda$connect$5` —
  **cause:liveness.** `completeExceptionally(null)` NPEs inside the callback
  and strands the attempt. The immediate case is killed outright by
  `anImmediateAttemptIsSettledWhenConnectReturns` whenever it is sampled; the
  deferred flavour is reachable only through a real delay, so it stays.
- `SolanaJsonRpcWebsocket.connect` — the immediate branch's
  `built.whenComplete(...)` reduced to bare `built` (`NakedReceiverMutator`) — **cause:liveness.** The bridge to
  `connected` never attaches, so the returned copy never completes. Same
  settlement pin, same racer caveat.
Under repair — three members TERMINATE, so none of them is a liveness case, yet
each still reads TIMED_OUT on a loaded run. They are tagged `cause:harness`,
which does not certify, and that is the point: the suite should not certify
while a detection nobody can audit stands in for a disposition that exists or
is owed. Each one's disposition:

- `SolanaJsonRpcWebsocket.checkCycle`'s `finally` `unlock()` removal
  (`VoidMethodCallMutator`) is killed by `WsLockReleaseContractTests`, which
  asserts the release contract on the test thread. Its old TIMED_OUT reading was
  a later locked entry parking on the leak. The contract test observes the leak
  directly, so the kill no longer depends on which test the minion samples — but
  the parked-entry flavour still wins some runs, and the row stays until a
  measured run stops producing it.
- `SolanaJsonRpcWebsocket.checkCycle`'s await guard forced false
  (`RemoveConditionalMutator` `EQUAL_ELSE`) hot-spins between passes but
  terminates: the pass below is clock-gated rather than park-gated, since the
  await's return is deliberately ignored. It is a stable SURVIVED equivalence
  belongs in the accepted baseline, and the pending refresh is where it lands — a
  park-duration assertion would be unsound against the spurious wakeups the code
  explicitly tolerates, so the baseline is the only honest disposition.
- `SolanaJsonRpcWebsocket.ensureCapacity`'s growth arithmetic (`MathMutator`)
  also terminates: the clamp still guarantees `minCapacity`, so reassembly
  finishes. What the mutation removes is amortisation, degrading a fragmented
  message to a reallocation per fragment — the `# capacity math` equivalence,
  whose slow flavour the fuzz replay surfaces under load. It carries an accepted
  row already; the refresh re-anchors its drifted line tag.

Retired 2026-08-10: `lambda$connect$1,NakedReceiverMutator` matched no mutant
after the connect restructuring — that lambda is now a one-expression
`_ -> deferredBuild(...)` with no call whose erased return type matches its
receiver, so NakedReceiver has nothing to mutate. Its structural cause, the
dropped `.whenComplete(...)` keeping a bare build future, was inherited by
the scheduler-branch runnable and by `connect` itself, both listed above.
