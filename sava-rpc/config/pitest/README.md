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
- **`# allocation routing`** — `Lamports.amount` `ConditionalsBoundaryMutator`, the
  boundary on `lamports < 0`.
  - Reason: both branches build the same `BigInteger` for non-negative longs,
    including zero, so moving the boundary to admit zero into the unsigned-widening
    branch changes no result. The guard saves allocation for non-negative values —
    `valueOf` is cheaper than widening the bits. The forced-true sibling
    (`RemoveConditionalMutator_ORDER_IF`), which the same argument covers, is not in
    the licensed population and left the record on 2026-10-07 (History).
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

- `JsonUtil.toJsonIntArray`'s two `# capacity math` rows (`MathMutator` on the
  `StringBuilder` hint `(data.length << 2) + 2`) were accepted as sizing-only from the
  first baseline. The 2026-10-07 oracle pass found the hint's int arithmetic wraps at
  the top of the domain (an `OutOfMemoryError` from 2^29 - 1 elements and a
  `NegativeArraySizeException` from 2^29, measured off-heap in a standalone JVM), so
  the hint moved to a long computation clamped at the VM's array limit in a
  package-private helper, which `ToJsonIntArrayTests` pins at the boundary; both rows
  read `KILLED` and left through the prune. The output-identity sweep from the same
  pass (`outputMatchesAnIndependentRenderingAcrossCapacityShapes`) stays as the
  regression for the rendering itself.
- `JsonUtil.parseEncodedData` `VoidMethodCallMutator`, the warning on the
  unsupported-encoding fallback, was accepted as `# logging only` from the first
  baseline until 2026-10-07. The warning is the only record of what the provider sent,
  the same property that withdrew the client log-and-rethrow acceptance, so
  `ParseResponseFieldTests.unsupportedEncodedDataValueTypesAreLoggedSkippedAndReadAsEmpty`
  now asserts it through the JUL backend and the row left through the keyed prune. The
  three `parseEncodedData` rows whose equivalence arguments `0d68f02` had invalidated
  left in the same prune; `ParseResponseFieldTests` pins the missing-encoding
  exception and the cursor after an unknown encoding.

- `Lamports.amount` `RemoveConditionalMutator_ORDER_IF`, accepted in 2026-08 under
  `# allocation routing` from the stock engine's population, had not been generated by
  the licensed engine since the licence was adopted on 2026-08-04. It left through the
  unscoped prune on 2026-10-07, when the owner made the licensed toolchain the
  population of record; the family keeps its boundary member.

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
  - Reason: its only distinguishing value is zero, which the mark cannot take. It is
    the `-1` sentinel until a `value` member is seen, then the iterator's position just
    past that member's name-separator: an index into the body, which `checkResponse`
    parses from its first byte. A `value` member inside `result` sits behind at least
    `{"result":{"value":`, so the position is never zero.
  - Oracle: `ValueResponseRouteTests.aValueBeforeItsContextIsParsedFromWhereTheValueStarts`
    sweeps the shortest value-first envelopes, with whitespace and every value type,
    and finds the parser handed the iterator exactly where the JSON grammar puts the
    value.
  - Invalidated if (derived): the mark can fall on the first byte of the iterator's
    buffer, as it could if the parser were handed an iterator over a bare `value`.
