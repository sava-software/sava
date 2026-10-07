# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,mutator,STATUS` — line numbers are metadata, carried as
a trailing `# line N` tag every refresh rewrites, so editing above a mutated
method churns nothing. Full policy — the three legal outcomes for a new
survivor, determinism requirements, targeting rules — lives in sava-build's
`HARDENING.md`.

This file holds the arguments in force, one section per suite, and the debt
deliberately left. What a run counted, added, pruned or killed is that run's output
and git's history; `HARDENING_NOTES.md` holds per-suite scope decisions and selected
per-package history, not every triage record.

- **The build reads this file.** Verify and Debt warn when a family label on a
  baseline row has no literal `# <label>` mention here (`# untriaged` is exempt).
  Every `ws-timeouts.csv` member's simple class name and method name must appear as
  whole words inside one heading-delimited section: verify warns on a member that
  does not, and certification treats it as an undocumented, non-certifying row.
  Renaming a label, or moving a member's class or method out of the section that
  holds the other, breaks these checks; the timed-out section's heading text matters
  only because the `ws-timeouts.csv` header comment points at it.
- **Never run a `pitest<Suite>BaselineUpdate` task just to make the build pass:**
  kill the mutant, refactor it out of existence, or record its reason here. A failure
  classifies each new row (`newly covered` vs shares an accepted key vs unexplained).
