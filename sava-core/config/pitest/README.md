# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,mutator,STATUS` — line numbers are metadata, carried as
a trailing `# line N` tag that the writers refresh (`BaselineRetag`, a green
`BaselinePrune`, `BaselineUpdate`) and unions preserve, so editing above a
mutated method churns nothing. Full policy — the three legal outcomes for a new
survivor, determinism requirements, targeting rules — lives in sava-build's
`HARDENING.md`.

Never run a `pitest<Suite>BaselineUpdate` task just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below. A failure classifies each new row (`newly covered` vs shares an
accepted key vs unexplained) and closes with a churn tally. A key unkilled at
a line no row's `# line` tag names draws the line-drift advisory: the code an
acceptance argues about moved, or a new mutant sits under an old acceptance
(the line-less key's one documented blind spot) — re-read the argument below,
then run `pitest<Suite>BaselineRetag` if it still applies.

Arguments below name **methods and constructs, not line numbers**: prose
anchors are not machine-checked and rot silently on the first refactor (the
sava-rpc ws family's did, wholesale, before 2026-08-01). Line numbers appear
nowhere here, History included; the current PIT report and each row's `# line`
tag, which the line-drift advisory checks, are the only source locators.

**Identical rows are sibling mutants — never dedupe this file.** One compound
condition emits a mutant per operand or branch direction at the same
`class,method,mutator,STATUS` key (and one `MathMutator` key can cover
different operations across a method — a shift and an add — the `# line` tags
telling the copies apart), so a key legitimately repeats. The comparison is a
multiset: collapsing the copies, as this repository did until 2026-07-23, let a
killed sibling regress unnoticed behind its accepted twin.

This file holds the arguments in force, one section per suite. What a run
counted, added, pruned or killed is that run's output and git's history; the
per-suite scope decisions and the dated mutator-set trial tables are in
`HARDENING_NOTES.md` (§sava-core, §Mutator-set trials). Retired arguments that
still teach something are kept apart, under "History (not evidence)" at the
end, so none of them reads as live evidence.

## Timed-out mutants (audited set)

`TIMED_OUT` is detected — these mutants never enter a baseline — but the
watchdog observed slowness, not wrongness: for exactly these mutants the
ratchet cannot see a weakened covering assertion, because a timeout keeps
"detecting" no matter what the test asserts. Per HARDENING.md, the summary's
`N timed out (watchdog detection; not a cause diagnosis)` is therefore an
audited set, not a count. Each suite's `<suite>-timeouts.csv` holds the
line-less `class,method,mutator` keys with their `cause:liveness` token, the
verify warns on any timeout outside them, and a mutant timing out that is
*not* in the set is something a reviewer stops on. Per-run counts sit at or
below the set size — a dead mutant's covering test racing the watchdog can land
either detected flavour. Each member's structural cause is argued in its
suite's "Audited timeouts" subsection below.

**A timeout is not one thing, and the difference decides whether a member
belongs here at all.** The first class is *non-termination*: a loop whose only
exit the mutant removed, or a lock it never releases. Nothing can assert
against it except the watchdog, so it is correctly audited — every current
member is of this kind. The second is *resource*: the mutant returns the
identical answer having done far more work, and only crawls into the watchdog
because allocation or GC caught up with it. That is not detection, it is a
race, and it reads `TIMED_OUT` under load and `SURVIVED` when idle. A resource
mutant is assertable and therefore does not belong in an audited set: find the
bound the method already claims and check it. Prefer a value assertion on that
bound over an allocation or timing harness, which sava-build's `HARDENING.md`
calls a last resort (worked example: `Base58.limbsLength`, under History).

Liveness is admissible only **after deterministic seams and budgets are
exhausted**: a mutant that a budget can bound is not a liveness member, it is
an unexercised seam (worked example: the four `vanity` members retired
2026-08-08, under History). The remaining `vanity` members and the `Jex.isValid`
member became budget-bounded on 2026-10-07 and read `KILLED` since; they sit in
their sets only until the quiet notice, as their suites' subsections record.
Membership lines are hand-maintained records; a
member leaves under HARDENING.md's quiet or stale rule, never by a writer. A
*quiet* member — its mutant still generated but no longer timing out — leaves
only after the tool's 3+ distinct fresh full-run quiet notice over identical
evidence inputs and a solo/gate confirmation. A *stale* member — its line-less
coordinate absent from one fresh full history-free report with valid committed
provenance — is removed by hand after that run, and the removal is recorded
under History.

## borsh

Mutator set: plain `STRONGER`. Both `EXPERIMENTAL_BIG_INTEGER` (2026-07-21) and
`EXPERIMENTAL_NAKED_RECEIVER` (2026-07-22) fired nothing here —
`HARDENING_NOTES.md` §Mutator-set trials.

No accepted mutants, so there is no `borsh-accepted.csv`. Keep it that way.

## crypto

Mutator set: plain `STRONGER`. `EXPERIMENTAL_NAKED_RECEIVER` fired nothing in
the 2026-07-22 trial (`HARDENING_NOTES.md` §Mutator-set trials); that trial's
population still included the since-removed two-argument `Hmac.hmacSHA512`
helper, so its generated count is not the current population.

No accepted mutants, so there is no `crypto-accepted.csv`. The `ed25519`
subpackage is excluded here — it has its own suite, and the `crypto.*` wildcard
spans dots.

`Hash.sha256Twice` and `Hash.h160` have no caller anywhere in the repo. They
were kept rather than deprecated since they are correct, tiny, and removing
`h160` would not shed the BouncyCastle dependency (ed25519, `Signer`,
`PublicKey` and Argon2id all need it). Being uncalled is exactly why they are
pinned to published vectors *and* differentially checked against a naive
two-instance implementation: `sha256Twice` reuses one `MessageDigest` across
both rounds and depends on `digest()` resetting it, so comparing against the
same technique twice would prove nothing.

## decimal

Mutator set: `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` — the latter fired in the
2026-07-22 trial (`HARDENING_NOTES.md` §Mutator-set trials).
`EXPERIMENTAL_BIG_DECIMAL` / `EXPERIMENTAL_BIG_INTEGER` stay off: they generate
nothing here, because this package's only arithmetic is the `int`-taking
`movePointLeft`/`movePointRight` (§pitestDecimal, and the comment at the
suite's registration in `sava-core/build.gradle.kts`).

### Accepted families

**Allocation routing only** — baseline label `# allocation routing`. Members:
`DecimalInteger.toDecimal` and its `BigInteger` twin
`DecimalIntegerAmount.amount`, `ConditionalsBoundaryMutator` and
`RemoveConditionalMutator_ORDER_IF` each, on the unsigned-widening guard
`val < 0 ? ByteUtil.toUnsignedBigInteger(val) : BigDecimal.valueOf(val)`.
Both branches build an identical value for every non-negative long — verified
exhaustively over the boundaries and 2M random values — so `<` → `<=` (which
differs only at zero, where both give zero) and forced-true (which always
widens) cannot be told apart by any assertion on the result. The guard exists
because `valueOf` is cheaper, not because the branches disagree. The
forced-*false* direction is not equivalent — it sign-extends instead of
widening — and is killed by
`DecimalIntegerTests.longOverloadTreatsNegativeAsUnsigned` and
`amountWidensNegativeLongsAsUnsigned`. Invalidated if the two branches can
ever build different values for a non-negative input.

The capability the deterministic harness lacks is an allocation oracle. These
were briefly killed on 2026-07-20 with `ThreadMXBean` allocation bounds, which
is the technique HARDENING.md suggests for exactly this shape. It was
reverted, and the reasons are worth recording before anyone tries again:

- **The measurement is fragile.** A result that is immediately discarded can be
  scalar-replaced by escape analysis, erasing the allocation being measured —
  and only on runs that reach the right JIT tier. The first version passed
  alone and failed intermittently under the ratchet. A `volatile` sink fixes
  it, but the fragility is inherent.
- **The margins are thin.** Bounds have to be set per method from
  measurements; `toDecimal` has a ~40 byte floor that `amount` does not, and on
  a large value the gap between the fast path and the mutant is 64 bytes
  against 88.
- **PIT re-runs covering tests once per mutant.** A warmup-plus-rounds harness
  of ~150k iterations per assertion took this suite from ~10s to ~38s, for
  mutants that are correctly described here in prose.

A documented equivalent mutant is a closed gap; chasing these to make a
percentage read 100 cost more than it returned.

## ed25519

Mutator set: plain `STRONGER`. `EXPERIMENTAL_NAKED_RECEIVER` fired nothing in
the 2026-07-22 trial (`HARDENING_NOTES.md` §Mutator-set trials).

### Accepted families

**Dropped carry passes** — baseline label `# pack25519 passes`. Members:
`Ed25519Util.pack25519`, `VoidMethodCallMutator` ×3 (dropping one of the three
leading `car25519` passes) and its `ConditionalsBoundaryMutator`. The remaining
passes plus the exact double conditional-subtract reduction still fully
normalize every limb state reachable from 32-byte `Codec.decode32` inputs; the
passes are retained as defense. Oracle: the same differentials as `# verdict
invisible` — the BouncyCastle and BigInteger Euler-criterion comparisons over random and
non-canonical encodings, the RFC 8032 vectors and the boundary sweeps in
`Ed25519UtilTests` — run with each of the four mutants applied by hand on 2026-10-07,
all passing (the random encodings are sampled fresh each run). Invalidated if a caller
can hand `pack25519` a limb state outside that reachable set, or the reduction tail
changes.

**Static-initializer construction** — baseline label `# static init`. Members:
`Ed25519Util$PointAccum.create` and `Ed25519Util$PointExtended.create`,
`NullReturnValsMutator`. Both are called only while the `static {}` block
builds the comb precomputation tables, once per PIT minion JVM before any
mutant activates — unkillable by construction (same family as
json-iterator's `JHex$INIT_DIGITS`). No test oracle can apply: no test
executes these after class initialisation. Invalidated if either factory is
ever called outside class initialisation.

**Verdict-invisible arithmetic in the TweetNaCl path** — baseline label
`# verdict invisible`. Members: `Ed25519Util.car25519` bias terms
(`MathMutator` ×2), `Ed25519Util.sel25519` XOR→AND (`MathMutator`),
`Ed25519Util.pack25519` tail mask/shift (`MathMutator` ×2),
`Ed25519Util.scalarMultBase`'s final `cnegate` (`VoidMethodCallMutator`), and
`Scalar25519.toSignedDigits` (`MathMutator`). The on-curve verdict consumes
packed values only through equality of two identically-packed values and
low-bit parity. Oracle: both differential oracles — BouncyCastle and the
in-test BigInteger Euler-criterion oracle over the full input domain,
including torsion points and non-canonical encodings — pass with these mutants
active, verified individually 2026-07-16; the oracles are described in
`AGAVE_SYNC.md` "Ed25519 hardening (sava-core `crypto/ed25519/`)". Invalidated
if a public path starts consuming these packed values other than through that
equality and parity.

### Audited timeouts

Every member is in `Ed25519Util`. Each read `TIMED_OUT` identically solo and
under gate load in the 2026-07-22 mode comparison (`HARDENING_NOTES.md` §Mode
comparison), and each was observed `TIMED_OUT` again in the 2026-09-05 and
2026-09-25 certification runs. Each makes a counted loop's only exit
unreachable:

- `Ed25519Util.pow2523`, `RemoveConditionalMutator_ORDER_IF` on `a >= 0`: the
  2^252−3 exponentiation ladder loses its countdown exit.
- `Ed25519Util.scalarMultBase`, `IncrementsMutator` (`var6 -= 4` → `+= 4`):
  the window cursor walks up instead of down. The `var6 < 0` exit is reached only
  once the `int` wraps, after about 5.4 × 10^8 further ladder steps, each a full
  point operation, so the loop is finite in arithmetic but not in any run a watchdog
  admits; the shift `>>> var6` masks its operand and throws nothing on the way.
  Owner decision pending: keep it as liveness on that reversed-progress reading, or
  refactor the window walk onto an array index so a reversed step fails on its
  first pass.
- `Ed25519Util.scalarMultBase`, `RemoveConditionalMutator_ORDER_ELSE` on
  `var6 < 0`: the ladder loop's only `return` is forced unreachable.

## encoding

Mutator set: plain `STRONGER`. Both `EXPERIMENTAL_BIG_INTEGER` (2026-07-21) and
`EXPERIMENTAL_NAKED_RECEIVER` (2026-07-22) fired nothing here —
`HARDENING_NOTES.md` §Mutator-set trials.

### Accepted families

**Allocation-size only** — baseline label `# allocation size`. Members:
`Base58.decode`, `MathMutator`, one in each of its six source variants. The
limb-array sizing `limbsLength(to - i)` → `to + i` only over-allocates, and the
`used` count returned by `toLimbs` bounds what is read back out. Oracle:
decode's output, which these mutants leave byte-identical — nothing reads a
limb past `used`. Invalidated if decode ever reads limbs beyond `used`, or the
sizing change can under-allocate. (`limbsLength` itself is no longer in this
family; see History.)

**Slow-path / alternate-path routing** — baseline label `# slow path routing`
(also used by `token2022`, argued there separately). Within the modeled
contract, both paths are result-identical and the mutant only changes which
one runs:

- `Base58.toLimbs`, `RemoveConditionalMutator_ORDER_ELSE` ×2 in each of its
  three source variants: disabling the 5-digit chunk batching
  (`numDigits < 5` / `i < to` → false) degrades to per-digit `mulAdd` with
  `POW_58[1]`; same accumulated limbs, more calls.
- `Jex.decodePrimIterChecked`, `RemoveConditionalMutator_EQUAL_ELSE`, both
  variants: removing the `len == 0` fast path routes empty input through the
  general loop, which produces the same empty result.

Oracle: the result itself, which only a call count could tell apart.
Invalidated if the batched and per-digit paths can accumulate different limbs,
or the general loop stops returning the same empty result for empty input.

**Surplus zero strip** — baseline label `# surplus zero strip`. Members:
`Base58.encode`, `Base58.mutableEncode` and `Base58.continueMutableEncode`,
`RemoveConditionalMutator_EQUAL_ELSE` and `RemoveConditionalMutator_ORDER_ELSE`
each: the surplus-`ENCODED_ZERO` strip loop after digit emission, and its
boundary variant. No entry point produces surplus zero digits for it to
remove; it is retained as defense. Oracle: the BigInteger-reference
differential and the Bitcoin Core vectors pass with the strip disabled.
Invalidated if any entry point can emit surplus leading zero digits.
`beginMutableEncode` is not in this family: it has no strip loop and no
accepted rows, and `Base58Tests.testMutableEncode` asserts the count of
characters each call emits, which kills every mutant on its chunk bound.

### Audited timeouts

- `Jex.isValid`, `IncrementsMutator` on the second `index++` → `index--`: the
  do-while cursor oscillates over the same valid digit pair and never reaches
  `len`. Since 2026-10-07 the member is killed deterministically rather than by the
  watchdog: `JexTests.isValidReadsAValidInputInBoundedPasses` validates through a
  `CharSequence` that fails on the first `charAt` past twice the input length, a
  call budget any forward pass stays under and the oscillating cursor exhausts in a
  few passes. The membership line stays until the verify's three-run quiet notice
  and the gate confirmation (`HARDENING.md`, admissible liveness members keep
  earning themselves); a timeout reappearing there reads as audited liveness.

## meta

Mutator set: `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` — the latter fired in the
2026-07-22 trial (`HARDENING_NOTES.md` §Mutator-set trials).

### Accepted families

**Identity short circuit in equals** — baseline label `# equals identity`.
Members: `RemoveConditionalMutator_EQUAL_IF` on the `this == o ||` prefix of
`equals` in `AccountMetaFeePayer`, `AccountMetaInvoked`,
`AccountMetaInvokedAndWrite`, `AccountMetaReadOnly`,
`AccountMetaReadOnlySigner`, `AccountMetaSignerWriter` and `AccountMetaWrite`.
Removing the reference check falls through to the class-and-key comparison,
which returns the same answer for every input. It is a fast path, not a
branch. Oracle: that comparison, which is reflexive for every instance.
Invalidated if any of these `equals` bodies stops being reflexive.

**Redundant or equal-returning branches in merge** — baseline label
`# merge redundant`. Members: `RemoveConditionalMutator_EQUAL_ELSE` in two
sub-cases:

- the `accountMeta.feePayer()` guard in `AccountMetaWrite.merge` and
  `AccountMetaReadOnlySigner.merge` is subsumed by the `signer()` branch below
  it, because a fee payer is also a signer *and* writable, so the ternary there
  returns the same argument the guard would have.
- the `accountMeta.write()` ternaries — two in `AccountMetaWrite.merge`, one
  each in `AccountMetaReadOnlySigner.merge` and `AccountMetaInvoked.merge`:
  forcing the else builds a fresh `AccountMetaSignerWriter` /
  `AccountMetaInvokedAndWrite` with the same key instead of returning the
  argument. Equal by `equals`, just not the same instance. Killable only by
  asserting identity, which the API does not promise.

Invalidated if a fee payer can be other than a writable signer, or if `merge`
comes to promise the identity of its result.

**hashCode arithmetic** — baseline label `# hashcode arithmetic`. Members:
`MathMutator` on the `31 * result + 1` mixing in `AccountMetaInvoked`,
`AccountMetaInvokedAndWrite` (two siblings), `AccountMetaReadOnlySigner` and
`AccountMetaWrite`. The surviving mutations still produce hashes that are
distinct across the privilege types, which is the only property that matters.
Oracle: `hashCodeDistinguishesPrivileges` asserts it. Killing them would mean
pinning exact hash values and freezing an implementation detail. Invalidated
if exact hash values become part of the contract.

### Behaviour notes (not accepted mutants)

`AccountMetaFeePayer` and `AccountMetaSignerWriter` hash identically — the
scheme folds in `(signer, write, invoked)` and a fee payer shares that triple.
Legal, since `equals` separates them by class and unequal objects may collide.
Asserted in `hashCodeDistinguishesPrivileges` so it stays deliberate.

Six further `merge` cells lose a privilege where `invoked` meets `signer`
(there is no type for an invoked signer). Those are unreachable — a program
account cannot sign — and are pinned in
`AccountMetaTests.mergeLosesPrivilegesOnlyWhereInvokedMeetsSigner` rather than
here, because they are a behaviour gap rather than an unkillable mutant.

## token2022

Mutator set: plain `STRONGER`. Both `EXPERIMENTAL_BIG_INTEGER` (2026-07-21) and
`EXPERIMENTAL_NAKED_RECEIVER` (2026-07-22) fired nothing here —
`HARDENING_NOTES.md` §Mutator-set trials.

### Accepted families

**Hash-mixing operator swaps** — baseline label `# hash mixing` (also used by
`tx`, argued there separately). Members: `MathMutator` in the `hashCode` of
`ConfidentialTransferAccount`, `ConfidentialTransferFeeConfig`,
`ConfidentialMintBurn` and `UnknownTokenExtension` — one line-less key per
class, one sibling row per mixed field. `31 * h + x` → `31 * h - x` still
yields a deterministic, equals-compatible hash; only exact-hash-value
assertions could kill these, and hash values are not part of the contract.
Oracle: equality consistency — equal extensions still hash equal. Invalidated
if exact hash values become part of the contract.

**Slow-path / alternate-path routing** — baseline label `# slow path routing`.
Member: `TokenMetadata.read`, `RemoveConditionalMutator_EQUAL_ELSE`. Removing
the `numExtras == 0` → `Map.of()` fast path builds an empty `LinkedHashMap` and
wraps it as unmodifiable. For parsed, protocol-valid metadata, whose Borsh
string keys are non-null, both paths expose the same empty entries,
iteration, map equality/hash code and mutation rejection, and produce the same
TokenMetadata Borsh length and bytes. The map implementations differ for null
queries and Java object serialization; neither behavior is part of the
modeled TokenMetadata wire domain. Invalidated if either of those becomes part
of the contract, or the parser can admit a null key.

**Dead final cursor advance** — baseline label `# dead cursor advance`.
Member: `ConfidentialMintBurn.read`, `MathMutator` on the last
`i += pendingBurn.length` before the return. It is a dead store — nothing reads
`i` afterwards. Kept for symmetry with the preceding field reads; refactoring
it away would remove the mutant. Invalidated if a read is ever added after
that advance.

## tx

Targets `tx` and `accounts.lookup`, including the v1 (SIMD-0385) classes.
Mutator set: `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` — the latter fired in the
2026-07-22 trial (`HARDENING_NOTES.md` §Mutator-set trials).

### Hashing and defensive code

**Instruction hash mixing** — baseline label `# hash mixing`. Members:
`InstructionRecord.hashCode`, `MathMutator` ×2. The reviewed mutants replace
addition with subtraction in the valid-span length fold and the per-byte fold,
respectively. Both retain a deterministic, content-dependent hash: equal
programs, account lists, lengths, and ordered logical bytes still produce
equal hashes, independent of backing-array identity or offset. Exact
valid-span hash values are not a public contract. Oracle: the collection and
representative-field tests in `InstructionBuildingTests` protect equality
consistency and useful dispersion; pinning exact integers merely to kill these
sign changes would overspecify them. Invalidated if exact valid-span hash
values become part of the contract. This acceptance does not cover arithmetic
in the invalid-span fallback, whose former generated-record hash is preserved
and checked independently.

**Dead defensive code** — baseline label `# dead defensive`:

- `TxBuilderImpl.MERGE_ACCOUNT_META` (`lambda$static$0`),
  `RemoveConditionalMutator_EQUAL_ELSE`: `Map.merge` never invokes its
  remapping function with a null existing value, so bypassing `prev == null`
  cannot change a valid map merge. Invalidated if the function is ever called
  other than as a `Map.merge` remapping function.
- `AccountIndexLookupTableView.compareTo`, `RemoveConditionalMutator_EQUAL_ELSE`:
  a view's constructor admits only a complete 32-byte key, so forcing the
  view-specific branch off compares the same key bytes through `toByteArray`.
  The cross-table comparison is pinned by
  `AccountIndexLookupTableTests.viewCompareToReadsTheOtherViewsBackingTable`.
  Invalidated if the view constructor ever admits a partial key.

### Offsets and signature blocks

**Config value offset codomain excludes 0** — baseline label
`# config value offset codomain excludes 0`. Members:
`ConditionalsBoundaryMutator` in `V1Transaction.configValueOffset`,
`.createTransaction` ×4 and `.setPriorityFeeLamportsFromComputeUnitPrice`, and
in `V1TransactionSkeleton.priorityFeeLamports` / `.computeUnitLimit` /
`.accountDataSizeLimit` / `.heapSize`. Every one of these tests an offset
returned by `V1TransactionSkeleton.configValueOffset`, whose only outcomes are
the literal `-1` when the mask bits are clear, or
`V1_ACCOUNTS_OFFSET + (numAddresses << 5) + (bitCount << 2)`. The base offset
is 42, and the unsigned address count and bit count add non-negative
displacements, so a present value's offset is at least 42. `< 0` and `<= 0`
(likewise `>= 0` and `> 0`) differ only at zero, which neither outcome can
produce. Both arms of each guard are exercised — the mutants are the boundary
alone. Invalidated if the offset computation can ever yield 0.

**Signature offset codomain excludes 0** — baseline label
`# signature offset codomain excludes 0`. Members:
`BaseTransaction.signedIdOffset` and `BaseTransaction.toString`,
`ConditionalsBoundaryMutator`. The same argument one level up:
`feePayerSignatureOffset` returns `-1`, or the legacy literal `1`, or a v1
offset that `V1TransactionSkeleton.requireSignatureBlockOffset` has already
forced to be `>= V1_ACCOUNTS_OFFSET`. Never 0. Invalidated if
`feePayerSignatureOffset` gains an outcome that can be 0.

**V1 signer count** — baseline label `# v1 signer count remains nonzero`.
Member: `BaseTransaction.feePayerSignatureOffset`, `MathMutator`. The v1
discriminator has already established `data[1] != 0`. Replacing the
sign-count mask `& 0xFF` with `| 0xFF` changes the numeric value but preserves
its only later use, `numSigners != 0`. Signature-block validation reads its own
count from the buffer. The read executes, and the zero-count route remains
unreachable. Invalidated if the masked count gains another use, or the
discriminator stops requiring `data[1] != 0`.

**The signature-block invariant.** A v1 message always carries at least one
64-byte signature: `V1Transaction.isV1` requires `data[1] != 0`, and
`deserialize` additionally rejects `num_readonly_signed >= num_required_signatures`,
so `signaturesOffset = data.length - numSignatures * 64 <= data.length - 64 < data.length`.
Three acceptances rest on it, and each is invalidated if a v1 message can carry
zero signatures:

- baseline label `# a v1 buffer always carries signatures`:
  `V1TransactionSkeleton.signaturesOffset`, `ConditionalsBoundaryMutator`,
  changing `headerBlockEnd > data.length` to `>=`. At equality, the existing
  `signaturesOffset < headerBlockEnd` operand already rejects the message,
  because the implied signature offset is below `data.length`. The same
  `IllegalStateException` and message are reached either way. Oracle: complete
  signature layouts and truncated-header rejection are covered by
  `aTransactionWhoseInstructionsHaveNoAccountsOrDataSitsExactlyOnTheHeaderBound`
  and `aPayloadTruncatedInsideTheInstructionHeadersIsDiagnosed`.
- the `RemoveConditionalMutator_ORDER_IF` sibling on the same condition, under
  `# redundant guard operand` (argued with that family below).
- baseline label `# malformed message still rejected`:
  `V1TransactionSkeleton.messageEnd`, `ConditionalsBoundaryMutator` on the
  header-block bound, also `>` → `>=`, but this helper reads untrusted raw
  bytes. A malformed buffer can end exactly at its header-block end. The
  original returns an end at or beyond the buffer length; the mutant returns
  `-1`. `requireSignatureBlockOffset` rejects both because neither can equal
  the implied signature offset, which is below the buffer length for a nonzero
  signature count. The diagnostic's rendered message-end offset differs, while
  the documented `IllegalArgumentException` rejection and every valid
  transaction's result are unchanged. This acceptance covers that
  diagnostic-only difference; it does not claim the boundary is unreachable.

### Fee conversion and compound guards

**Short circuit already returned** — baseline label
`# short circuit already returned`:

- `TxBuilder.computeUnitPriceToPriorityFeeLamports` and
  `TransactionRecord.priorityFeeLamportsToComputeUnitPrice`,
  `ConditionalsBoundaryMutator` in each: each negative-price/fee guard differs
  from `<= 0` only at zero, already handled by the preceding fast return.

Invalidated if the preceding fast return stops handling zero. The zero-limit operand in
`TxBuilder.computeUnitPriceToPriorityFeeLamports` is not equivalent: skipping
it reaches division by zero in the overflow guard, and
`testAZeroComputeUnitLimitShortCircuitsBeforeTheOverflowGuardDivides` kills it.

**Redundant guard operand** — baseline label `# redundant guard operand` — the
mutant disables one operand of a compound condition whose remaining path
reaches the identical result:

- `TxBuilder.computeUnitPriceToPriorityFeeLamports`,
  `RemoveConditionalMutator_EQUAL_ELSE` on the `microLamportsPerComputeUnit == 0`
  half of the fast return. Skipping it for a zero price falls through the
  overflow guard (`0 < 0` and `0 > (Long.MAX_VALUE - 999_999) / limit` are both
  false) into the general arithmetic, which computes
  `(0 * limit + 999_999) / 1_000_000` — integer division yielding the same 0.
  The **other** half of that same condition, `cappedComputeUnitLimit == 0`, is
  *not* equivalent: skipping it divides by zero in the overflow guard, and it
  is killed by `testAZeroComputeUnitLimitShortCircuitsBeforeTheOverflowGuardDivides`.
  Sibling mutants on one condition are not interchangeable — this pair is the
  worked example. Invalidated if the rounding constant or divisor changes so a
  zero price no longer computes 0.
- `V1TransactionSkeleton.signaturesOffset`, `RemoveConditionalMutator_ORDER_IF`
  on the `headerBlockEnd > data.length` half of
  `headerBlockEnd > data.length || signaturesOffset < headerBlockEnd`. Removing
  that operand's early exit leaves the second one, which is strictly stronger
  by the signature-block invariant above: whenever `headerBlockEnd > data.length`
  holds, `headerBlockEnd > signaturesOffset` follows, so the second operand
  catches every input the first would have.
- `TransactionRecord.priorityFeeLamportsToComputeUnitPrice`,
  `RemoveConditionalMutator_EQUAL_ELSE` on the `priorityFeeLamports == 0` half of
  its fast return: if the limit is zero the other operand still returns
  immediately; otherwise bypassing the zero-fee shortcut computes
  `(limit - 1) / limit`, which is also zero for the positive capped limit.
  Invalidated if the limit can reach that arithmetic uncapped or non-positive.

### Routing and compaction

**Result-identical routing** — baseline label `# result identical routing`:

- `Transaction.createTx`, `RemoveConditionalMutator_EQUAL_ELSE`, in the overload
  taking `LookupTableAccountMeta[]`: forcing the `len == 1` compaction shortcut
  false routes a one-element displacement through `System.arraycopy`. It copies
  the same account into the same destination slot before the common front
  assignment. This argument covers that singleton displacement, not the removed
  single-table dispatch or filtered-table serialization shortcuts.
- `TransactionSkeleton.deserializeSkeleton`, `ConditionalsBoundaryMutator`, on
  `numLookupTables > 0`: at zero tables `>` → `>=` builds an empty
  `PublicKey[]` where the guard returns the shared
  `BaseTransactionSkeleton.NO_TABLES`. Both expose zero loaded accounts and the
  same remaining transaction fields; the constant avoids allocation.

Invalidated if the one-element copy can target a different slot, or a caller
can tell `NO_TABLES` from a fresh empty array. The singleton-null case in
`InstructionRecord.extraAccounts` is not accepted (see History).

**No-op displacement boundaries** — baseline label `# displacement boundary`:

- `Transaction.createTx`, `ConditionalsBoundaryMutator` ×2, in the single-table
  and `LookupTableAccountMeta[]` overloads: changing `i > numIncludedAccounts`
  to `>=` adds only the equality case. There `len` is zero, so the copy moves
  no elements and the front assignment writes the account back to its existing
  slot.
- `Transaction.createTx`, `RemoveConditionalMutator_EQUAL_ELSE`, in the
  single-table overload: bypassing `len == 1` performs the same one-element
  move with `System.arraycopy` before the common front assignment.

Invalidated if the displacement stops ending in that common front assignment.

**Array identity only** — baseline label `# array identity only`. Members:
`RemoveConditionalMutator_EQUAL_ELSE` in
`TransactionSkeletonImpl.filterInstructions` /
`.filterInstructionsWithoutAccounts` and their `V1TransactionSkeleton` twins,
on the `d == numInstructions ? instructions : Arrays.copyOfRange(instructions, 0, d)`
tail. Forcing the equality false always takes the copy arm, which at
`d == numInstructions` copies the full range — same component type, length and
element references. `instructions` is a local that is never published, so the
only difference is an array identity no caller can hold. Invalidated if that
local array is ever published before the tail. The `EQUAL_IF` siblings, which
would return the null-padded array when `d < numInstructions`, are genuinely
observable and are killed. The filter-copy mutants in these methods are
covered by this family only.

**Legacy parse routes converge** — baseline label
`# legacy parse routes converge`. Members:
`TransactionSkeletonImpl.parseAccounts`, `RemoveConditionalMutator_EQUAL_ELSE`
×2. `deserializeSkeleton`'s legacy branch always supplies empty invoked
indexes and no lookup tables with `numAccounts == numIncludedAccounts`, so the
versioned parse degenerates to the legacy one — same length, same metas,
`binarySearch` against an empty array always missing. Oracle:
`testLegacyParseAccountsIgnoresLookupTables`. Invalidated if the legacy branch
can supply invoked indexes or tables.

**Both arms write the same bytes** — baseline label
`# both arms write the same bytes`. Member: `BaseTransaction.setBlockHash`,
`RemoveConditionalMutator_EQUAL_ELSE`. For a built-in transaction, bypassing
the direct copy routes through its final `setRecentBlockHash`, copying the same
32 bytes to the same destination offset. External implementations already use
the setter route. Invalidated if `setRecentBlockHash` writes anything else.

**Same rejection** — baseline label `# mergeAccounts rejects it identically`.
Member: `TxBuilderImpl.createTransaction`, `RemoveConditionalMutator_EQUAL_ELSE`.
Bypassing the empty-instruction check reaches `mergeAccounts`, which rejects
the same empty input with the same exception class and message. Invalidated if
either rejection's class or message changes independently of the other.

**Empty prepend** — baseline label
`# prepend path is a no-op when nothing is prepended`. Member:
`TransactionRecord.setComputeBudgetValues`, `RemoveConditionalMutator_EQUAL_ELSE`.
When both requested values have been found, bypassing `numToPrepend == 0`
allocates an equally sized local array, adds neither prefix instruction, and
copies all updated instructions into it at offset zero. Both routes rebuild
from the same ordered instructions and carry over the same blockhash. The copy
executes; no zero-iteration loop is involved. Invalidated if the prepend route
starts doing anything beyond that copy when nothing is prepended.

### Audited timeouts

None. The one member this suite carried, `AddressLookupTableOverlay.lambda$keysToString$1`
`PrimitiveReturnsMutator`, left with the 2026-10-07 refactor recorded under History.

## accounts

Mutator set: `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` — the latter fired in the
2026-10-07 trial, run after the suite was registered on 2026-08-04 without one
(`HARDENING_NOTES.md` §Mutator-set trials).

### Debt

**Seeded debt** — every row is `# untriaged`. The suite was registered on
2026-08-04 to close `mutationOwnershipAudit` by targeting rather than declining,
and its baseline was seeded from the full unkilled population; the 2026-10-07
campaign (every `SolanaAccountsBuilder` setter, the ranged and `char[]`/ASCII
`PublicKey` overloads, key-pair validation and generation in `Signer`) killed the
bulk and the unscoped prune retired those rows. What remains, still debt and not
equivalence claims: the `Signer.fromProperties` branches (prefix, key form, AAD
and KDF selection, including its two `NakedReceiverMutator` rows), the
`Signer.encryptKey` branches, the `validateKeyPair` calls on pairs the method
just built, the `PublicKeyBytes` buffer and equality arms, `PublicKey.readPubKey`,
`PublicKey.l`, `PublicKey.createProgramAddress`, `KeyPairSigner.createDedicatedSigner`
and `ProgramDerivedAddress.createPDA`. The `NO_COVERAGE` rows among them are
untested lines; each row remains active matching capacity until triage kills,
refactors or argues it.

## sysvar

Mutator set: plain `STRONGER`: `EXPERIMENTAL_NAKED_RECEIVER` fired nothing in the
2026-10-07 trial (`HARDENING_NOTES.md` §Mutator-set trials).

### Debt

None: the suite has detected its whole population since 2026-10-07, when
`SysvarTests` gained a write round trip at a zero and a non-zero offset for every
sysvar, each with distinct non-zero field values against an independently built
little-endian wire form, plus the address-taking `read` overloads and `l()`. The
seeded `# untriaged` rows (every `EpochRewards.write` sibling among them, which the
licensed population had stopped observing) left through the unscoped prune.

## pbkdf

Mutator set: `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` — the latter fired in the
2026-10-07 trial, run after the suite was registered on 2026-08-04 without one
(`HARDENING_NOTES.md` §Mutator-set trials).

### Accepted families

The seeded `# untriaged` rows left through the unscoped prune on 2026-10-07 after
the campaign covered the properties and JSON forms, the password guard, the salt
and IV draws, the key wipes and `RawSecretKey`; what remains is argued here.

- **`# unobservable wipe`** — `Argon2id.derive` `VoidMethodCallMutator` (the UTF-8
  password bytes), `PBKDF2WithHmacSHA512.derive` `VoidMethodCallMutator`
  (`PBEKeySpec.clearPassword`, on the spec's own copy of the password) and
  `PBKDFEncryption.toUtf8Bytes` `VoidMethodCallMutator` (the encoder's buffer).
  - Reason: each zeroes a local or a JCE-internal copy after its last use; the
    array never escapes the method, so no caller, test or collaborator can read it
    afterwards. Removing the wipe changes nothing any test can observe.
  - Oracle: none executable, by construction; the property is reviewed by reading.
    The wipes a caller can observe are killed instead: `encryptWipesTheDerivedKey`
    and `passwordDecryptWipesTheDerivedKeyWhenAuthenticationFails` in
    `PBKDFEncryptionTest` watch the key array a recording derivation handed out.
  - Invalidated if: the buffer is returned, retained or shared, at which point the
    wipe becomes observable and must be tested.
- **`# wipe preceded by the JCE`** — `PBKDFEncryption.decrypt` `VoidMethodCallMutator`,
  the `finally` wipe of the key derived from a password.
  - Reason: `RawSecretKey.getEncoded` returns the key array itself, and SunJCE zeroes
    that array while initialising AES/GCM for decryption (JDK 25.0.2, measured
    2026-10-07: at `init`, in decrypt mode only). The wipe runs on an array that is
    already zero. Encryption leaves the array intact, which is why the matching
    `encrypt` wipe is killed by `encryptWipesTheDerivedKey`.
  - Oracle: `theJceZeroesARawKeyArrayAtDecryptInit` in `PBKDFEncryptionTest` pins the
    JDK behaviour the argument rests on, with a key of its own, not `RawSecretKey`.
  - Invalidated if: that test fails (a JDK that stops zeroing), or `RawSecretKey`
    starts copying in `getEncoded`.
- **`# empty AAD update is a no-op`** — `PBKDFEncryption.encrypt` and
  `PBKDFEncryption.decrypt` `ConditionalsBoundaryMutator` on `aad.length > 0`.
  - Reason: the boundary change routes an empty array into `Cipher.updateAAD`, which
    the JCE treats exactly as no associated data.
  - Oracle: `emptyAadIsTheSameAsNoAad` in `PBKDFEncryptionTest` seals with each of
    null and empty and opens with each of the other.
  - Invalidated if: that test fails (a provider that binds an empty AAD).
- **`# blank prefix strips to empty`** — `EncryptionEnvelope.toPropertiesString`
  `RemoveConditionalMutator_EQUAL_ELSE` on the `isBlank` half of the prefix guard.
  - Reason: forcing the blank check false sends a blank prefix through `strip()`,
    which yields the empty string the guard would have chosen.
  - Oracle: `propertiesTextIsExactlyThePrefixLineTheKdfLinesAndTheEncodedFields` in
    `EncryptionEnvelopePropertiesTests` asserts a blank prefix writes byte for byte
    the text of no prefix.
  - Invalidated if: the blank branch writes anything.
- **`# builder default restated`** — `Argon2id.derive` `NakedReceiverMutator` on
  `withVersion(ARGON2_VERSION_13)`.
  - Reason: the Bouncy Castle `Argon2Parameters.Builder` is constructed with version
    0x13, so dropping the explicit call leaves the same parameters. The sibling
    calls (`withSalt`, `withMemoryAsKB`, `withParallelism`, `withIterations`) are
    killed because each default differs from what the vector or the parameter test
    passes.
  - Oracle: `argon2idMatchesTheReferenceVector` in `Argon2idVectorTests` pins the
    version 0x13 output of the reference implementation's vector.
  - Invalidated if: that vector fails (a builder whose default is another version),
    or the generator is replaced.

## vanity

Mutator set: `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` — the latter fired in the
2026-07-22 trial (`HARDENING_NOTES.md` §Mutator-set trials). Targets the whole
package since 2026-08-04 (`HARDENING_NOTES.md` §pitestVanity records the
retired `Subsequence*` allowlist).

### Debt

Every accepted row is `# untriaged`. Seeded from the full unkilled population when
the suite was widened on 2026-08-04 to close `mutationOwnershipAudit` by targeting
rather than declining; the 2026-10-07 campaign drove the generator API end to end
(`VanityAddressGeneratorTests` on an inline executor and seeded factory,
`ConcurrentVanityAddressGeneratorTests` on a recording queue), pinned the worker
accessors, the interrupt exits and the draw budget, and the unscoped prune retired
the rows those tests killed. What remains, still debt and not equivalence claims:
the `BaseMaskWorker.queueResult` self-checks (signature verification and the key
file branches, which `KeyFileRoundTripTests` reaches through the file formats
rather than the worker), the `foundLimitOrInterrupted` found-count boundary, and in
`MaskWorker.run` the packed-offset unpacking, the `checkFound` poll branch and the
`clearSecrets` call. Each remains active matching capacity until triage kills,
refactors or argues it.

### Audited timeouts

Every mask worker's search is a `for (;;)` with exactly **two** exits: "found
enough" (`foundHitLimitOrInterrupted` / `foundLimitOrInterrupted`) and "cap
reached" (`searchExhausted(attempts)`, the bounded-attempts seam whose javadoc
names tests as its reason for existing). Every member listed below disables
the cap itself — breaking either the counter that feeds it or the branch that
consumes it. Each was observed `TIMED_OUT` in the 2026-09-05 and 2026-09-25
certification runs, under an argument that the cap was the only bound and so
nothing but the watchdog could observe them. That argument was wrong: every
attempt is exactly one `SecureRandom.nextBytes` draw, a collaborator the tests
supply, so a draw budget on the test's `FixedSeedSecureRandom` counts attempts
from outside the loop. Since 2026-10-07 the three unsatisfiable searches in
`MaskWorkerTests` run on a budget of twice their cap, working code never reaches
it, and every member below fails on the budget's `AssertionError` within a few
hundred milliseconds instead of timing out.

- `MaskWorker.run` and `BeginsWithMaskWorker.run`, `MathMutator`
  (`++attempts` → `--attempts`): the attempt counter runs backwards, so
  `searchExhausted(attempts)` never becomes true; the draw budget fires at the
  cap plus one.
- `MaskWorker.run` and `BeginsWithMaskWorker.run`,
  `RemoveConditionalMutator_EQUAL_ELSE`: the branch that acts on the exhausted
  cap is forced the way that keeps the loop going; the draw budget fires at the
  same point.

The membership lines stay until the verify's three-run quiet notice and the gate
confirmation (`HARDENING.md`, admissible liveness members keep earning
themselves); a timeout reappearing at one of them reads as audited liveness and
must be argued afresh, because the budget was supposed to make it impossible.

The fixture bounds, recorded because the plugin asks whether a claimed bound
can fail first: `MaskWorkerTests` drive every satisfiable search with a finite
`MAX_SEARCHES` (10,000 attempts; the worst real search on its fixed seed takes
529, as recorded in `8eeb5d9` and in the comment on `MAX_SEARCHES`, so the bound
is ~19x headroom and never fires on working code), and the unsatisfiable
searches on a cap of 500 with a draw budget of 1,000. Those three tests also
carry a JUnit `@Timeout(60)`, which interrupts the test thread; the loop polls
the flag every `checkFound` draws, so an interrupted search ends on the found
exit. Under PIT that timeout never governs: the watchdog is `2.0 x` the test's
recorded duration `+ 1500 ms`, under two seconds for these tests, so a mutant that
escaped the budget would be reported `TIMED_OUT` by the watchdog long before
JUnit's sixty seconds, exactly the audited state recorded above.

## primitives

Mutator set: plain `STRONGER`: `EXPERIMENTAL_NAKED_RECEIVER` fired nothing in the
2026-10-07 trial (`HARDENING_NOTES.md` §Mutator-set trials).

No accepted mutants, so there is no `primitives-accepted.csv` and no
provenance pair.

## History (not evidence)

- 2026-10-07, accounts: the four `PublicKey.verifySignature` rows labelled
  `# removed signature overload pending prune` since `666c164` (the tombstones of
  the String-signature overloads removed in `0112d69`) left through the unscoped
  prune. Their keys were blocked by live `NO_COVERAGE` siblings in two private
  `verifySignature` overloads that nothing called; those overloads were deleted the
  same day (uncalled private code, not published surface), the ranged overload's
  siblings were killed by `rangedVerificationAcceptsOnlyTheSignedRangeAndAnUntamperedSignature`,
  and the keys emptied. The "licensed-kill removal rule" the old paragraph deferred
  to is about ArcMutate toolchain absence, not deleted source, and never applied.

- 2026-10-07, tx: `AddressLookupTableOverlay.keysToString` was refactored from an
  `IntStream.iterate` offset cursor to an `IntStream.range` over the account count,
  the casebook's "refactor it out of existence" outcome for a timeout of the
  heap-race shape (a stalled cursor that appends the same key until the watchdog).
  Every mutant of the counted range is finite. The fresh history-free run that
  followed omitted the old `lambda$keysToString$1` `PrimitiveReturnsMutator`
  coordinate, so its membership line was removed by hand under the stale-row rule
  and, as the suite's only member, `tx-timeouts.csv` with it.

Retired arguments kept because each teaches something a reader might otherwise
repeat. Nothing here supports a current row or audited timeout; run counts,
prune procedures and certification receipts are in git and
`HARDENING_NOTES.md`.

**`Base58.limbsLength` — resource, not liveness (killed 2026-08-05).** Its
mutants inflated the bit-bound estimate (`/ 1_000` → `* 1_000`, `>> 5` →
`<< 5`), returning identical bytes for up to a million times the memory; one
sat in the audited timeout set and flapped `SURVIVED`↔`TIMED_OUT` between a
busy and an idle machine. The allocation-size family's old reason — "decode has no
zero-allocation contract" — asked the wrong question: there *is* a bit bound
(never under-allocate, round up only to the limb boundary), and it is
assertable as a value. `limbsLength` became package-private and
`Base58LimbBoundTests` checks it against an exact `BigInteger` oracle — the
minimum limbs holding `58^d - 1` — across every digit count from 1 to 512, in
both directions. All six mutants died; the value oracle cannot flap, needs no
warm-up, and killed one more mutant than a measured allocation bound did.

**Vanity output in the wrong layer (2026-07-20).** While the suite still
allowlisted `Subsequence*`, it was briefly seeded with mutants on the
"Character options:" table that `Subsequence.create` printed to `System.out`.
Nothing asserts stdout, so nothing could kill them. Rather than accept that,
the block moved out of the library the same day: `Subsequence.charOptionsTable()`
returns the table as a string, `software.sava.vanity.Entrypoint` prints it, and
every mutant died. A cluster of unkillable mutants around output or logging
usually means the side effect is in the wrong layer, not that the mutants are
equivalent.

**Four vanity timeouts that were unexercised seams (retired 2026-08-08).**
Four members were once listed under the same "the cap is the only exit left"
argument as the current ones, and that argument was wrong for them. Their
mutants broke the *match* path instead of the cap — dropping key-pair
generation in `MaskWorker.run` and `BaseMaskWorker.generateKeyPair`
(`VoidMethodCallMutator`) so the same bytes were retested forever, forcing
`BaseMaskWorker.queueResult`'s result-queuing predicate false
(`RemoveConditionalMutator_EQUAL_ELSE`), and corrupting the resumed-encode
offset in `MaskWorker.run` (`MathMutator`, `& 0xFFFF` → `| 0xFFFF`) so a real
tail match failed its prefix check. Each still *reached* a working cap. They
timed out only because every satisfiable test in `MaskWorkerTests` passed
`Long.MAX_VALUE` for `maxSearches`. Giving those tests the finite
`MAX_SEARCHES` converted all four into ordinary assertion failures, and they
are `KILLED` with named covering tests.

**Two stale `ORDER_IF` timeout members (removed 2026-09-25).**
`Ed25519Util.pack25519` (`RemoveConditionalMutator_ORDER_IF` on the `j < 2`
reduction-pass loop) and `SubsequenceRecord.formatCharOptions` (the known
`KILLED`↔`TIMED_OUT` flapper in the 2026-07-22 mode comparison) were removed by
hand on 2026-09-25 after one fresh full history-free run with valid committed
provenance reported both coordinates absent from the population. No code
moved: the licensed mutator set generates `ORDER_ELSE` and
`ConditionalsBoundary` at those sites but no `ORDER_IF`. Should an `ORDER_IF`
timeout reappear at either site it reads as unaudited and has to be argued
afresh.

**A mis-filed family (2026-09-18, killed 2026-09-20).** `Base58.beginMutableEncode`'s
chunk-bound survivors sat in the surplus-zero-strip family, whose argument is about
a strip loop that method does not have. They were re-filed as their own
`encode chunk split` family, whose paragraph named the assertion that would
kill them; `Base58Tests.testMutableEncode` gained it (the first call emits
exactly `min(maxLen, digits)` characters) and the family left the baseline.

**A boundary refactored out rather than accepted (2026-09-06).** The
short-tail guard inside `Token2022.parseExtensions`' loop made the loop's own
`i < data.length` boundary redundant and therefore equivalent; folding the
guard into the loop condition (`data.length - i >= Short.BYTES`) restored a
killable distinction.

**Retired `tx` compatibility acceptances — do not re-accept (2026-09-05).**
These rows were retired because the licensed mutants were observed killed, not
merely absent; the killing tests pin behaviour already present in 25.10.0:

- `Transaction.createTx(..., AccountMeta[], LookupTableAccountMeta[])`,
  `RemoveConditionalMutator_EQUAL_IF` and `VoidMethodCallMutator`: forcing the
  singleton compaction arm or removing the arraycopy changes the caller's array
  even though the serialized transaction stays identical. The array tail is
  caller-visible, so the former consumed-tail argument was insufficient.
  `TransactionFactoryTests.multiTableCompactionPreservesCallerArrayEntriesAndRelativeOrder`
  pins the existing stable partition: message accounts first, lookup accounts
  afterward, retaining every entry and relative order within both groups. The
  lookup tail follows the input array order, not the table order on the wire.
- `InstructionRecord.extraAccounts(List)`, `RemoveConditionalMutator_EQUAL_ELSE`:
  bypassing the singleton shortcut appends a null account where the current
  method ignores it.
  `InstructionBuildingTests.extraAccountsRetainsSizeDependentNullHandlingForCompatibility`
  pins that published asymmetry, including retention of nulls in larger lists.
- `InstructionRecord.toString`, `RemoveConditionalMutator_EQUAL_IF` and
  `ConditionalsBoundaryMutator`: removing the null-data guard or widening the
  positive-length check can make diagnostic rendering throw for inputs it
  renders as empty.
  `InstructionBuildingTests.toStringRendersNullOrNonpositiveLengthDataWithoutReadingTheSpan`
  covers null data with a positive length and a zero-length span with an
  invalid offset. Diagnostic rendering does not make either input valid for
  serialization.

**`TransactionSkeletonImpl.invokedProgramAccount` — not result-identical
routing (killed by the v1 merge).** Triage was right that the branches of its
`RemoveConditionalMutator_EQUAL_ELSE` are not result-identical, so a
`# result identical routing` acceptance would be wrong there: rebuilding an
already-invoked meta through `createInvoked` preserves `AccountMetaInvoked`
(whose `equals` is value-based on the public key, which is why the legacy
assertions could not see it) but downgrades an `AccountMetaInvokedAndWrite` to
read-only. The distinguishing fixture is a
program account that is also writable;
`V1FilterBoundaryTests.anInvokedAndWrittenProgramKeepsBothFlagsThroughParseInstructions`
pins it. Do not accept it as routing.