- **`# eager deferred convergence`** — `JsonRpcValueResponseParser$Parser.test`
  `RemoveConditionalMutator_EQUAL_IF`: always records and skips a value even when its
  context was already parsed.
  - Reason: on a well-formed response the final parse resets to the same value bytes
    and supplies the same context, so eager and deferred paths invoke the value parser
    once with the same inputs and return the same result. The paths leave the cursor in
    different places, which nothing observes: `applyResponse` returns the parser's
    result and drops the iterator. They part on a result holding two `context` members
    around the value, or two `value` members (RFC 8259 section 4 leaves that
    unpredictable), on a truncated or malformed body (the eager path skips the value
    before parsing it, so the parser may run once or not at all before the failure), and
    on a value parser that reads past its own value (`applyGenericResponseValue` is
    protected, so a caller's parser can).
  - Oracle: `ValueResponseRouteTests.bothMemberOrdersParseTheValueOnceWithTheSameInputs`
    records each call's context, starting position and consumed text for every value
    type in both member orders, and `theClientsValueParsersAgreeAcrossMemberOrders`
    compares a sample of the client's own value parsers across the two orders.
  - Invalidated if (derived): the client's value parsers gain side effects or stop
    consuming exactly their value, the context differs between the eager and deferred
    paths, or something reads the iterator after `parseResponse` returns.
- **`# request debug only`** — `JsonHttpClient.newPostRequest`
  `VoidMethodCallMutator`.
  - Reason: removing the DEBUG body log does not change the URI, timeout, method,
    headers, body publisher, or returned request. This is an accepted non-contract
    diagnostic, not a claim that JUL output is impossible to observe. Unlike the
    parse-failure tails (history notes), the line is not the only record of what it
    holds: the body is the caller's own request, which the caller's `extendRequest`
    receives already set, the caller's `HttpClient` receives it built, and the server
    reads off the wire (`RpcRequestTests` asserts it per method).
  - Oracle: `JsonHttpClientRequestTests`'
    `postRequestCarriesItsBodyWhetherOrNotTheDebugLineRuns` builds the request with the
    line running and with it suppressed, and asserts that both carry the inputs and the
    body byte for byte, as does the builder `extendRequest` is handed.
  - Invalidated if (derived): the body stops reaching the caller's hooks or the wire
    verbatim, making the log the only record of something a caller needs, or the owner
    makes it a contract.

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

- The keyed prune of 2026-10-07 retired the `# killed retained` rows, each observed
  killed by a fresh licensed run (`EQUAL_*`/`ORDER_*` abbreviate
  `RemoveConditionalMutator_*`): `BaseSolanaJsonRpcClient.joinKeys` `EQUAL_ELSE`,
  `JsonHttpClient.gzipBufferSize` `VoidMethodCallMutator`,
  `JsonHttpClient.wrapResponseParser` `NullReturnValsMutator` and `EQUAL_ELSE`,
  `JsonRpcHttpClient.applyGenericResponseResult` `NullReturnValsMutator`,
  `JsonRpcValueResponseParser$Parser.parse` `ORDER_ELSE`,
  `SolanaJsonRpcClient.getAppliedAccounts` and `lambda$static$0` `EQUAL_ELSE`, and
  `SolanaRpcClient.sendTransaction` `EQUAL_ELSE` and `EQUAL_IF`.

- The licensed toolchain became the population of record on 2026-10-07 (owner
  decision, `AGENTS.md`). `BaseSolanaJsonRpcClient.joinKeys` `EQUAL_IF`, written from
  the stock engine's population in 2026-08 and never generated by the licensed engine,
  left through the unscoped prune after two matching previews.

## ws suite

Targets `software.sava.rpc.json.http.ws.*`. Every member below is a
`SolanaJsonRpcWebsocket` method unless another class is named, and every row is
`SURVIVED`. Every family names its oracle; an invalidation condition marked
"(derived)" negates its reason's premise, the others are concrete.

### Hash and sentinel domains

- **`# hash distribution only`** — `RootSubscription.hashCode`
  `PrimitiveReturnsMutator`. Reason: replacing it with a constant preserves the
  `equals`/hash contract and changes only bucket distribution. Oracle: the
  `Object.hashCode` contract. Invalidated if (derived): a caller depends on hash
  distribution rather than the contract.
- **`# positive sentinel gap`** — `closed` `ConditionalsBoundaryMutator`. Reason: the
  request-id counter is the closed flag, and `< 0` and `<= 0` differ only at zero. The
  counter starts at 1 and every request of every kind adds one, so while open it
  reaches zero only by wrapping past `Long.MAX_VALUE`; `close()` replaces it with
  `Long.MIN_VALUE`, and only the lock-held requests already past their closed check,
  and the listener thread's orphan cancellation (gated by connection resolution), step
  it up from there, each by one. Oracle: `MessageIdSentinelTests` reads the counter off
  the wire (ids 2, 3, … across subscribes and a cancellation) and holds `closed()` to
  the `SolanaRpcWebsocket` contract, "says only that close() was called"; a closed
  instance mints no id. Invalidated if: a path other than `close()` sets, decrements or
  resets the counter, or ids stop starting positive.

### Capacity and buffer routing

- **`# capacity math`** — `ensureCapacity` `MathMutator`, the `- 2` sibling on the
  growth hint `((long) conn.buffer.length << 1) + 2`. Reason: it moves the hint four
  chars below the doubling, and `Math.clamp` still allocates at least the required
  capacity and at most `maxMessageLength`, so growth stays geometric and the
  reassembled chars are the same. Oracle: `ReassemblyCapacityTests`'
  `fragmentedMessagesDeliverTheirExactPayloadAcrossTheCapacityShapes` and
  `aMessageAtACapBetweenTwoHintsDeliversItsExactPayload` compare delivered payloads with
  the generated ones across the lengths where the two hints clamp differently. The
  shift sibling (`>> 1`) is not a constant factor: it turns doubling into an exact fit
  that every later fragment copies again, the complexity change `HARDENING.md` keeps
  out of this family, so `crossingTheCapacityGrowsGeometricallyNotToAnExactFit` and
  `aCapWithinReachOfTheNextGrowthIsReachedInOneAllocation` kill it through the
  package-private `reassemblyCapacity()` seam (its row left through the prune on
  2026-10-07). Invalidated if (derived): the hint can drive the clamped size below the
  required capacity, or more than a constant factor below the doubling.
- **`# zero-offset route convergence`** — `onText` `ConditionalsBoundaryMutator`.
  Reason: it sends a whole frame through the assembled-buffer route, which parses the
  same characters. A frame with no backing array takes identical steps either way; an
  array-backed one is copied into the reassembly buffer, which may grow (the
  `reassemblyCapacity()` seam shows it), where the original parses it in place: a
  constant factor bounded by `maxMessageLength`, as for `# capacity math`, and not a
  property any contract states. A malformed frame's `JsonException` reports a
  position and a context excerpt from whichever buffer was parsed (`offset()`,
  `context()` and so `getMessage()`), which already differ between backings in the
  original; only the failure's `op()` is message-relative. Oracle:
  `TextFrameBackingParityTests` delivers one message from the backings a listener's
  `CharSequence` commonly takes (heap at zero and non-zero offsets,
  slice, read-only, byte view, wrapped `String`, `String`, `StringBuilder`), whole and
  in fragments, and compares the payload read back with the generated one, and a
  malformed frame's reported failure by its `op()`. Invalidated if: parsing whole
  frames in place becomes a contract (no copy, or a reassembly buffer whole frames
  never grow), or parse failures promise message-relative positions or excerpts.
- **`# equivalent buffer copy`** — `onText` `RemoveConditionalMutator_EQUAL_ELSE`,
  four siblings: the `instanceof CharBuffer` test and the three `hasArray()` tests.
  Reason: the mutants choose `arraycopy`, `CharBuffer.get`, or a wrapped buffer for
  the same remaining characters. What changes is the delivered buffer's position,
  which `CharBuffer.get` advances, and, for the two siblings that reach a whole
  array-backed frame, the allocation route of `# zero-offset route convergence`. The
  position belongs to the caller's buffer: the engine promises nothing about it, the
  original already advances it for every buffer without an array, and the listener
  contract ends the engine's access when its returned stage completes. Oracle:
  `TextFrameBackingParityTests` (above), and JDK 25's `WebSocketImpl.processText`,
  which reads nothing back from the buffer it delivered. Invalidated if: the engine
  promises to leave the delivered buffer's position alone, or keeps the buffer past
  its returned stage.

### Literal and convergent registry returns

- **`# literal return equivalent`** — `BooleanTrueReturnValsMutator` and
  `BooleanFalseReturnValsMutator` on `queueSubscription`, `queueUnsubscribe`,
  `rootSubscribe`, `rootUnsubscribe`, `slotSubscribe`, `slotUnsubscribe`, `subscribe`
  and `unsubscribe`, one row per exit. Reason: each exit returns a literal from inside
  the lifecycle lock's `try`/`finally`, which javac compiles to a store, the inlined
  unlock and a reload, so the mutator replaces the reloaded literal with itself.
  Oracle: the `SolanaRpcWebsocket` contract (once closed "subscriptions return false";
  a subscribe's `false` means already subscribed; an unsubscribe answers whether it
  removed a registration), asserted exit by exit by
  `SubscriptionResultContractTests`. Invalidated if (derived): an exit stops returning
  a literal.
- **`# same-map re-put`** — `queueSubscription` `RemoveConditionalMutator_EQUAL_IF`,
  two siblings, one per overload (String-keyed and `PublicKey`-keyed). Reason: forcing
  the `byCommitment == null` guard re-puts the commitment map the same lock hold just
  read; every mutator of the typed registries holds the lifecycle lock, so the key still
  maps to that map, and putting the identical value leaves the mapping as it was.
  Oracle: the `Map.put` contract; `HeldRegistryNamespaceTests`'
  `aSecondCommitmentJoinsTheHeldMapOfAStringKey` and
  `aSecondCommitmentJoinsTheHeldMapOfAPublicKey` sweep an absent, held, emptied and
  re-created key and assert what both routes keep: a second commitment joins the held
  map, the first stays addressable, and the key leaves with its last commitment.
  Invalidated if (derived): the put can store a different map, or a typed-registry
  mutator runs without the lifecycle lock.
- **`# compute-if-absent convergence`** — `subscribe`
  `RemoveConditionalMutator_EQUAL_ELSE`. Reason: forced past its `registered != null`
  arm, `subscribe` asks `computeIfAbsent` for the namespace that `get` returned in the
  same lock hold, and every `genericSubs` mutator holds that lock, so the present
  namespace comes back. Oracle: the `Map.computeIfAbsent` contract (it returns the
  current value when one is present);
  `HeldRegistryNamespaceTests.aSecondKeyJoinsTheHeldNamespaceOfAGenericMethod` sweeps an
  absent, held, emptied and re-created namespace and asserts that a second key joins the
  held one, is bound to its first key's cancellation method, and the namespace leaves
  only with its last key. Invalidated if (derived): the namespace can be absent or
  replaced on that path.

### Build and reconnect ownership

- **`# settled prior build`** — `connect` `RemoveConditionalMutator_EQUAL_ELSE`.
  Reason: it sends the predecessor's build to the `join()` arm instead of `cancel`,
  which differs only while that build is pending, and no successor is admitted then:
  a build is installed only beside its own bridge, an installed build's bridge settles
  only from that build's completion (the scheduler seam honours `schedule()`'s
  contract, so a call that throws or returns null never runs its task), and retirement
  and `close()` clear the two together. Oracle: `ConnectSuccessorAdmissionTests`
  asserts, at each admission, that the installed build is done or cancelled and that
  the build count moved as the shape predicts, across a pending handshake joined by
  callers, a deferred attempt, a build call that throws, a transport retired mid-build
  and a re-entrant `connect()` from the attempt's own completion. Invalidated if: a
  successor can be admitted while an installed build is pending, or the scheduler seam
  is driven outside `schedule()`'s contract.