- **Arguments name methods and constructs, not line numbers.** Prose anchors are not
  machine-checked and rot silently on the first refactor. The authoritative anchor is
  each row's `# line` tag in the CSV, which every refresh rewrites for the rows the
  report gates. A retained row (see the debt section) keeps the tag of its last gated
  observation: `BaselineRetag` refreshes only rows a fresh report matches, and a
  selective prune performs no incidental retag, so its anchor can lag source drift
  until the row leaves. Cite a line here only where it is the historical record of a
  past state.
  A key unkilled at a line no row's `# line` tag names draws the line-drift advisory:
  the code an acceptance argues about moved, or a new mutant sits under an old
  acceptance (the line-less key's one documented blind spot). Re-read the argument,
  then let the next refresh rewrite the tag.
- **Identical rows are sibling mutants — never dedupe these files.** One compound
  condition emits a mutant per operand or branch direction at the same
  `class,method,mutator,STATUS` key, and one `MathMutator` key can cover different
  operations in one method (`ensureCapacity`'s growth computation is a shift *and* an
  add), the `# line` tags telling the copies apart. The comparison is a multiset.
  When one sibling survives and another is killed, the verify names the killing test:
  the survivor is that test's opposite branch, and it is triaged as its own mutant
  rather than assumed covered.
- **Toolchain absence is not a kill.** A row leaves a baseline only when the same
  licensed mutant is observed and killed (`AGENTS.md`, "ArcMutate licence").
- **Baseline shrinkage requires row-specific evidence; growth requires a reason here.**
  Writer options are defined by the installed `hardeningHelp`.
- **Never accept a `NO_COVERAGE` mutant as equivalent.** The `NO_COVERAGE` rows below
  sit in the debt section, never in a family.

Each family states its members, the reason, the independent oracle, and what
invalidates it. *Derived* marks an invalidation that is the negation of the reason's
own premise; *owed* marks an element not yet argued from the source.

## responses suite

Targets `software.sava.rpc.json.http.response.*`. The arguments below cover the
live families; the `JsonUtil.parseEncodedData` pending-prune rows are debt (see
"Retained rows and the writer gap").

- **`# int clamp boundary`** — `RpcCustomError.parseError` (both overloads)
  `ConditionalsBoundaryMutator`, two siblings per overload, on the long→int clamp.
  - Reason: at the exact `Integer.MIN_VALUE` / `MAX_VALUE` boundaries the mutant
    returns `Unknown` directly, and the original reaches the switch `default`, which
    is also `Unknown` — with identical iterator handling (`ji.skip()` on both routes).
    No defined custom-error code sits at an int boundary, so the routes cannot diverge.
  - Oracle: the killable near-misses — codes aliasing real ones under `(int)`
    truncation, `code ± (1L << 32)` — are pinned by `ParseCustomErrorCodeTests`.
  - Invalidated if (derived): a defined custom-error code sits at an int boundary, or
    the two routes stop handling the iterator identically.
- **`# allocation routing`** — `Lamports.amount` `ConditionalsBoundaryMutator` and
  `RemoveConditionalMutator_ORDER_IF`, boundary/forced-true on `lamports < 0`.
  - Reason: both branches build the same `BigInteger` for non-negative longs,
    including zero. Negative inputs already take the unsigned-widening branch, so
    forcing that branch true leaves their result unchanged too. The guard saves
    allocation for non-negative values — `valueOf` is cheaper than widening the bits.
  - Oracle: sava-core's
    `ByteUtilTests.toUnsignedBigIntegerAgreesWithValueOfWhereCallersBranch` sweeps
    `valueOf` against the widening over seeded non-negative values plus boundaries on
    every build.
  - Harness capability lacking: an allocation bound. See the decimal suite notes in
    sava-core for why the allocation-bound technique that would kill these was tried
    and reverted.
  - Invalidated if (derived): the two branches stop producing equal values, or
    allocation becomes a contract.
  - The `ORDER_IF` row is absent from the licensed population and has not been
    observed killed; its argument remains valid and the row stays in this family,
    since absence under the licensed toolchain is not a kill.
- **`# logging only`** — `JsonUtil.parseEncodedData` `VoidMethodCallMutator`.
  - Reason: removed `System.Logger::log` call on the unsupported-encoding fallback.
  - Oracle: owed. Invalidated if: owed. This argument does not yet say why the log is
    not the only record of what the provider sent — the property that withdrew the
    client log-and-rethrow acceptance (see "History notes (client)").
- **`# capacity math`** — `JsonUtil.toJsonIntArray` `MathMutator`, two siblings on
  `(data.length << 2) + 2`.
  - Reason: `StringBuilder` sizing only; the builder grows as needed.
  - Oracle: owed.
  - Invalidated if (derived): the value is used for anything but initial capacity.
- **`# best-effort guard`** — `JsonRpcException.envelopeRequestId`
  `RemoveConditionalMutator_EQUAL_ELSE` and `RemoveConditionalMutator_ORDER_ELSE`,
  on guards that sit inside the reader's best-effort `catch (RuntimeException)`,
  which exists so that no unreadable id can cost the caller the error object.
  - Property: an id the reader cannot carry reads as empty and never displaces the
    error.
  - Oracle: the reader's own contract (its javadoc and `CONVENTIONS.md`), pinned by
    `JsonRpcExceptionTests.theEnvelopeReaderCarriesOnlyWhatSavaMints` and the HTTP and
    websocket envelope tests.
  - Reason: (1) `skipUntil("id") == null` forced false: a missing `id` then runs
    `whatIsNext()` at the end of the object, which either throws into the catch or is
    not a `NUMBER`, and either way the result is empty. (2) `c > '9'` forced false in
    the digit check: any character above `'9'` also fails `Long.parseLong`, whose
    exception the same catch swallows, so the guard cannot change the result. Both
    guards are the non-exceptional fast path in front of that catch; the kills are on
    the other direction of each (forced true rejects every id) and on the `'0'`/`'9'`
    boundaries (an id carrying both digits).
  - Why not refactor: removing the guards to make the mutants killable would turn
    every ordinary missing or null `id` into an exception used as control flow;
    narrowing the catch would trade robustness for a killable mutant.
  - Invalidated if (derived): the `catch (RuntimeException)` is narrowed or removed.

### History notes (responses)

Kept apart from the arguments above; none of it is live evidence.

- A reviewer measured the same two `# best-effort guard` survivors independently
  before that acceptance was written (2026-09-23).

## client suite

Targets `software.sava.rpc.json.http.client.*`. Harness capability, for anyone
re-reading a "needs a harness we don't have" acceptance: `JsonHttpClientTransportTests`
runs an echo server answering with the method and path it saw, and `StubHttpResponse`
constructs any status, a real `HttpRequest`, a predecessor response and an
`SSLSession`, driving the parser controllers without a server. Read every "the stub
returns null anyway" acceptance against that capability.

- **`# dead null arm`** — `BaseJsonResponseController.applyResponse`
  `RemoveConditionalMutator_EQUAL_ELSE`: the `body == null` guard in the log argument
  of `applyResponse`'s parse-failure catch, forced false.
  - Reason: equivalent *in context*, because the base `checkResponse` returns before
    the parser runs whenever the body is null, so the catch block never sees one —
    the guard's null arm is defensive dead code there, unreachable by construction
    rather than untested.
  - Oracle: through the base `checkResponse`,
    `GenericJsonResponseParserTests.nullBodyUnderASuccessStatusReturnsNull` and
    `rejectionWithoutABodyReportsTheStatus` pin that a null body returns null or
    throws before the parser runs. Owed for the JSON-RPC override in
    `BaseJsonRpcResponseParser`, which parses the body inside its own `checkResponse`,
    outside the catch, and which no test drives with a null body.
  - Invalidated if (derived): `checkResponse` stops ending a null-body exchange before
    the parse-failure catch, or `applyResponse` is reached by a path that skips
    `checkResponse`.
- **`# dead pattern arm`** — `JsonHttpClient.readInputStream`
  `RemoveConditionalMutator_EQUAL_ELSE`.
  - Reason: `readInputStream` is private and is called only from the
    `body instanceof InputStream inputStream` pattern arm. That pattern binding is
    necessarily non-null, so forcing the method's defensive `inputStream == null`
    check false cannot change any reachable call. The opposite mutation is killed by
    the non-empty stream contract.
  - Oracle: the language guarantee that a pattern binding is non-null.
  - Invalidated if (derived): `readInputStream` gains a second caller or stops being
    private.
- **`# capacity hint only`** — `ProgramAccountsRequestRecord.toJson` `MathMutator`
  and `RemoveConditionalMutator_EQUAL_IF`.
  - Reason: both change only the initial `StringBuilder` capacity derived from the
    filter count. Request content is appended independently by `appendFilters`. The
    sibling arithmetic mutation that changes appended content is killed.
  - Oracle: null, empty, and populated filter requests produce the same bytes under
    these mutants.
  - Invalidated if (derived): the capacity value feeds anything other than the
    `StringBuilder` constructor.
- **`# impossible zero mark`** — `JsonRpcValueResponseParser$Parser.parse`
  `ConditionalsBoundaryMutator`, on the `valueMark < 0` boundary.
  - Reason: its only distinguishing value is zero, but a mark captured after the
    enclosing object's `value` member name can never be zero: the two reachable
    domains are the `-1` absent-value sentinel and a positive cursor position.
  - Oracle: owed.
  - Invalidated if (derived): a mark source that can be zero.
- **`# eager deferred convergence`** — `JsonRpcValueResponseParser$Parser.test`
  `RemoveConditionalMutator_EQUAL_IF`: always records and skips a value even when its
  context was already parsed.
  - Reason: the final parse resets to the same value bytes and supplies the same
    context, so eager and deferred paths invoke the value parser once with the same
    inputs and return the same result.
  - Oracle: owed.
  - Invalidated if (derived): the value parser gains side effects, or the context
    differs between the eager and deferred paths.
- **`# request debug only`** — `JsonHttpClient.newPostRequest`
  `VoidMethodCallMutator`.
  - Reason: removing the DEBUG body log does not change the URI, timeout, method,
    headers, body publisher, or returned request. This is an accepted non-contract
    diagnostic, not a claim that JUL output is impossible to observe.
  - Oracle: owed.
  - Invalidated if (derived): the request-body log becomes the only record of
    something a caller needs, or the owner makes it a contract.

### History notes (client)

Kept apart from the arguments above; none of it is live evidence.

- The parse-failure tails of `applyResponse` and
  `BaseJsonRpcResponseParser.parseRpcException` log and rethrow. They were first read
  as logging-only, then killed through the JUL backend (`TestLogs`) once a second read
  noticed the rethrown exception is the JSON parser's own, so the logged status and
  body are the *only* record of what the provider sent. `# dead null arm` is what
  remains of that family.
- Two acceptances were withdrawn because the unreachability was the fixture's, not
  the code's: the `checkResponse` status-range boundaries (a stub constructs any
  status) and the `ReadHttpResponse` pass-through accessors (a stub returning the
  mutator's own replacement value withdraws that mutant before the tests are
  consulted).

