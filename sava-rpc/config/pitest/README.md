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

The 2026-08-11 fresh, full, history-free pass generated 586 mutants: 579 killed
and 7 survived, with zero `NO_COVERAGE`, timeout, or invalid outcomes. All seven
current survivors are argued below. The 18-row file is deliberately larger than
the current survivor set: ten freshly killed rows are marked `# killed retained`,
and one row generated only by the old unlicensed toolchain is marked
`# unlicensed-only retained`. Those eleven rows are historical evidence, not
acceptance of current mutants; the reason they remain is recorded below.

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

One `# unlicensed-only retained` row — `BaseSolanaJsonRpcClient.joinKeys`
`RemoveConditionalMutator_EQUAL_IF` — has **no counterpart in the licensed
population** and is reported unmatched on every run, the same way the protected
`ws` rows are (see the `ws` note on that). It remains because absence under the
licensed toolchain is not evidence that the old unlicensed mutant was killed.
The other `joinKeys` direction is now an ordinary kill: direct helper tests
distinguish null, empty, and populated collections.

**Dead input arm** — baseline label `# dead pattern arm` (SURVIVED):
`JsonHttpClient.readInputStream` is private and is called only from the
`body instanceof InputStream inputStream` pattern arm. That pattern binding is
necessarily non-null, so forcing the method's defensive `inputStream == null`
check false cannot change any reachable call. The opposite mutation is killed
by the non-empty stream contract.

**Capacity hints only** — baseline label `# capacity hint only` (SURVIVED): the
two `ProgramAccountsRequestRecord.toJson` survivors change only the initial
`StringBuilder` capacity derived from the filter count. Request content is
appended independently by `appendFilters`; null, empty, and populated filter
requests produce the same bytes under these mutants. The sibling arithmetic
mutation that changes appended content is killed.

**Deferred value parsing equivalences** (SURVIVED): `# impossible zero mark`
marks the `valueMark < 0` boundary mutation in
`JsonRpcValueResponseParser.Parser.parse`. Its only distinguishing value is
zero, but a mark captured after the enclosing object's `value` member name can
never be zero: the two reachable domains are the `-1` absent-value sentinel and
a positive cursor position. `# eager deferred convergence` marks the mutation
that always records and skips a value even when its context was already parsed.
The final parse resets to the same value bytes and supplies the same context, so
eager and deferred paths invoke the value parser once with the same inputs and
return the same result.

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

**Request-body DEBUG diagnostic** — baseline label `# request debug only`
(SURVIVED): removing `newPostRequest`'s DEBUG body log does not change the URI,
timeout, method, headers, body publisher, or returned request. This is an
accepted non-contract diagnostic, not a claim that JUL output is impossible to
observe. The old combined acceptance with `gzipBufferSize` was wrong:
`gzipBufferSize` diagnoses a malformed provider-controlled `Content-Length`
before safely decoding the body, and `TestLogs` now asserts that diagnostic, so
its log-call mutant is killed.

**Freshly killed rows retained by the writer boundary** — baseline label
`# killed retained`: the 2026-08-11 pass killed ten previously accepted rows.
They cover the other `joinKeys` direction, the `gzipBufferSize` diagnostic, both
`wrapResponseParser` rows, the generic-result parser factory, the
absent-value cursor branch, empty-account rejection, the immutable empty leader
schedule, and both `sendTransaction` boolean encodings. The named prune/update
writers cannot remove only those rows: they would also delete the protected
unlicensed-only `joinKeys` evidence. Because baseline structure is never edited
by hand, the killed rows remain with an explicit non-acceptance label until a
sanctioned selective writer exists.

## Triaged mutants — ws suite

The suite was seeded on 2026-07-21 over
`software.sava.rpc.json.http.ws.*` and then hardened through the connection,
registry, parser, ping, close, and reconnect rewrites. The current state is the
fresh, full, history-free 2026-08-11 observation: 1,248 mutants, 1,163 killed,
82 survived, 3 timed out, and zero `NO_COVERAGE` or invalid outcomes. Every
current survivor is argued below; no row remains `# untriaged`.