- **`# adopted build identity`** — `connect` `RemoveConditionalMutator_EQUAL_ELSE`,
  the two operands of `replaced == null || replaced.socket != unadopted`. Reason: the
  first sibling aborts the displaced connection's socket a second time, after the
  displacement above aborted it; the second skips the abort whenever a connection was
  displaced, which differs only if that connection's socket is not the build's
  result. Oracle: the `java.net.http.WebSocket` contract. `abort()`: "Subsequent
  invocations of abort will have no effect"; and a `WebSocket` "invokes methods of the
  associated listener passing itself as an argument", so the socket a build completes
  with is the one its attempt listener adopts, and production adoption is fenced to
  the current attempt's listener (the engine's own `WebSocket.Listener.onOpen` is
  reachable only by casting the published type to the JDK interface, outside the
  published API). Invalidated if: `abort()` stops being idempotent, or builders that
  hand their listener a socket other than the one their future completes with are
  supported.
- **`# current socket identity`** — `close` `RemoveConditionalMutator_EQUAL_ELSE`, the
  second operand of `conn == null || conn.socket != unadopted` (the first is killed by
  `closeKeepsAnAdoptedBuildPoliteUntilItsWatchdog`). Reason: it skips the abort of the
  settled build's socket whenever a connection is current, which differs only if that
  connection's socket is not the build's result. Oracle: the `WebSocket` identity
  contract quoted above, for production adoption. Invalidated if: the same
  out-of-contract builders are supported.