## ws suite

Targets `software.sava.rpc.json.http.ws.*`. Every member below is a
`SolanaJsonRpcWebsocket` method unless another class is named, and every row is
`SURVIVED`. Oracles are owed for every ws family whose bullet does not name one;
invalidation conditions are derived from each reason's premise.

### Hash and sentinel domains

- **`# hash distribution only`** — `RootSubscription.hashCode`
  `PrimitiveReturnsMutator`. Reason: replacing it with a constant preserves the
  `equals`/hash contract and changes only bucket distribution. Oracle: the
  `Object.hashCode` contract. Invalidated if (derived): a caller depends on hash
  distribution rather than the contract.
- **`# positive sentinel gap`** — `closed` `ConditionalsBoundaryMutator`. Reason:
  websocket message ids start positive and close jumps directly to `Long.MIN_VALUE`;
  zero, the sole value separating `< 0` from `<= 0`, is unreachable. Invalidated if
  (derived): message ids can reach zero.

### Capacity and buffer routing

- **`# equal-capacity copy`** — `ensureCapacity` `ConditionalsBoundaryMutator`.
  Reason: exact capacity enters the growth branch; the clamp grows a sub-maximum
  buffer and performs a same-sized copy only at `maxMessageLength`, without changing
  bytes or parsing. Invalidated if (derived): the growth branch changes bytes, or the
  clamp no longer bounds the copy.