The 159-row accepted file is deliberately larger than the current survivor
set: 82 rows represent current survivors, 40 `# killed retained` rows and 28
`# retired implementation retained` rows are historical records that no
longer match an unkilled mutant, and 9 `# unlicensed-only retained` rows
preserve the old open-source-PIT population. All 77 retained rows are records,
not acceptances of current behavior.

### Current accepted survivors

- **Hash and sentinel domains.** `# hash distribution only` records that
  replacing `RootSubscription.hashCode()` with a constant preserves the
  `equals`/hash contract and changes only bucket distribution.
  `# positive sentinel gap` records that websocket message ids start positive
  and close jumps directly to `Long.MIN_VALUE`; zero, the sole value separating
  `< 0` from `<= 0`, is unreachable.
- **Capacity and buffer routing.** `# equal-capacity copy` makes exact capacity
  enter the growth branch: the clamp grows a sub-maximum buffer and performs a
  same-sized copy only at `maxMessageLength`, without changing bytes or parsing.
  The two `# capacity math` siblings alter only the growth
  hint before `Math.clamp`, which still allocates at least the required
  capacity. `# zero-offset route convergence` sends an unfragmented message
  through the assembled-buffer route; it parses the same characters.
  `# equivalent buffer copy` chooses `arraycopy`, `CharBuffer.get`, or a
  wrapped buffer for the same remaining characters; only the callback-owned
  buffer cursor and allocation route differ.
- **Literal and convergent registry returns.** `# literal return equivalent`
  marks return-value replacements equal to the literal at that bytecode exit.
  `# same-map re-put` stores the map already held at the same key.
  `# redundant outer duplicate guard` lets a duplicate reach the lock-held
  `queueSubscription` check, which returns the same result before minting an
  id. `# compute-if-absent convergence` returns the already-present generic
  method map.
- **Build and reconnect ownership.** `# settled prior build` follows from the
  single-flight bridge: a successor cannot reach cleanup until the prior build
  is done. `# adopted build identity` records that a successful build and the
  socket delivered to its attempt listener are the same object.
  `# current socket identity` is the close-side twin: an adopted completed
  build is the current connection and must remain on the polite close path.
  `# zero-delay convergence` differs only at the exact throttle edge, where
  both routes connect immediately. `# ignored null completion` can fault only
  an ignored dependent stage, not the original build or public bridge.
- **Empty scans, deadlines, and wire order.** `# empty-scan fast path` enters
  an iteration over an already-empty map and still finds no work.
  `# saturated deadline fringe` moves an unreachable deadline a few
  milliseconds below `Long.MAX_VALUE`; no representable age from the
  monotonic clock reaches either value. `# saturated-add equality` chooses
  two expressions that both equal `Long.MAX_VALUE` at the boundary.
  `# strict wire ordinal` relies on distinct lock-held transmissions receiving
  distinct pre-incremented ordinals. `# positive request-id domain` relies on
  client ids beginning at 2; zero and negative ids never occupy correlation
  maps.
- **Lock-owned registry representation.** `# null-channel type invariant`
  records that only `GenericSubscription` has no channel.
  `# identity-owned registry slot` and `# subId-owner invariant` cover
  removals reached with the same subscription that owns the slot or server id.
  `# prechecked in-flight gate` belongs to a helper whose lock-held callers
  have already proved the per-id gate absent. `# disjoint registry phases`
  records that a subscription leaves pending before installation and leaves
  installed before requeue. `# pruned empty registry` records that an empty
  generic namespace is removed from the outer map.
- **Correlation and wake hints.** The two `# correlation co-registration`
  siblings clear correlation structures in the same locked response
  transition, so either surviving operand proves the same correlated result.
  `# connection-owned registry` records that an acknowledgement map belongs
  to its `Connection`; a displaced connection cannot share its successor's
  entry. `# absent-map removal` performs only a no-op removal when the earlier
  lookup proved the key absent. The three `# pending-work wake hint` siblings
  add a condition signal when no matching work remains; condition wakeups are
  explicitly allowed to be spurious and no state changes.