- **`# zero-delay convergence`** — `connect` `ConditionalsBoundaryMutator`. Reason:
  `<` and `<=` differ only at `elapsed == reConnectDelay`, where the deferral arm
  computes `reConnectDelay - elapsed`, exactly zero, and a zero delay builds at once
  as the other arm does; the identity holds over the whole domain, the
  `Long.MAX_VALUE` first attempt included. Oracle: `ReconnectThrottleEdgeTests` reads
  the `connect()` contract ("waits out whatever remains of reConnectDelay") one
  millisecond short of, on, and past the edge, and the reconnect tests'
  `maximalDelaysDoNotSuppressTheFirstAttempt` reads the edge at `Long.MAX_VALUE`.
  Invalidated if: the deferral arm stops computing the remainder, or a zero delay is
  scheduled rather than built.
### Empty scans, deadlines, and wire order

- **`# empty-scan fast path`** — `escalateUnanswered`
  `RemoveConditionalMutator_EQUAL_ELSE` (the `inFlightSends.isEmpty()` operand) and
  `handleActivePendingSubscriptions` `RemoveConditionalMutator_EQUAL_IF` (the
  `killedSubIds` emptiness check). Reason: the mutant enters a loop over an empty
  `HashMap` entry set, whose iterator yields no element, so the per-entry body, the only
  place either loop does work, never runs; the deadline computed before it reads only a
  record accessor. Oracle: the `Iterator` contract and `Collection.removeIf`'s default
  traversal, which JDK 25's `HashMap.EntrySet` does not override;
  `MaintenancePassBoundaryTests.aPassWithNothingInFlightAndNoKillFindsNoWorkAtAnyAge`
  runs passes with nothing in flight from age zero to the largest clock step and asserts
  no escalation, frame, ping or ordinal entry. Invalidated if (derived): either loop
  gains work outside its per-entry body.
- **`# saturated deadline fringe`** — `escalateUnanswered`
  `ConditionalsBoundaryMutator`. Reason: it differs only at a resend window of exactly
  `Long.MAX_VALUE / UNANSWERED_ESCALATION_FACTOR`, where the deadline becomes
  `Long.MAX_VALUE` instead of `Long.MAX_VALUE - 3`; an age is the difference of two
  `pacingMillis()` readings, each a long nanosecond difference over a million, so no age
  exceeds about 1.9e13 ms and neither deadline is reachable. Oracle: that bound;
  `MaintenancePassBoundaryTests`'
  `aWindowAtTheSaturationBoundaryNeverEscalatesAtTheLargestAge` ages a transmitted
  request by the largest step a `TestClock` admits (about 9.2e12 ms) under windows one
  below, at and one above the boundary with no escalation, while a window whose deadline
  sits below that age escalates. Invalidated if (derived): the deadline becomes
  reachable, through a stamp or `now` that is not a `pacingMillis()` reading or pacing
  in units finer than a millisecond.
- **`# saturated-add equality`** — `onWholeMessage` `ConditionalsBoundaryMutator`.
  Reason: the two arms disagree only at the boundary window `Long.MAX_VALUE - now`,
  where `now + window` is `Long.MAX_VALUE` as well. Oracle: that identity;
  `MaintenancePassBoundaryTests.aRetryAtTheSaturatedAddBoundaryStaysParked` rejects a
  cancellation at pacing time 1 under windows `Long.MAX_VALUE - 2`, `Long.MAX_VALUE - 1`
  (the boundary) and `Long.MAX_VALUE`, and finds the retry parked past the largest clock
  step, while a reachable window re-sends it on time. Invalidated if (derived): the two
  expressions differ at the boundary.
- **`# strict wire ordinal`** — `ConditionalsBoundaryMutator` on
  `lambda$handleActivePendingSubscriptions$0` (the kill sweep), `lambda$onWholeMessage$0`
  (the kill record) and `onWholeMessage` (two siblings: the casualty replay and the
  dead-grant check). Reason: each compares a subscribe attempt's ordinal with a
  cancellation's, both pre-incremented from the connection's one `nextWireSeq` under the
  lifecycle lock (in `sendSubscription` and `sendUnSubscriptionLockHeld`), so the two are
  never equal, and the `Long.MAX_VALUE` absent default cannot equal a minted ordinal.
  Oracle: `WireOrderAdjudicationTests` builds, for each of the four comparisons, the
  attempts adjacent before and after one cancellation, reads their positions off the
  recording socket, and asserts the outcome wire order demands on each side.
  Invalidated if (derived): an attempt and a cancellation can share an ordinal, through
  an ordinal taken off the lock or attempts and cancellations counted separately.