- **`# capacity math`** — `ensureCapacity` `MathMutator`, two siblings. Reason: they
  alter only the growth hint before `Math.clamp`, which still allocates at least the
  required capacity. A third `ensureCapacity` `MathMutator` row carries
  `# retired implementation retained` (debt section). Invalidated if (derived): the
  hint can drive the clamped size below the required capacity.
- **`# zero-offset route convergence`** — `onText` `ConditionalsBoundaryMutator`.
  Reason: it sends an unfragmented message through the assembled-buffer route, which
  parses the same characters. Invalidated if (derived): the two routes parse
  different characters.
- **`# equivalent buffer copy`** — `onText` `RemoveConditionalMutator_EQUAL_ELSE`,
  four siblings. Reason: the mutants choose `arraycopy`, `CharBuffer.get`, or a
  wrapped buffer for the same remaining characters; only the callback-owned buffer
  cursor and allocation route differ. Invalidated if (derived): a caller reads the
  callback-owned buffer cursor after `onText` returns.

### Literal and convergent registry returns

- **`# literal return equivalent`** — `BooleanTrueReturnValsMutator` and
  `BooleanFalseReturnValsMutator` on `queueSubscription`, `queueUnsubscribe`,
  `rootSubscribe`, `rootUnsubscribe`, `slotSubscribe`, `slotUnsubscribe`, `subscribe`
  and `unsubscribe`. Reason: each replacement equals the literal at that bytecode
  exit. Oracle: owed. Invalidated if (derived): the exit stops returning a
  literal.
- **`# same-map re-put`** — `queueSubscription` `RemoveConditionalMutator_EQUAL_IF`,
  two siblings. Reason: it stores the map already held at the same key. Invalidated
  if (derived): the put can store a different map.
- **`# redundant outer duplicate guard`** — `RemoveConditionalMutator_EQUAL_ELSE` on
  `accountSubscribe`, `logsSubscribe`, `programSubscribe` and `signatureSubscribe`.
  Reason: a duplicate reaches the lock-held `queueSubscription` check, which returns
  the same result before minting an id. Invalidated if (derived): the lock-held check
  is removed or answers differently from the outer guard.
- **`# compute-if-absent convergence`** — `subscribe`
  `RemoveConditionalMutator_EQUAL_ELSE`. Reason: it returns the already-present
  generic method map. Invalidated if (derived): the map can be absent on that path.

### Build and reconnect ownership

- **`# settled prior build`** — `connect` `RemoveConditionalMutator_EQUAL_ELSE`.
  Reason: follows from the single-flight bridge: a successor cannot reach cleanup
  until the prior build is done. Invalidated if (derived): builds stop being
  single-flight.
- **`# adopted build identity`** — `connect` `RemoveConditionalMutator_EQUAL_ELSE`,
  two siblings. Reason: a successful build and the socket delivered to its attempt
  listener are the same object. Invalidated if (derived): the listener can receive a
  different socket.
- **`# current socket identity`** — `close` `RemoveConditionalMutator_EQUAL_ELSE`.
  Reason: the close-side twin of the above: an adopted completed build is the current
  connection and must remain on the polite close path. Invalidated if (derived): an
  adopted build can differ from the current connection.
- **`# zero-delay convergence`** — `connect` `ConditionalsBoundaryMutator`. Reason:
  it differs only at the exact throttle edge, where both routes connect immediately.
  Invalidated if (derived): the routes diverge at a zero delay.
- **`# ignored null completion`** — `lambda$ownBuild$0`
  `RemoveConditionalMutator_EQUAL_IF`. Reason: it can fault only an ignored dependent
  stage, not the original build or public bridge. Invalidated if (derived): that
  dependent stage gains an observer.

### Empty scans, deadlines, and wire order

- **`# empty-scan fast path`** — `escalateUnanswered`
  `RemoveConditionalMutator_EQUAL_ELSE` and `handleActivePendingSubscriptions`
  `RemoveConditionalMutator_EQUAL_IF`. Reason: the mutant enters an iteration over an
  already-empty map and still finds no work. Invalidated if (derived): the iteration
  does work on an empty map.
- **`# saturated deadline fringe`** — `escalateUnanswered`
  `ConditionalsBoundaryMutator`. Reason: it moves an unreachable deadline a few
  milliseconds below `Long.MAX_VALUE`; no representable age from the monotonic clock
  reaches either value. Invalidated if (derived): the deadline becomes reachable.
- **`# saturated-add equality`** — `onWholeMessage` `ConditionalsBoundaryMutator`.
  Reason: it chooses between two expressions that both equal `Long.MAX_VALUE` at the
  boundary. Invalidated if (derived): the two expressions differ at the boundary.