- **Parser rescans.** `# unique-member rescan` resets and finds the same
  unique JSON-RPC `params`, `value`, or `subscription` member; duplicate
  member-name resolution is outside the protocol contract.
  `# fast-forward funnel` drops an initial iterator fast-forward, after which
  the existing mark/reset fallback reaches the same member and dispatches the
  same notification.
- **Ping and private-tail cleanup.** `# retired-state write` permits only an
  extra ping-state write to a displaced `Connection` plus a condition wake;
  no live state or callback reads it. `# ping-state invariant` follows from
  the same lock-held transition publishing the failure while changing
  `ACTIVE` to `PING_FAILED`. `# private-tail normalization` can make only
  a private discarded completion tail exceptional after cleanup has committed;
  the next enqueue normalizes that prior exception.

### Retained historical rows

`# killed retained` means the fresh licensed run observed the corresponding
behavior as killed. `# retired implementation retained` means the mutation
site or its containing helper no longer exists. They remain because the named
update/prune writers cannot delete only those 68 rows without also deleting
protected unlicensed evidence; hand-editing baseline record structure is not a
sanctioned substitute. Their status fields remain historical by design and do
not describe the current report.

The nine `# unlicensed-only retained` rows are the two
`lambda$queueUnsubscribe$0 EQUAL_IF` siblings, `ensureCapacity ORDER_IF`,
`onText ORDER_IF`, the `logsSubscribe` and `programSubscribe EQUAL_IF`
rows, two `handlePendingSubscriptions EQUAL_IF` siblings, and one
`onWholeMessage EQUAL_IF` sibling. The licensed ArcMutate population does not
observe them. Absence under that toolchain is not a kill, and the line-less
multiset cannot safely identify them for selective deletion. They remain until
a sanctioned writer can preserve that evidence while pruning the separately
killed and retired rows.
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

## Timed-out mutants (audited set)

`TIMED_OUT` is detected, never accepted, but a watchdog cannot prove that a
covering assertion observed the defect. `ws-timeouts.csv` therefore holds a
line-less audited key set, and verification warns on a timeout outside it. The
fresh 2026-08-11 full run produced three timed-out mutants: two
`runLoop,RemoveConditionalMutator_EQUAL_ELSE` siblings and one
`runLoop,VoidMethodCallMutator`. Every current timeout has
`cause:liveness`; there are no resource or harness holding rows.

- **`SolanaJsonRpcWebsocket.runLoop` `EQUAL_ELSE` (two sibling mutants).** One forces the
  `closed()` exit false and the other forces the interruption exit false.
  In each mutated path the corresponding terminal event can no longer end the
  loop, and the path owns no replacement finite completion guarantee. Timeout
  membership is key-level, so one CSV row honestly classifies both siblings.
- **`SolanaJsonRpcWebsocket.runLoop` `VoidMethodCallMutator`.** Removing `checkCycle` leaves the
  unbounded loop doing no maintenance work. A covering path waiting for the
  cycle's state transition has no path-owned completion; an external close or
  interrupt is only the fixture's emergency exit.
- **`SolanaJsonRpcWebsocket.closed` `ORDER_ELSE`.** Forcing `msgId < 0` false prevents the close
  sentinel from ever ending the maintenance loop. It was killed in the current
  run, but remains in the audited set until the liveness-retirement rule has
  three distinct fresh quiet full runs over identical evidence inputs and the
  required solo/gate confirmation. It is not counted among the current three
  timed-out mutants.

The old `run`, `close`, connect-lambda, `connect`, `checkCycle`, and
`ensureCapacity` timeout members were removed. Their mutation sites were
eliminated or their finite behavior now has a deterministic killed or accepted
disposition; none remains under a `cause:harness` label.