- **`# positive request-id domain`** — `onWholeMessage` `ConditionalsBoundaryMutator`
  and `RemoveConditionalMutator_ORDER_IF`. Reason: `JsonRpcException.envelopeRequestId`
  yields a non-negative id or nothing, which the websocket maps to -1, and ids are
  minted from 2 (the counter starts at 1 and every mint pre-increments), so 0 and -1,
  the only ids on which the mutants decide differently, name no entry in any correlation
  map. Oracle: `envelopeRequestId`'s documented domain ("the only ids this client
  mints"); `ResponseIdDomainTests` sends rejections carrying 0, 1, null, negative,
  fractional, string and out-of-range ids, which release no registration and re-arm no
  send until one names the request's own id. Invalidated if (derived): a zero or
  negative id can enter a correlation map.

### Lock-owned registry representation

- **`# null-channel type invariant`** — `releaseChannelSlot`
  `RemoveConditionalMutator_EQUAL_IF` on the null-channel branch's
  `instanceof GenericSubscription` test. Reason: forced true, the branch casts every
  channel-less registration to `GenericSubscription`; the typed subscribes,
  `slotSubscribe` and `rootSubscribe` build theirs with a `Channel` constant and only the
  generic `subscribe` builds one without, so the cast never meets another type. Oracle:
  `RegistryOwnershipInvariantTests`' `onlyGenericRegistrationsLackAChannel` collects the
  handle of every registration kind through `onSub`, checks that only the generic one
  lacks a channel, then releases every kind through a request-defect rejection with no
  error but the server's. Invalidated if: a construction site passes a null `Channel` to
  a non-generic subscription.