- **`# strict wire ordinal`** — `ConditionalsBoundaryMutator` on
  `lambda$handleActivePendingSubscriptions$0`, `lambda$onWholeMessage$0` and
  `onWholeMessage` (two siblings). Reason: distinct lock-held transmissions receive
  distinct pre-incremented ordinals. Invalidated if (derived): two transmissions can
  share an ordinal.
- **`# positive request-id domain`** — `onWholeMessage` `ConditionalsBoundaryMutator`
  and `RemoveConditionalMutator_ORDER_IF`. Reason: client ids begin at 2; zero and
  negative ids never occupy correlation maps. Invalidated if (derived): a zero or
  negative id can enter a correlation map.

### Lock-owned registry representation

- **`# null-channel type invariant`** — `releaseChannelSlot`
  `RemoveConditionalMutator_EQUAL_IF`. Reason: only `GenericSubscription` has no
  channel. Invalidated if (derived): another subscription type can lack a channel.
- **`# identity-owned registry slot`** — `lambda$releaseChannelSlot$0` and
  `releaseChannelSlot` (two siblings), `RemoveConditionalMutator_EQUAL_IF`; and
  **`# subId-owner invariant`** — `queueUnsubscribe`
  `RemoveConditionalMutator_EQUAL_IF`. Reason: these removals are reached with the
  same subscription that owns the slot or server id. Invalidated if (derived): a
  removal can be reached with a subscription that does not own the slot or id.
- **`# prechecked in-flight gate`** — `sendUnSubscriptionLockHeld`
  `RemoveConditionalMutator_EQUAL_ELSE`. Reason: the helper's lock-held callers have
  already proved the per-id gate absent. Invalidated if (derived): a caller reaches
  the helper without that precheck.
- **`# disjoint registry phases`** — `onWholeMessage`
  `RemoveConditionalMutator_EQUAL_ELSE`. Reason: a subscription leaves pending before
  installation and leaves installed before requeue. Invalidated if (derived): a
  subscription can sit in both registries at once.
- **`# pruned empty registry`** — `onWholeMessage`
  `RemoveConditionalMutator_EQUAL_ELSE`. Reason: an empty generic namespace is
  removed from the outer map. Invalidated if (derived): an empty namespace can remain.

### Correlation and wake hints

- **`# correlation co-registration`** — `onWholeMessage`
  `RemoveConditionalMutator_EQUAL_ELSE`, two siblings. Reason: they clear correlation
  structures in the same locked response transition, so either surviving operand
  proves the same correlated result. Invalidated if (derived): the structures can be
  cleared in separate transitions.
- **`# connection-owned registry`** — `onWholeMessage`
  `RemoveConditionalMutator_EQUAL_IF`. Reason: an acknowledgement map belongs to its
  `Connection`; a displaced connection cannot share its successor's entry.
  Invalidated if (derived): connections can share an acknowledgement map.
- **`# absent-map removal`** — `onWholeMessage` `RemoveConditionalMutator_EQUAL_IF`.
  Reason: it performs only a no-op removal when the earlier lookup proved the key
  absent. Invalidated if (derived): the key can appear between lookup and removal.
- **`# pending-work wake hint`** — `onWholeMessage`
  `RemoveConditionalMutator_EQUAL_IF`, three siblings. Reason: they add a condition
  signal when no matching work remains; no state changes. Oracle: condition wakeups
  are explicitly allowed to be spurious (`Condition` contract). Invalidated if
  (derived): a waiter treats a wakeup as proof of work.

### Parser rescans

- **`# unique-member rescan`** — `RemoveConditionalMutator_EQUAL_IF` on
  `onWholeMessage` (two siblings), `publish` (two siblings), `publishGeneric` and
  `skipToParams`. Reason: the mutant resets and finds the same unique JSON-RPC
  `params`, `value`, or `subscription` member; duplicate member-name resolution is
  outside the protocol contract. Invalidated if (derived): duplicate member names
  become part of the contract.
- **`# fast-forward funnel`** — `NakedReceiverMutator` on `onWholeMessage` and
  `publish` (two siblings). Reason: it drops an initial iterator fast-forward, after
  which the existing mark/reset fallback reaches the same member and dispatches the
  same notification. Invalidated if (derived): the mark/reset fallback is removed.

### Ping and private-tail cleanup