- **`# identity-owned registry slot`** — `RemoveConditionalMutator_EQUAL_IF` on the
  identity tests guarding a release in `releaseChannelSlot`: the generic namespace's
  (`lambda$releaseChannelSlot$0`) and the slot and root singletons' (two siblings).
  Reason: forced true, a release frees whatever holds the key; both callers, a
  request-defect rejection and a coalesced grant, release only a registration they just
  took out of the current connection's pending map, and a pending registration always
  owns its slot: an unsubscribe frees the slot and drops the registration from the
  current connection's pending map under one lock hold, adoption re-derives pending from
  the durable registries, and the casualty and killed-grant replays re-queue only
  registrations that still hold theirs. Oracle: `RegistryOwnershipInvariantTests`'
  `aPredecessorsLateRejectionCannotReleaseItsSuccessor` (slot, root and generic: the
  predecessor's terminal answer leaves the successor registered and served) and
  `SolanaJsonRpcWebsocketReconnectTests`'
  `aCoalescedGrantRejectsTheSecondRegistrationLoudly`. Invalidated if: a registration can
  stay pending after its slot is freed or reassigned, or a caller releases a registration
  it did not take from the current connection.
- **`# subId-owner invariant`** — `queueUnsubscribe` `RemoveConditionalMutator_EQUAL_IF`
  on the value-conditional `subscriptionsBySubId.remove(subId, sub)`. Reason: forced
  true, an unsubscribe would queue a cancellation for an id its registration does not
  own; a non-null `subId` names the registration's own mapping on the current
  connection, since adoption and the casualty replay clear it with the mapping and a
  coalesced loser never gets one. The one path that drops a mapping and keeps the
  `subId`, a terminal signature notification, frees the durable slot in the same locked
  block on the current connection; on a connection retired with no successor the
  mapping goes and the handle keeps its `subId`, but no connection is current, so
  `queueUnsubscribe` returns before this check and the next adoption clears the id
  before any frame could name it. Oracle:
  `RegistryOwnershipInvariantTests`'
  `anUnsubscribeCancelsOnlyTheIdItsRegistrationWasGranted` (after adoption and after a
  casualty replay, the wire never names the dead id) and
  `SolanaJsonRpcWebsocketInboundHardeningTests`'
  `tombstonedEquivalentGrantLeavesItsLiveOwnerUntouched`. Invalidated if: a path on
  the current connection removes or replaces a mapping without clearing the handle's
  `subId` while the handle stays registered, or an unsubscribe can reach this check
  with no current connection.
- **`# prechecked in-flight gate`** — `sendUnSubscriptionLockHeld`
  `RemoveConditionalMutator_EQUAL_ELSE` on `!conn.inFlightUnsubs.add(subId)`. Reason:
  forced false, the helper would send while another cancellation for the id is
  unanswered; both callers, `sendUnSubscription` and `flushPendingUnSubscriptions`, test
  `inFlightUnsubs.contains` under the same lock hold just before the call, nothing
  between that test and the helper's `add` calls out, and every mutation of the set
  holds the lock, so the `add` always succeeds. Oracle: one wire cancellation per id at
  a time, the property `Connection#inFlightUnsubs` states, asserted on the direct path by
  `SolanaJsonRpcWebsocketReconnectTests`'
  `repeatedUnknownIdNotificationsMintOneCancellation` and on the flush by
  `RegistryOwnershipInvariantTests`' `aGatedIdGetsNoSecondCancellationFromTheFlush`.
  Invalidated if: a caller reaches the helper without the lock-held precheck, or code
  between a precheck and the helper's `add` can mark the id in flight.
- **`# disjoint registry phases`** — `onWholeMessage`
  `RemoveConditionalMutator_EQUAL_ELSE` on the `previous == pendingSub` operand of a
  grant's install test. Reason: forced false, a grant for a registration that already
  owns the granted id would take the coalesced-collision path and release it; a
  registration leaves the pending map before it is installed and is unmapped before the
  casualty replay re-queues it, so `putIfAbsent` never returns the pending registration
  itself. Oracle: `RegistryOwnershipInvariantTests`'
  `aCasualtyReGrantedItsOldIdIsInstalledWithoutACollision`, the one shape in which a
  pending registration meets its own former id: it is installed with no collision report
  and counted once by `retainedRegistrations`. Invalidated if: a path re-queues a
  registration without removing its mapping, or installs one still in the pending map.
- **`# pruned empty registry`** — `onWholeMessage`
  `RemoveConditionalMutator_EQUAL_ELSE` on the `registered.isEmpty()` operand of the
  generic-notification lookup. Reason: an empty namespace cannot be resident. A
  namespace is created beside its first registration (a null key is refused before the
  namespace or a request id exists, since 2026-10-07; before that the registry put
  threw after both had been created and the empty namespace stayed), and `unsubscribe`
  and the terminal release prune a namespace with its last registration. Forced false,
  the operand would read the first element of an empty map, which never exists, so the
  mutant decides nothing. Oracle: `FailedGenericSubscribeTests`
  (`aFailedSubscribeRetainsNoNotificationNamespace` holds `retainedRegistrations()` at
  zero after a refused key, and `aNotificationUnderAFailedSubscribesNamespaceIsIgnored`
  ignores the frame) and the unsubscribe prune tests. Invalidated if: a registration can
  fail after `computeIfAbsent` created its namespace, or a prune stops removing an
  emptied namespace.

### Correlation and wake hints

- **`# correlation co-registration`** — `onWholeMessage`
  `RemoveConditionalMutator_EQUAL_ELSE`, two siblings: the `rejectedUnsub != null` and
  `cancelledRequests.remove(requestId) != null` operands of an error response's
  `correlated`. Reason: forced false, either can only drop a correlation the
  `inFlightSends.remove(requestId) != null` operand already proves. A cancellation's
  acknowledgement entry and its in-flight entry are added together by
  `sendUnSubscriptionLockHeld` and removed together by its send-failure path and by the
  acknowledgement, numeric-answer and error branches; `queueUnsubscribe` tombstones only
  a request in `inFlightSends`, and the error and confirmation branches and
  `sendSubscription`'s recall and send-failure paths remove both under one lock hold.
  Oracle: a response correlates by id with the request this connection sent, so a
  correlated error is consumer news whatever its wording:
  `CorrelatedRejectionWordingTests`' `aRejectedCancellationIsDeliveredWhateverItsWording`
  and `aRejectedCancelledSubscribeIsDeliveredWhateverItsWording` deliver the "Invalid
  subscription id" wording for each structure and drop it for an id not in flight.
  Invalidated if: a path removes an `inFlightSends` entry while the acknowledgement entry
  or tombstone for the same request id stays.
- **`# absent-map removal`** — `onWholeMessage` `RemoveConditionalMutator_EQUAL_IF` on the
  grant branch's `kill != null` test before `killedSubIds.remove`. Reason: forced true,
  it removes a key the `get` just above, in the same lock hold, found absent:
  `killedSubIds` is written only through `merge` with a non-null wire ordinal, so a null
  `get` means no mapping, and nothing between the lookup and the removal writes the map.
  Oracle: the `Map.merge` contract (a merge never stores a null value) and the
  `Map.remove` contract (removing an absent key leaves the map unchanged). Invalidated
  if: `killedSubIds` gains a writer that can store null, or code between the lookup and
  the removal can add the key.
- **`# pending-work wake hint`** — `onWholeMessage`
  `RemoveConditionalMutator_EQUAL_IF`, three siblings: the rejected-unsubscribe path,
  the acknowledged-unsubscribe path (`stillQueued`) and the numeric-answer path each
  signal `newSubscription` only when a matching pending cancellation exists. Reason:
  forcing the check true adds a condition signal (and the wake hint that goes with it)
  when no matching work remains; no registry state changes. Oracle: condition wakeups
  are explicitly allowed to be spurious (`Condition` contract). Invalidated if
  (derived): a waiter treats a wakeup as proof of work.

### Parser rescans

- **`# unique-member rescan`** — `RemoveConditionalMutator_EQUAL_IF` on `skipToParams`
  (`params`), `onWholeMessage` (two siblings: the keyed channels' `value` scan beside
  `context`, and the signature path's `subscription` scan), `publish` (two siblings,
  one per overload) and `publishGeneric`, each forcing the rescan from the object's
  mark after a forward scan that already found its member. Reason: on a well-formed
  frame the forward scan and the rescan read the same object, so the rescan re-finds
  the member; they part only on an object with two members of one name, one on each
  side of the cursor, which RFC 8259 section 4 leaves unpredictable. Two conditions
  that argument needs are enforced since 2026-10-07: the account path's `publish`
  overload skips the value and the rest of `result` before its forward scan, so that
  scan reads `params` (a `result` member named `subscription` used to redirect the
  notification; `AccountNotificationAttributionTests`), and a keyed notification whose
  `params` has no `result` or whose `result` has no `context` is refused rather than
  scanned past (`NotificationResultShapeTests`), since scanning on would read the rest
  of `params` as the context and the value. Oracle: `NotificationMemberOrderTests`
  sends a logs, program, account, signature and generic notification in every member
  order, with decoy members nesting the same names, and requires the same item from the
  forward route and the rescan route (RFC 8259 section 1: object members are
  unordered). Invalidated if (derived): a site's forward scan and rescan stop reading
  the same object, a required member stops being enforced, or duplicate member names
  become part of the contract.
- **`# fast-forward funnel`** — `NakedReceiverMutator` on `onWholeMessage` (signature
  path) and `publish` (two siblings, one per overload), each dropping the
  `skipRestOfObject()` in front of the forward `subscription` scan. Reason: none in
  force; the acceptance is withdrawn. It held that the mark/reset fallback reaches the
  same member, but without the skip the forward scan reads the rest of `result` (logs,
  program, signature) or the account object itself (account), where an unknown member
  named `subscription` is payload. The mutant attributes the notification to that
  member: it drops the notification with an unsubscribe for the nested id, or hands it
  to the registration the id names. Oracle: `NestedSubscriptionMemberTests` kills all
  three; the code attributes those frames by `params.subscription`. Invalidated if:
  already; the rows leave through the prune writers after two matching fresh previews,
  and this bullet then moves to the history notes.

### Ping and private-tail cleanup

- **`# ping-state invariant`** — `recordFailedPing` `RemoveConditionalMutator_EQUAL_IF`,
  forcing `pingFailure == null` true (its two siblings at this key are killed: the
  `lifecycle == ACTIVE` operand by `aPingFailureLosingToAnAlreadyClaimedDeadlineIsDropped`,
  the `!superseded` operand by `TeardownPingFailureTests`, see the history notes).
  Reason: the operand is implied by the `lifecycle == ACTIVE` operand before it.
  `pingFailure` is set non-null at one site, in the locked transition that moves `ACTIVE`
  to `PING_FAILED`; no assignment returns a connection to `ACTIVE`, and only
  `takePingFailure` clears the field, after retirement. Oracle: the `Connection` field
  contracts (`lifecycle` "Leaves ACTIVE at most once"; `pingFailure` is "published under
  the lifecycle lock with the `PING_FAILED` transition"). Invalidated if (derived):
  `pingFailure` gains a writer outside that transition, or a connection can return to
  `ACTIVE`.
- **`# private-tail normalization`** — `lambda$sendSubscription$1`
  `NullReturnValsMutator`, on the `return` of the recall branch, which drops a
  subscribe cancelled while it waited in the chain. Reason: the null return makes the
  link's `thenCompose` stage complete exceptionally, and that stage is read only as
  the next link's predecessor. `queueText` and `sendSubscription` both begin with
  `outboundTail.exceptionally(_ -> null)`, the tolerance that keeps a failed send from
  blocking the chain, and nothing else reads the tail. Oracle:
  `RecalledFrameChainTests` recalls a frame and chains a successor behind it through
  each reader, requiring the successor on the wire in order and no report from the
  error, send-error, ping-error or exception seams. Invalidated if (derived): anything
  but a link's `exceptionally` reads the tail, or a reader drops that normalization.

### Timed-out mutants (audited set)

`TIMED_OUT` is detected, never accepted, but a watchdog cannot prove that a
covering assertion observed the defect. `ws-timeouts.csv` therefore holds a
line-less audited key set, and verification warns on a timeout outside it. Every
member has `cause:liveness`; there are no resource or harness holding rows.

Shared cause, argued once: each member makes the `SolanaJsonRpcWebsocket` maintenance
loop lose an exit or its per-cycle work, so the mutated path has no path-owned finite
completion guarantee. The members are `SolanaJsonRpcWebsocket` `runLoop`
`RemoveConditionalMutator_EQUAL_ELSE` (two sibling mutants, one key row) and
`runLoop` `VoidMethodCallMutator`.

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

Why no synchronous reader serves as a deterministic oracle for the two `runLoop`
members (`HARDENING.md`, "`TIMED_OUT` is detected, but does not diagnose its cause"):
the only observable of either exit is `run()` returning, which never happens on the
mutated path, so there is no post-call reader. For the interruption sibling,
`Thread.interrupted()` runs before the removed jump and consumes the flag, so no later
flag reader exists either. `runLoop` is private and calls `checkCycle` directly, and
the mutated path touches no injectable collaborator, so no call budget or clock seam
applies.

### ws history notes

Kept apart from the arguments above; none of it is live evidence.

- `closed` `RemoveConditionalMutator_ORDER_ELSE`, forcing `msgId < 0` false, sat in the
  audited set until 2026-10-07 under the shared liveness cause. It never met the
  admission precondition (`HARDENING.md`: liveness is claimed only after deterministic
  seams have been exhausted): `checkLoopReturnsImmediatelyOnceClosed` asserts
  `ws.closed()` synchronously right after `ws.close()`, a synchronous reader of exactly
  the state the mutant breaks, and every covering test that enters the loop exits on
  interruption or a poisoned clock, or does not wait for the loop thread, so no
  covering path can hang on it. It has read `KILLED` by that test in every fresh run
  since 2026-08-11. The owner directed the removal on 2026-10-07 and the membership
  line was removed by hand; a timeout reappearing at that coordinate reads as
  unaudited and must be argued afresh.

- The keyed prune of 2026-10-07 retired every `# killed retained` and
  `# retired implementation retained` row whose key held nothing else. The killed rows
  (`SolanaJsonRpcWebsocket` unless named) sat on `accountSubscribe`, `checkCycle`,
  `close`, `lambda$queueUnsubscribe$0`, `lockAndHandlePendingSubscriptions`,
  `logsSubscribe`, `onClose`, `onError`, `onOpen`, `onWholeMessage`, `publishGeneric`,
  `queueSubscription`, `rootSubscribe`, `sendPing`, `sendUnSubscription`,
  `signatureSubscribe`, `slotSubscribe`, `subscribe`, `subscribeToTokenAccounts` and
  `SolanaRpcWebsocketBuilder.create`; the retired rows on `lambda$connect$0`,
  `lambda$sendPing$0`, `onWholeMessage`, `removeDanglingSub` and `unsubscribe`. The
  three rows still blocked at shared keys are listed under "Retained rows and the
  writer gap".

- The licensed toolchain became the population of record on 2026-10-07 (owner
  decision, `AGENTS.md`), and the rows written from the stock engine's population in
  2026-08 that the licensed engine never generated left through the unscoped prune
  after two matching previews: the `# unlicensed-only retained` rows on
  `ensureCapacity` and `onText` (`ORDER_IF`), `logsSubscribe`, `programSubscribe`,
  `lambda$queueUnsubscribe$0` (two) and `handlePendingSubscriptions` (two)
  (`EQUAL_IF`); the `# retired implementation retained` row on
  `handlePendingSubscriptions` `EQUAL_IF`; the `# killed retained` row on
  `programSubscribe` `EQUAL_ELSE`; and two stale-tagged rows whose keys held one row
  more than the population (`ensureCapacity` `MathMutator`, `onWholeMessage`
  `EQUAL_IF`). Two rows that had carried retained labels turned out to match live
  licensed mutants and were relabelled into the families that argue them:
  `ensureCapacity` `MathMutator` at line 2607 (`# capacity math`) and the
  rejected-unsubscribe signal in `onWholeMessage` at line 2101
  (`# pending-work wake hint`). The "Retained rows and the writer gap" section that
  itemised all of this is gone with them.

- The oracle pass of 2026-10-07 (every family swept for a counterexample over the input
  shapes its mutated control flow keys on) withdrew five acceptances and killed their
  rows; each left through the prune writer after two matching previews:
  - `# equal-capacity copy` (`ensureCapacity` `ConditionalsBoundaryMutator`): at exact
    capacity the mutant grows a buffer the message still fits, and at the cap it copies
    the whole buffer for every empty continuation fragment, a complexity change;
    `ReassemblyGrowthGuardTests` kills it through the `reassemblyCapacity()` seam.
  - `# ignored null completion` (`lambda$ownBuild$0` `EQUAL_IF`): `whenComplete` adds an
    action's exception as suppressed to its source's, so the mutant's
    `NullPointerException` rides the cancelled build's `CancellationException` to
    `connect()`'s caller; `AbandonedAttemptCancellationTests` kills it.
  - `# redundant outer duplicate guard` (`accountSubscribe`, `logsSubscribe`,
    `signatureSubscribe` `EQUAL_ELSE`, and the misfamilied `programSubscribe` row, which
    was the `containsKey` operand): the public guard is a fast path with observable
    effects, a refused duplicate renders nothing of the caller's key and never waits for
    the lifecycle lock, and the `programSubscribe` operand refused a second commitment of
    a held program; `DuplicateSubscribeRoutesTests` kills all four.
  - `# connection-owned registry` (`onWholeMessage` `EQUAL_IF`, the acknowledgement
    branch's `conn == this.connection` re-check): a true acknowledgement's casualty
    replay clears the durable handle's `subId`, which the successor has by then re-armed,
    so a stale acknowledgement inside the displaced parser erased the successor's grant;
    `DisplacedAcknowledgementTests` kills it.
  - `# retired-state write` (`recordFailedPing` `EQUAL_IF`, forcing `!superseded` true):
    `onError` and `onClose` read the retired connection's ping failure through
    `takePingFailure` right after the abort, so the mutant turned teardown noise into a
    ping failure; `TeardownPingFailureTests` kills it. Its sibling keeps
    `# ping-state invariant`.
  The same pass found two parser defects, fixed with regression tests: the account
  notification path scanned the rest of `result` for `subscription` (a member of that name
  inside `result` redirected the notification) and a keyed notification missing `result` or
  `context` was scanned past its end rather than refused; and the generic `subscribe`
  accepted a null key late enough to leave an empty namespace resident, now refused up
  front. Four rows of the `onWholeMessage` `EQUAL_IF` key had been re-tagged positionally
  by earlier retags and carried another construct's label; they were relabelled by hand
  to the family that argues the line each tag names.

## encoding suite

Targets `software.sava.rpc.json.*` and `software.sava.rpc.json.http.request.*`,
subtracting the client, responses and ws packages. It has no accepted rows and no
audited timeouts. `RpcEncoding`'s missing `jsonParsed` constant is a deliberate API
invariant (`AGENTS.md`), not a gap to triage. `PrivateKeyEncodingTests` pins the
key-import field-order, unknown-field and missing-field contracts; the declaration
documents the retained duplicate-encoding behaviour (a later encoding does not
reinterpret an already imported secret).

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
| `EXPERIMENTAL_NAKED_RECEIVER`, 2026-10-07 | `encoding` | 58 | 65 | 7 |

`encoding` was registered on 2026-08-04, after the first trials, and carried the
mutator untrialed until the 2026-10-07 run of `pitestMutatorTrial`, which measured
all four suites with the candidate alone: it fired in each (`client` 103, `ws` 67,
`responses` 90, `encoding` 7 generated), so the set stands everywhere.