- **`# retired-state write`** and **`# ping-state invariant`** — `recordFailedPing`
  `RemoveConditionalMutator_EQUAL_IF`, two siblings, one per label.
  `# retired-state write`: only an extra ping-state write to a displaced `Connection`
  plus a condition wake; no live state or callback reads it. `# ping-state invariant`:
  follows from the same lock-held transition publishing the failure while changing
  `ACTIVE` to `PING_FAILED`. Invalidated if (derived): live state or a callback reads
  a displaced connection's ping state, or the failure is published outside that
  transition.
- **`# private-tail normalization`** — `lambda$sendSubscription$1`
  `NullReturnValsMutator`. Reason: it can make only a private discarded completion
  tail exceptional after cleanup has committed; the next enqueue normalizes that prior
  exception. Invalidated if (derived): the tail is exposed or no longer normalized.

### Timed-out mutants (audited set)

`TIMED_OUT` is detected, never accepted, but a watchdog cannot prove that a
covering assertion observed the defect. `ws-timeouts.csv` therefore holds a
line-less audited key set, and verification warns on a timeout outside it. Every
member has `cause:liveness`; there are no resource or harness holding rows.

Shared cause, argued once: each member makes the `SolanaJsonRpcWebsocket` maintenance
loop lose an exit or its per-cycle work, so the mutated path has no path-owned finite
completion guarantee. The members are `SolanaJsonRpcWebsocket` `runLoop`
`RemoveConditionalMutator_EQUAL_ELSE` (two sibling mutants, one key row),
`runLoop` `VoidMethodCallMutator`, and `closed` `RemoveConditionalMutator_ORDER_ELSE`.

- **`runLoop` `EQUAL_ELSE` (two sibling mutants).** One forces the `closed()` exit
  false and the other forces the interruption exit false. In each mutated path the
  corresponding terminal event can no longer end the loop, and the path owns no
  replacement finite completion guarantee. Timeout membership is key-level, so one
  CSV row honestly classifies both siblings.
- **`runLoop` `VoidMethodCallMutator`.** Removing `checkCycle` leaves the unbounded
  loop doing no maintenance work. A covering path waiting for the cycle's state
  transition has no path-owned completion. An external close or interrupt is only
  the fixture's emergency exit where the fixture has one (the two `@Timeout` tests
  below); the executor-task tests have none, and PIT's watchdog is their only bound.
- **`closed` `ORDER_ELSE`.** Forcing `msgId < 0` false prevents the close sentinel
  from ever ending the maintenance loop. `checkLoopReturnsImmediatelyOnceClosed`
  asserts `ws.closed()` synchronously right after `ws.close()`, a synchronous reader
  of exactly the state this mutant breaks: it kills the mutant whenever it is the
  covering test that runs, and a timeout is recorded only when a test that enters the
  loop covers the mutant first. It was killed in the fresh 2026-08-11 full run, so
  its coordinate is still in the population: that is the *quiet* case, not a stale
  row, and its quiet-retention clock is running. Whether it should leave the audited
  set on that clock or be argued as a deterministic kill is the owner's call.

Retention: a coordinate still in the population whose timeout went quiet stays in
the audited set until the tool's 3+ distinct fresh full-run quiet notice over
identical evidence inputs and the solo/gate confirmation, after which its membership
line is removed by hand — since sava-build 21.5.37 every timeout membership line is
hand-maintained and no writer retires one. Only a coordinate that leaves the
population altogether is removed after a single fresh history-free run with valid
committed provenance. Former members left the set when their mutation sites were
removed or given a deterministic killed or accepted disposition; none remains under a
`cause:harness` label.

Fixture bounds, read from the test source rather than from a PIT coverage reading.
In `SolanaJsonRpcWebsocketLifecycleTests`, `run()` drives `runLoop` inline on the
test thread, either through `RecordingExecutor`'s captured task
(`checkLoopExitsOnInterruptAndCloses`, `checkLoopReturnsImmediatelyOnceClosed`,
`checkLoopClosesAndLogsAnUnhandledException`; no fixture bound) or through a direct
`ws.run()` call (`aCheckLoopFailureReachesOnErrorBeforeClosing`,
`aThrowingOnErrorHandlerDoesNotPreventTheClose`, each under a 30 s JUnit `@Timeout`,
which interrupts the test thread). A websocket built through the public builder, as
in `SolanaRpcWebsocketTests`, runs the loop on its own single-thread executor, which
`close()` shuts down without interrupting. PIT's effective watchdog is the covering
test's duration × `timeoutFactor` + `timeoutConst`; `sava-rpc/build.gradle.kts` sets
2.0 and 1500 ms, and its comment puts this module's slowest test at a quarter of a
second. The watchdog therefore fires long before 30 s: the JUnit bound is an
emergency ceiling that cannot fail first and contributes no cause evidence.

Owed (`HARDENING.md`, "`TIMED_OUT` is detected, but does not diagnose its cause"),
for the two `runLoop` members only: the statement of why no synchronous reader of the
mutated state (the closed and interruption exits, the cycle's work) serves as a
deterministic oracle.

## encoding suite

Targets `software.sava.rpc.json.*` and `software.sava.rpc.json.http.request.*`,
subtracting the client, responses and ws packages. It has no accepted rows and no
audited timeouts. `RpcEncoding`'s missing `jsonParsed` constant is a deliberate API
invariant (`AGENTS.md`), not a gap to triage. `PrivateKeyEncodingTests` pins the
key-import field-order, unknown-field and missing-field contracts; the declaration
documents the retained duplicate-encoding behaviour (a later encoding does not
reinterpret an already imported secret).

## Retained rows and the writer gap

These rows stay deliberately. Their labels and status fields record historical
observations, but **each row still contributes active baseline matching capacity**
and can accept a later mutant with the same class, method, mutator, and status;
historical labels do not deactivate matching. In the lists below, `EQUAL_ELSE`,
`EQUAL_IF`, `ORDER_IF` and `ORDER_ELSE` abbreviate `RemoveConditionalMutator_*`.

- **`# killed retained`** — a fresh licensed run observed the same mutant killed.
- **`# retired implementation retained`** — the mutation site or its containing
  helper no longer exists.
- **`# unlicensed-only retained`** — rows the licensed ArcMutate population does not
  observe. Absence under that toolchain is not a kill, so each stays until the same
  licensed mutant is observed and killed.
- **`# killed guard pending prune`**, **`# removed fallback pending prune`**,
  **`# killed reset pending prune`** — responses rows whose former equivalence
  arguments no longer hold (below).

Shared rationale, argued per key. A reviewed subset can be retired by key: the named
prune writer takes `-PpruneBaselineKeys` after two fresh history-free previews
(`HARDENING.md`, "Retiring a reviewed subset"). Selecting a key selects every row at
that key. A key holding a matched live-family row is refused by the writer; a key
holding a protected `# unlicensed-only retained` sibling would be accepted, since
those rows are unmatched candidates too, and is omitted only by this file's rule that
toolchain absence is not a kill. These keys are blocked, all `SolanaJsonRpcWebsocket`
in ws:

- `ensureCapacity,MathMutator,SURVIVED` — a `# retired implementation retained` row
  beside the live `# capacity math` siblings (refused by the writer).
- `handlePendingSubscriptions,EQUAL_IF,SURVIVED` — a
  `# retired implementation retained` row beside `# unlicensed-only retained`
  siblings (omitted by rule).
- `programSubscribe,EQUAL_ELSE,SURVIVED` — a `# killed retained` row beside the live
  `# redundant outer duplicate guard` row (refused by the writer).

Every other key holding a killed, retired or pending-prune row holds only such rows, so
the CSVs do not block it; keys holding only unlicensed-only rows stay by the rule above.
Whether
each of its rows is an eligible candidate in a given run (not matched,
timeout-protected, pending a status flip, or flip-insured) is a run observation that
only the previews confirm; choosing the reviewed keys is the owner's step. What the
selective prune does not do is retag ungated rows: it performs no incidental retag,
and `BaselineRetag` refreshes only rows a fresh report matches, so a retained row's
`# line` tag stays at its last gated observation until the row leaves. Hand-editing
baseline record structure is not a sanctioned substitute for either writer. Several
retained rows are `NO_COVERAGE`; they are debt, never equivalences. This is open debt
in client, ws and responses; it closes key by key, and a blocked key closes only when
its live or protected sibling no longer needs that capacity or the owner retires the
unlicensed population.

### client

- `# killed retained`: `BaseSolanaJsonRpcClient.joinKeys` `EQUAL_ELSE` (the other
  direction is killed by direct helper tests distinguishing null, empty, and
  populated collections); `JsonHttpClient.gzipBufferSize` `VoidMethodCallMutator`
  (the malformed provider-controlled `Content-Length` diagnostic, asserted through
  `TestLogs`); `JsonHttpClient.wrapResponseParser` `NullReturnValsMutator` and
  `EQUAL_ELSE`; `JsonRpcHttpClient.applyGenericResponseResult` `NullReturnValsMutator`
  (the generic-result parser factory); `JsonRpcValueResponseParser$Parser.parse`
  `ORDER_ELSE` (the absent-value cursor branch);
  `SolanaJsonRpcClient.getAppliedAccounts` `EQUAL_ELSE` (empty-account rejection);
  `SolanaJsonRpcClient.lambda$static$0` `EQUAL_ELSE` (the immutable empty leader
  schedule); `SolanaRpcClient.sendTransaction` `EQUAL_ELSE` and `EQUAL_IF` (both
  boolean encodings).
- `# unlicensed-only retained`: `BaseSolanaJsonRpcClient.joinKeys` `EQUAL_IF`, with no
  counterpart in the licensed population, reported unmatched on every run. It remains
  because absence under the licensed toolchain is not evidence that the old
  unlicensed mutant was killed. Its key differs from every killed row's key above, so
  it does not block their selective prune.

### ws

- `# killed retained` (`SolanaJsonRpcWebsocket` unless named): `accountSubscribe`,
  `checkCycle`, `close`, `lambda$queueUnsubscribe$0`,
  `lockAndHandlePendingSubscriptions`, `logsSubscribe`, `onClose`, `onError`,
  `onOpen`, `onWholeMessage`, `programSubscribe`, `publishGeneric`,
  `queueSubscription`, `rootSubscribe`, `sendPing`, `sendUnSubscription`,
  `signatureSubscribe`, `slotSubscribe`, `subscribe`, `subscribeToTokenAccounts`, and
  `SolanaRpcWebsocketBuilder.create`. The `queueSubscription` and `subscribe`
  `BooleanTrueReturnValsMutator` rows among them are `NO_COVERAGE`.
- `# retired implementation retained`: `ensureCapacity` (`MathMutator`),
  `handlePendingSubscriptions`, `lambda$connect$0`, `lambda$sendPing$0`,
  `onWholeMessage`, `removeDanglingSub`, and `unsubscribe`. The `onWholeMessage`,
  `removeDanglingSub` and `unsubscribe` rows include `NO_COVERAGE` rows.
- `# unlicensed-only retained` — itemised because `AGENTS.md` points here: the two
  `lambda$queueUnsubscribe$0 EQUAL_IF` siblings, `ensureCapacity ORDER_IF`,
  `onText ORDER_IF`, the `logsSubscribe` and `programSubscribe EQUAL_IF` rows, two
  `handlePendingSubscriptions EQUAL_IF` siblings, and one `onWholeMessage EQUAL_IF`
  sibling. Each remains until the same licensed mutant is observed and killed; the
  `handlePendingSubscriptions` siblings also block the retired row at their key.

### responses

Each is a `JsonUtil.parseEncodedData` row with no valid equivalence argument.

- `# killed guard pending prune` — `RemoveConditionalMutator_EQUAL_IF,SURVIVED`: the
  former argument predates the explicit missing-encoding exception introduced in
  `0d68f02`. Forcing the guard now rejects valid encoded arrays, which
  `ParseResponseFieldTests` decodes; the same tests pin the missing-encoding
  exception message.
- `# removed fallback pending prune` — `NullReturnValsMutator,NO_COVERAGE`: it
  belonged to a fallback return removed by `0d68f02`. The current throwing branch has
  no return to mutate. This is removed-source evidence, not identification of that
  historical sibling among today's return mutants, and it additionally requires
  reconciliation with the licensed-kill retirement rule.
- `# killed reset pending prune` — `NakedReceiverMutator,SURVIVED`: the former
  argument assumed the data element had already been decoded. Unknown encodings leave
  it unconsumed, so dropping `ji.reset(mark2)` breaks the cursor. Oracle:
  `unknownResponseEncodingsStillConsumeTheArrayAndReturnEmptyData` in
  `ParseResponseFieldTests` asserts both empty output and the next enclosing-object
  field.

## Mutator sets

Every suite in this module runs `STRONGER,EXPERIMENTAL_NAKED_RECEIVER`;
`EXPERIMENTAL_BIG_INTEGER` is off. The trials, recorded with their method in
`HARDENING_NOTES.md` ("Mutator-set trials"), measured for this module:

| Trial | Suite | Without | With | Fires |
|---|---|---|---|---|
| `EXPERIMENTAL_BIG_INTEGER`, 2026-07-21 | `client` | 498 | 498 | 0 |
| `EXPERIMENTAL_BIG_INTEGER`, 2026-07-21 | `responses` | 524 | 524 | 0 |
| `EXPERIMENTAL_NAKED_RECEIVER`, 2026-07-22 | `client` | 501 | 601 | 100 |
| `EXPERIMENTAL_NAKED_RECEIVER`, 2026-07-22 | `responses` | 524 | 607 | 83 |
| `EXPERIMENTAL_NAKED_RECEIVER`, 2026-07-22 | `ws` | 541 | 592 | 51 |

`encoding` was registered on 2026-08-04, after the trials, and carries
`EXPERIMENTAL_NAKED_RECEIVER` without a trial row of its own.
