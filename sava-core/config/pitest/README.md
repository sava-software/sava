# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,mutator,STATUS` — line numbers are metadata, carried as
a trailing `# line N` tag every refresh rewrites, so editing above a mutated
method churns nothing. Full policy — the three legal outcomes for a new
survivor, determinism requirements, targeting rules — lives in sava-build's
`HARDENING.md`.

## Newly adopted suites — 2026-08-04, seeded untriaged debt

`accounts`, `sysvar`, `pbkdf` and `primitives` were registered, and `vanity` was
widened from its `Subsequence*` allowlist to the whole package, to close
`mutationOwnershipAudit` — every compiled production class in this module now
sits in some suite's target universe, with no `declineExclusionAudit` anywhere.
Adding a suite is expected to go red first, and these did. Their first baselines
were seeded from the full unkilled population and every row carries
`# untriaged`:

| Suite | Population | Detected | Seeded rows |
| --- | --- | --- | --- |
| `accounts` | 304 | 34% | 199 (44 SURVIVED, 155 NO_COVERAGE) |
| `sysvar` | 164 | 72% | 45 (13 SURVIVED, 32 NO_COVERAGE) |
| `pbkdf` | 80 | 30% | 56 (14 SURVIVED, 42 NO_COVERAGE) |
| `primitives` | 58 | 13% | 50 (all NO_COVERAGE) |
| `vanity` | 235 | 73% | 63 (32 SURVIVED, 31 NO_COVERAGE) |

**PIT 1.25.9 restored record compact-constructor mutants — all of them killed,
2026-08-04.** The tool bump from 1.25.8 grew this module by 19 mutants and left
every one of them unkilled at first: `pbkdf` +16 (population 80 → 96, of which 15
new survivors), `primitives` +1, `tx` +3 (those three killed on arrival). The +15
were the `Argon2id` and `PBKDF2WithHmacSHA512` compact constructors — the guards
that bound KDF parameters read out of an externally-supplied key file, so a mutant
there accepts a silently-weak derivation or a memory/CPU-exhaustion setting and
says nothing. `KeyDerivationBoundsTests` kills all of them by asserting each bound
at its extremes: the boundary value that must be accepted and the first value past
it that must be rejected, which is the only shape that separates `<` from `<=`.
`DiscriminatorTests` did the same for `primitives`, taking it from 8/59 to 16/59.
**No accepted row was added for the version bump** — every new mutant was killed,
not argued.

**Those 413 rows are debt made explicit, not equivalence claims.** Nothing below
argues them yet, which is exactly what `# untriaged` means, and the
`NO_COVERAGE` majority is mechanical work — untested lines, not judgement calls.
Triage replaces the label with a family label whose argument is written here.
The heavy `NO_COVERAGE` counts are honest: `primitives` has one covering test
(`FilterTests`) for four packages, and `accounts` covers the library's central
value types with three test classes.

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

**Identical rows are sibling mutants — never dedupe this file.** One compound
condition emits a mutant per operand or branch direction at the same
`class,method,mutator,STATUS` key (and one `MathMutator` key can cover
different operations across a method — a shift and an add — the `# line` tags
telling the copies apart), so a key legitimately repeats. The comparison is a multiset: the copies were collapsed
until 2026-07-23, which let a killed sibling regress unnoticed behind its
accepted twin. The migration that materialized them added five copies here
(`ed25519` `car25519`, `encoding` `Base58` ×4) — all inside the families
below, plus one `limbsLength` copy that had been reading `TIMED_OUT`.

## Removed PublicKey overloads — pending baseline retirement

`# removed signature overload pending prune` marks four retained excess
`PublicKey.verifySignature` `NO_COVERAGE` rows: two each for
`BooleanFalseReturnValsMutator` and `BooleanTrueReturnValsMutator`, following removal
of the String-signature overloads in `0112d69`. The fresh 2026-09-05 accounts run
contains three remaining `NO_COVERAGE` instances per mutator; the baseline contains five.

These rows still contribute active baseline matching capacity. Removed-source
evidence establishes absence, not an observed licensed kill. Their retirement
remains pending reconciliation with the repository's licensed-kill removal rule;
the labels do not claim that the deleted sites are equivalent or still uncovered.

## Triaged equivalent mutants (accepted with reasons)

Triaged 2026-07-18 for the encoding, ed25519, and token2022 suites (the tx
population remains debt — see below); grouped by the principle that makes
them equivalent. The baseline CSVs carry the exact keys. `borsh` seeded
empty: keep it that way.

**Allocation-size only** — baseline label `# allocation size` — the mutant
changes how much is allocated, never what is computed:
- `Base58.decode` (all six variants): the limb-array sizing
  `limbsLength(to - i)` → `to + i` only over-allocates; `used` from
  `toLimbs` bounds what is read back out.
- `Base58.limbsLength` — **killed 2026-08-05, acceptance withdrawn.** These
  inflated the bit-bound estimate (`/ 1_000` → `* 1_000`, `>> 5` → `<< 5`),
  returning identical bytes for up to a million times the memory. The old
  reason — "an allocation-bound assertion could convert this family to
  killable, accepted instead because decode has no zero-allocation
  contract" — asked the wrong question. There is no zero-allocation
  contract, but there *is* a bit bound, and it is stated in the method's own
  comment: never under-allocate, and round up only to the limb boundary.
  That is assertable as a value, so no allocation harness was needed:
  `limbsLength` is package-private and `Base58LimbBoundTests` checks it
  against an exact `BigInteger` oracle — the minimum limbs holding
  `58^d - 1` — across every digit count from 1 to 512, in both directions.
  All six mutants die, including the one that had been in the audited
  timeout set. See the note on resource-detected mutants below.

**Slow-path / alternate-path routing** — baseline label `# slow path routing`
— within the modeled contract described for each case, both paths are
result-identical and the mutant only changes which one runs:
- `Base58.toLimbs` (three source variants): disabling the 5-digit chunk
  batching (`numDigits < 5` / `i < to` → false) degrades to per-digit
  `mulAdd` with `POW_58[1]`; same accumulated limbs, more calls.
- `Jex.decodePrimIterChecked` (both variants): removing the `len == 0` fast
  path routes empty input through the general loop, which produces the same
  empty result.
- `TokenMetadata.read`: removing the `numExtras == 0` → `Map.of()` fast
  path builds an empty `LinkedHashMap` and wraps it as unmodifiable. For parsed,
  protocol-valid metadata, whose Borsh string keys are non-null, both paths expose
  the same empty entries, iteration, map equality/hash code and mutation rejection,
  and produce the same TokenMetadata Borsh length and bytes. The map implementations
  differ for null queries and Java object serialization; neither behavior is part
  of the modeled TokenMetadata wire domain.

**Defensive code unreachable in context** (baseline labels `# surplus zero strip`
for the Base58 encode family and `# pack25519 passes` for the dropped
`car25519` passes):
- `Base58` encode family (`encode`, `mutableEncode`,
  `continueMutableEncode`, `beginMutableEncode`): the surplus-`ENCODED_ZERO`
  strip loop after digit emission (and its boundary variant). No entry point
  produces surplus zero digits for it to remove — corroborated by the
  BigInteger-reference differential and Bitcoin Core vectors passing with
  the strip disabled; retained as defense.
- `Ed25519Util.pack25519`: dropping one of the three leading `car25519`
  passes, and its `changed conditional boundary`: the remaining passes plus
  the exact double conditional-subtract reduction still fully normalize
  every limb state reachable from 32-byte `Codec.decode32` inputs.

The fresh 2026-09-05 `token2022` run generated 620 mutants: 600 killed and the
same 20 accepted survivors, all in extension classes. Removing the redundant
unknown-extension add-and-advance branch reduced the population from 623;
unknown values now use the common path, and diagnostic names are looked up only
on errors. No `Token2022.parseExtensions` null-guard acceptance remains.

**Static-initializer construction** — baseline label `# static init`
(`Ed25519Util$PointAccum.create`,
`$PointExtended.create`): called only while the `static {}` block builds
the comb precomputation tables, once per PIT minion JVM before any mutant
activates — unkillable by construction (same family as json-iterator's
`JHex$INIT_DIGITS`).

**Verdict-invisible arithmetic in the TweetNaCl path** — baseline label
`# verdict invisible` (`car25519` bias
terms, `sel25519` XOR→AND, `pack25519` tail mask/shift mutants,
`scalarMultBase`'s final `cnegate`, `Scalar25519.toSignedDigits`): the
on-curve verdict consumes packed values only through equality of two
identically-packed values and low-bit parity, and both differential oracles
(BouncyCastle and the in-test BigInteger Euler-criterion oracle over the
full input domain, including torsion points and non-canonical encodings)
pass with these mutants active. Verified individually 2026-07-16 — see the
Ed25519 hardening section of `AGENTS.md`.

**Hash-mixing operator swaps** — baseline label `# hash mixing` (18 sibling rows
across 4 line-less keys — one `hashCode,MathMutator` key per class, carrying
11 siblings in `ConfidentialTransferAccount`, 3 each in
`ConfidentialTransferFeeConfig` and `ConfidentialMintBurn`, 1 in
`UnknownTokenExtension`; one sibling per mixed field): `31 * h + x` → `31 * h - x` still
yields a consistent, equals-compatible hash; only exact-hash-value
assertions could kill these, and hash values are not part of the contract.

**Dead final cursor advance** — baseline label `# dead cursor advance`
(`ConfidentialMintBurn.read` line 36): the
last `i += pendingBurn.length` before the return is a dead store — nothing
reads `i` afterwards. Kept for symmetry with the preceding field reads;
refactoring it away would remove the mutant.

## Triaged equivalent mutants — meta suite

Seeded 2026-07-20 with the suite, 18 entries across three families. No
`NO_COVERAGE`.

**Identity short circuit in equals** — baseline label `# equals identity`
(7 keys, `RemoveConditionalMutator_EQUAL_IF`
on the `this == o ||` prefix of every `equals`): removing the reference check
falls through to the class-and-key comparison, which returns the same answer
for every input. It is a fast path, not a branch.

**Redundant or equal-returning branches in merge** — baseline label
`# merge redundant` (6 keys,
`RemoveConditionalMutator_EQUAL_ELSE`), two sub-cases:
- `AccountMetaWrite.merge` line 18 and `AccountMetaReadOnlySigner.merge`
  line 18: the `accountMeta.feePayer()` guard is subsumed by the `signer()`
  branch below it, because a fee payer is also a signer *and* writable, so the
  ternary there returns the same argument the guard would have.
- the `accountMeta.write()` ternaries (`AccountMetaWrite` 26/29,
  `AccountMetaReadOnlySigner` 22, `AccountMetaInvoked` 19): forcing the else
  builds a fresh `AccountMetaSignerWriter`/`AccountMetaInvokedAndWrite` with
  the same key instead of returning the argument. Equal by `equals`, just not
  the same instance. Killable only by asserting identity, which the API does
  not promise.

**hashCode arithmetic** — baseline label `# hashcode arithmetic` (5 keys,
`MathMutator` on the `31 * result + 1` mixing):
the surviving mutations still produce hashes that are distinct across the
privilege types, which is the only property that matters and the one
`hashCodeDistinguishesPrivileges` asserts. Killing them would mean pinning
exact hash values and freezing an implementation detail.

Note `AccountMetaFeePayer` and `AccountMetaSignerWriter` hash identically —
the scheme folds in `(signer, write, invoked)` and a fee payer shares that
triple. Legal, since `equals` separates them by class and unequal objects may
collide. Asserted in `hashCodeDistinguishesPrivileges` so it stays deliberate.

Six further `merge` cells lose a privilege where `invoked` meets `signer`
(there is no type for an invoked signer). Those are unreachable — a program
account cannot sign — and are pinned in
`AccountMetaTests.mergeLosesPrivilegesOnlyWhereInvokedMeetsSigner` rather than
here, because they are a behaviour gap rather than an unkillable mutant.

## crypto suite — no accepted mutants

No `crypto-accepted.csv` is present because this suite has no accepted mutants.
The fresh 2026-09-05 observation after removing `Hmac.hmacSHA512(byte[], byte[])`
generated and killed all 10 mutants. The earlier 12-mutant population included
the removed helper; the reduction is explained by that API removal, not reduced
test execution. The dated trial table in `HARDENING_NOTES.md` retains its original
12-mutant measurement.

`Hash.sha256Twice` and `Hash.h160` have no caller anywhere in the repo. They
were kept rather than deprecated since they are correct, tiny, and removing
`h160` would not shed the BouncyCastle dependency (ed25519, `Signer`,
`PublicKey` and Argon2id all need it). Being uncalled is exactly why they are
pinned to published vectors *and* differentially checked against a naive
two-instance implementation: `sha256Twice` reuses one `MessageDigest` across
both rounds and depends on `digest()` resetting it, so comparing against the
same technique twice would prove nothing.

The removed two-argument `Hmac.hmacSHA512` helper had a historical key/data reversal
and was deprecated before its removal for 25.11.0. The no-argument factory remains
supported; the RFC 4231 vector tests initialize its `Mac` explicitly.

The `ed25519` subpackage is excluded here — it has its own suite, and the
`crypto.*` wildcard spans dots.

## vanity suite — the former Subsequence-only phase (closed 2026-08-04)

Everything in this section describes the suite's earlier life, when its target
list was the `Subsequence*` allowlist: in that phase `vanity-accepted.csv` was
empty and the narrow suite ran at 100%. The 2026-08-04 ownership closure widened
the suite to the whole package, and its current state — 63 seeded `# untriaged`
rows and the audited timeout set — is recorded in the seeded-debt table at the
top of this file, not here.

It was briefly seeded 2026-07-20 with 9 entries, all from the "Character
options:" table that `Subsequence.create` printed to `System.out` while
building the mask set — `VoidMethodCallMutator` on the print calls plus the
`level < 3` loop driving them. Nothing asserts stdout, so nothing could kill
them. Rather than accept that, the block moved out of the library the same
day: [Subsequence#charOptionsTable()] now returns the table as a string and
`software.sava.vanity.Entrypoint` prints it, which is where user-facing
reporting belongs. A pure function is assertable, and all nine mutants died.

The general lesson for this repo: a cluster of unkillable mutants around
output or logging usually means the side effect is in the wrong layer, not
that the mutants are equivalent.

Note this suite deviates from the package-wildcard targeting rule and
allowlists `Subsequence*`. The reason is in `sava-core/build.gradle.kts`: the
mask workers search in an unbounded loop, so mutants that break the match
predicate run to the PIT timeout rather than failing fast. They stay covered
by `MaskWorkerTests` without being mutated.

## Triaged equivalent mutants — decimal suite

4 entries, all the same equivalence: the unsigned-widening guard
`val < 0 ? ByteUtil.toUnsignedBigInteger(val) : BigDecimal.valueOf(val)` in
`DecimalInteger.toDecimal`, and its `BigInteger` twin in
`DecimalIntegerAmount.amount`.

**Allocation routing only** — baseline label `# allocation routing`. Both
branches build an identical value for every
non-negative long — verified exhaustively over the boundaries and 2M random
values — so `<` → `<=` (which differs only at zero, where both give zero) and
forced-true (which always widens) cannot be told apart by any assertion on the
result. The guard exists because `valueOf` is cheaper, not because the branches
disagree. The forced-*false* direction is not equivalent — it sign-extends
instead of widening — and is killed by
`DecimalIntegerTests.longOverloadTreatsNegativeAsUnsigned` and
`amountWidensNegativeLongsAsUnsigned`.

These were briefly killed on 2026-07-20 with `ThreadMXBean` allocation bounds,
which is the technique HARDENING.md suggests for exactly this shape. It was
reverted, and the reasons are worth recording before anyone tries again:

- **The measurement is fragile.** A result that is immediately discarded can be
  scalar-replaced by escape analysis, erasing the allocation being measured — and
  only on runs that reach the right JIT tier. The first version passed alone and
  failed intermittently under the ratchet. A `volatile` sink fixes it, but the
  fragility is inherent.
- **The margins are thin.** Bounds have to be set per method from measurements;
  `toDecimal` has a ~40 byte floor that `amount` does not, and on a large value
  the gap between the fast path and the mutant is 64 bytes against 88.
- **PIT re-runs covering tests once per mutant.** A warmup-plus-rounds harness of
  ~150k iterations per assertion took this suite from ~10s to ~38s, for four
  mutants that are correctly described here in prose.

A documented equivalent mutant is a closed gap. Chasing the last four to make a
percentage read 100 cost more than it returned.

## Triaged equivalent mutants — tx suite

The tx baseline was seeded 2026-07-18 with 182 keys of untriaged debt from
widening the suite to the full `tx` and `accounts.lookup` packages. A
kill pass the same day (`AccountIndexLookupTableTests`,
`TransactionByteHelpersTests`, `TransactionFactoryTests`,
`TransactionRecordPlumbingTests`) removed 142 of them. Those are historical
measurements; the accepted CSV records the current retained rows, and the arguments
below apply only to their named branches.

**Instruction hash mixing** — baseline label `# hash mixing` (2 sibling rows
under `InstructionRecord.hashCode`, `MathMutator`). The reviewed mutants replace
addition with subtraction in the valid-span length fold and the per-byte fold,
respectively. Both retain a deterministic, content-dependent hash: equal programs,
account lists, lengths, and ordered logical bytes still produce equal hashes,
independent of backing-array identity or offset. Exact valid-span hash values are
not a public contract. The collection and representative-field tests in
`InstructionBuildingTests` protect equality consistency and useful dispersion;
pinning exact integers merely to kill these sign changes would overspecify them.
This acceptance does not cover arithmetic in the invalid-span fallback, whose
former generated-record hash is preserved and checked independently.

`Transaction.exceedsSizeLimit` is a reachable compatibility default for external
implementations. `PreV1InterfaceShapeTests` exercises its 1232/1233-byte boundary;
the built-in legacy/v0 implementation has its own independent boundary test.
There is no accepted mutant on this default. The current `BaseTransaction.setBlockHash`
acceptance is documented below under `# both arms write the same bytes`.

**Result-identical routing** — baseline label `# result identical routing`:
two branches retain this equivalence argument after the 2026-09-05 review. The
singleton-null exception in `InstructionRecord` is documented separately below.

- `Transaction.createTx`, `RemoveConditionalMutator_EQUAL_ELSE`, in the overload
  taking `LookupTableAccountMeta[]`: forcing the `len == 1` compaction shortcut
  false routes a one-element displacement through `System.arraycopy`. It copies
  the same account into the same destination slot before the common front
  assignment. This argument covers that singleton displacement, not the removed
  single-table dispatch or filtered-table serialization shortcuts.
- `TransactionSkeleton.deserializeSkeleton`, `ConditionalsBoundaryMutator`, on
  `numLookupTables > 0`: at zero tables `>` → `>=` builds an empty `PublicKey[]`
  where the guard returns the shared `BaseTransactionSkeleton.NO_TABLES`. Both
  expose zero loaded accounts and the same remaining transaction fields; the
  constant avoids allocation.

The filter-copy mutants in `TransactionSkeletonImpl` are covered separately under
`# array identity only`. No current `# result identical routing` row belongs to
transaction signing.

**No-op displacement boundaries** — baseline label `# displacement boundary`:

- `Transaction.createTx`, `ConditionalsBoundaryMutator` ×2, in the single-table
  and `LookupTableAccountMeta[]` overloads: changing `i > numIncludedAccounts` to
  `>=` adds only the equality case. There `len` is zero, so the copy moves no
  elements and the front assignment writes the account back to its existing slot.
- `Transaction.createTx`, `RemoveConditionalMutator_EQUAL_ELSE`, in the
  single-table overload: bypassing `len == 1` performs the same one-element move
  with `System.arraycopy` before the common front assignment.

**Dead defensive code** — baseline label `# dead defensive`:

- `TransactionRecord.MERGE_ACCOUNT_META` (`lambda$static$0`),
  `RemoveConditionalMutator_EQUAL_ELSE`: `Map.merge` never invokes its remapping
  function with a null existing value, so bypassing `prev == null` cannot change
  a valid map merge.
- `AccountIndexLookupTableView.compareTo`, `RemoveConditionalMutator_EQUAL_ELSE`:
  for views of complete 32-byte keys, forcing the view-specific branch off
  compares the same key bytes through `toByteArray`. The cross-table comparison
  was fixed on 2026-07-21 and is pinned by
  `AccountIndexLookupTableTests.viewCompareToReadsTheOtherViewsBackingTable`.
  No current row in this family belongs to transaction signing.

### Retired compatibility acceptances — 2026-09-05

Five accepted rows were removed with `pitestTxBaselinePrune` after two matching
fresh full history-free previews and a distinct third write-boundary run over
unchanged source, tests, toolchain, and baseline inputs. All three serialized Tx
runs produced 1,678 mutants: 1,639 killed, 38 survivors, and one existing audited
timeout. They agreed on every mutation status and the exact five removal candidates.
The baseline shrank from 43 to 38 rows; the writer also refreshed two retained line
metadata tags. No new timeout or invalid execution status was observed.

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
  bypassing the singleton shortcut appends a null account where the current method
  ignores it. `InstructionBuildingTests.extraAccountsRetainsSizeDependentNullHandlingForCompatibility`
  pins that published asymmetry, including retention of nulls in larger lists.
- `InstructionRecord.toString`, `RemoveConditionalMutator_EQUAL_IF` and
  `ConditionalsBoundaryMutator`: removing the null-data guard or widening the
  positive-length check can make diagnostic rendering throw for inputs it currently
  renders as empty. `InstructionBuildingTests.toStringRendersNullOrNonpositiveLengthDataWithoutReadingTheSpan`
  covers null data with a positive length and a zero-length span with an invalid
  offset. Diagnostic rendering does not make either input valid for serialization.

These regressions preserve behavior already present in 25.10.0. The records were
retired because the licensed mutants were observed killed, not merely absent.

### SIMD-0385 v1 transactions — triaged 2026-08-15

Merging the v1 transaction work brought seven previously unbaselined classes
(`BaseTransaction`, `BaseTransactionSkeleton`, `TransactionSkeletonImpl`,
`TxBuilder{,Impl}`, `V1Transaction`, `V1TransactionSkeleton`) into scope, 118
unkilled mutants on first measurement. A kill pass took that to 29 —
`TransactionEqualityTests`, `LegacyComputeBudgetTests`, `TxBuilderValidationTests`,
`SigningCountTests`, `V1ConfigValueTests`, `V1FilterBoundaryTests` and
`LegacyInstructionViewTests`, 63 tests. Those counts describe that historical
measurement; the current per-family arguments follow.

**Config value offset codomain excludes 0** — baseline label
`# config value offset codomain excludes 0` (`V1Transaction.configValueOffset`,
`.createTransaction` ×4, `.setPriorityFeeLamportsFromComputeUnitPrice`,
`V1TransactionSkeleton.priorityFeeLamports` / `.computeUnitLimit` /
`.accountDataSizeLimit` / `.heapSize`): every one of these tests an offset
returned by `V1TransactionSkeleton.configValueOffset`, whose only outcomes are
the literal `-1` when the mask bits are clear, or
`V1_ACCOUNTS_OFFSET + (numAddresses << 5) + (bitCount << 2)`. The base offset is
42, and the unsigned address count and bit count add non-negative displacements,
so a present value's offset is at least 42. `< 0` and `<= 0` (likewise `>= 0` and
`> 0`) differ only at zero, which neither outcome can produce. Both arms of each
guard are exercised — the mutants are the boundary alone.

**Signature offset codomain excludes 0** — baseline label
`# signature offset codomain excludes 0` (`BaseTransaction.signedIdOffset`,
`.toString`): the same argument one level up. `feePayerSignatureOffset` returns
`-1`, or the legacy literal `1`, or a v1 offset that
`V1TransactionSkeleton.requireSignatureBlockOffset` has already forced to be
`>= V1_ACCOUNTS_OFFSET`. Never 0.

**A v1 buffer always carries signatures** — baseline label
`# a v1 buffer always carries signatures`: the `ConditionalsBoundaryMutator` in
`V1TransactionSkeleton.signaturesOffset` changes `headerBlockEnd > data.length`
to `>=`. At equality, the existing `signaturesOffset < headerBlockEnd` operand
already rejects the message, because a parsed v1 skeleton requires at least one
64-byte signature and its implied signature offset is therefore below `data.length`.
The same `IllegalStateException` and message are reached either way. Complete
signature layouts and truncated-header rejection are covered by
`aTransactionWhoseInstructionsHaveNoAccountsOrDataSitsExactlyOnTheHeaderBound` and
`aPayloadTruncatedInsideTheInstructionHeadersIsDiagnosed`.

**Malformed message still rejected** — baseline label
`# malformed message still rejected`: the `ConditionalsBoundaryMutator` on
`V1TransactionSkeleton.messageEnd`'s header-block bound also changes `>` to `>=`,
but this helper reads untrusted raw bytes. A malformed buffer can end exactly at
its header-block end. The original returns an end at or beyond the buffer length;
the mutant returns `-1`. `requireSignatureBlockOffset` rejects both because neither
can equal the implied signature offset, which is below the buffer length for a
nonzero signature count. The diagnostic's rendered message-end offset differs,
while the documented `IllegalArgumentException` rejection and every valid
transaction's result are unchanged. This acceptance covers that diagnostic-only
difference; it does not claim the boundary is unreachable.

**Array identity only** — baseline label `# array identity only`
(`TransactionSkeletonImpl.filterInstructions` / `.filterInstructionsWithoutAccounts`
and their `V1TransactionSkeleton` twins): the
`d == numInstructions ? instructions : Arrays.copyOfRange(instructions, 0, d)`
tail. Forcing the equality false always takes the copy arm, which at
`d == numInstructions` copies the full range — same component type, length and
element references. `instructions` is a local that is never published, so the
only difference is an array identity no caller can hold. The `EQUAL_IF` siblings,
which would return the null-padded array when `d < numInstructions`, are
genuinely observable and are killed.

**Short circuit already returned** — baseline label
`# short circuit already returned`:

- `TxBuilder.computeUnitPriceToPriorityFeeLamports` and
  `TransactionRecord.priorityFeeLamportsToComputeUnitPrice`,
  `ConditionalsBoundaryMutator` ×2: each negative-price/fee guard differs from
  `<= 0` only at zero, already handled by the preceding fast return.
- `TransactionRecord.priorityFeeLamportsToComputeUnitPrice`,
  `RemoveConditionalMutator_EQUAL_ELSE`, on `priorityFeeLamports == 0`: if the
  limit is zero the other operand still returns immediately; otherwise bypassing
  the zero-fee shortcut computes `(limit - 1) / limit`, which is also zero for
  the positive capped limit. This row skips a redundant shortcut rather than
  testing a value after that shortcut returned.

The zero-limit operand in `TxBuilder.computeUnitPriceToPriorityFeeLamports` is
not equivalent: skipping it reaches division by zero in the overflow guard.
`testAZeroComputeUnitLimitShortCircuitsBeforeTheOverflowGuardDivides` kills it.

**Legacy parse routes converge** — baseline label
`# legacy parse routes converge` (`TransactionSkeletonImpl.parseAccounts` ×2):
`deserializeSkeleton`'s legacy branch always supplies empty invoked indexes and
no lookup tables with `numAccounts == numIncludedAccounts`, so the versioned
parse degenerates to the legacy one — same length, same metas, `binarySearch`
against an empty array always missing. Pinned by
`testLegacyParseAccountsIgnoresLookupTables`.

**Redundant guard operand** — baseline label `# redundant guard operand` — the
mutant disables one operand of a compound condition whose remaining path
reaches the identical result:
- `TxBuilder.computeUnitPriceToPriorityFeeLamports`, the
  `microLamportsPerComputeUnit == 0` half of the fast return. Skipping it for a
  zero price falls through the overflow guard (`0 < 0` and
  `0 > (Long.MAX_VALUE - 999_999) / limit` are both false) into the general
  arithmetic, which computes `(0 * limit + 999_999) / 1_000_000` — integer
  division yielding the same 0. The **other** half of that same condition,
  `cappedComputeUnitLimit == 0`, is *not* equivalent: skipping it divides by
  zero in the overflow guard, and it is killed by
  `testAZeroComputeUnitLimitShortCircuitsBeforeTheOverflowGuardDivides`.
  Sibling mutants on one condition are not interchangeable — this pair is the
  worked example.
- `V1TransactionSkeleton.signaturesOffset`, the `headerBlockEnd > data.length`
  half of `headerBlockEnd > data.length || signaturesOffset < headerBlockEnd`.
  Removing that operand's early exit leaves the second one, which is strictly
  stronger: a v1 message always carries at least one 64-byte signature
  (`V1Transaction.isV1` requires `data[1] != 0`, and `deserialize` additionally
  rejects `num_readonly_signed >= num_required_signatures`), so
  `signaturesOffset = data.length - numSignatures * 64 <= data.length - 64 < data.length`.
  Whenever `headerBlockEnd > data.length` holds, `headerBlockEnd > signaturesOffset`
  follows, so the second operand catches every input the first would have.

**Other unchanged outcomes** — retained family labels:

- `# v1 signer count remains nonzero`,
  `BaseTransaction.feePayerSignatureOffset`, `MathMutator`: the v1 discriminator
  has already established `data[1] != 0`. Replacing the sign-count mask `& 0xFF`
  with `| 0xFF` changes the numeric value but preserves its only later use,
  `numSigners != 0`. Signature-block validation reads its own count from the
  buffer. The read executes, and the zero-count route remains unreachable.
- `# both arms write the same bytes`, `BaseTransaction.setBlockHash`,
  `RemoveConditionalMutator_EQUAL_ELSE`: for a built-in transaction, bypassing
  the direct copy routes through its final `setRecentBlockHash`, copying the same
  32 bytes to the same destination offset. External implementations already use
  the setter route.
- `# mergeAccounts rejects it identically`, `TxBuilderImpl.createTransaction`,
  `RemoveConditionalMutator_EQUAL_ELSE`: bypassing the empty-instruction check
  reaches `mergeAccounts`, which rejects the same empty input with the same
  exception class and message.
- `# prepend path is a no-op when nothing is prepended`,
  `TransactionRecord.setComputeBudgetValues`, `RemoveConditionalMutator_EQUAL_ELSE`:
  when both requested values have been found, bypassing `numToPrepend == 0`
  allocates an equally sized local array, adds neither prefix instruction, and
  copies all updated instructions into it at offset zero. Both routes rebuild
  from the same ordered instructions and carry over the same blockhash. The copy
  executes; no zero-iteration loop is involved.

## Untriaged debt (tx suite)

The current baseline has two `# untriaged` rows, both in
`TransactionSkeleton.deserializeSkeleton`: the forced versioned walk and the removed
legacy instruction walk described below. The first two entries record closed findings
from the earlier seven-row triage; they are historical evidence, not retained debt.

- `TransactionSkeletonImpl.invokedProgramAccount`
  `RemoveConditionalMutator_EQUAL_ELSE` — **killed by the v1 merge.** The
  triage was right that the branches are not result-identical: rebuilding
  an already-invoked meta through `createInvoked` preserves
  `AccountMetaInvoked` (whose `equals` is value-based on the public key,
  which is why the legacy assertions could not see it) but downgrades an
  `AccountMetaInvokedAndWrite` to read-only. It named the distinguishing
  fixture — a program account that is also writable — and the v1 branch
  already had exactly that in
  `V1FilterBoundaryTests#anInvokedAndWrittenProgramKeepsBothFlagsThroughParseInstructions`,
  so merging the two histories killed it without new work.
- `TransactionSkeletonImpl.parseInstructions`
  `ConditionalsBoundaryMutator` — **killed 2026-08-18** once sava#57
  resolved the read-side contract as two bounds in `instructionAccount`,
  neither of them a format split: an index at or past the transaction's
  own declared total (`numAccounts`, included plus table-loaded) is
  corruption in every format and throws the diagnosed rejection
  transaction v1 already uses, while a declared index the supplied array
  cannot resolve reads as the documented null — reachable through
  sava's own parsers only for a v0 message parsed without its lookup
  tables. Both bounds are revert-verified and pinned at their exact
  boundaries by
  `TransactionSkeletonParseTests#declaredButUnresolvedV0InstructionAccountIndicesReadAsNull`
  and `#undeclaredInstructionAccountIndicesAreRejectedInEveryFormat`,
  the latter including the oversized-caller-array case, since the
  caller's array must not widen what the wire declares.
- `TransactionSkeleton.deserializeSkeleton`
  `RemoveConditionalMutator_ORDER_IF` — **kill candidate.** Forcing the versioned
  walk for a legacy message leaves `version` untouched, so `isLegacy()` still
  agrees, but `invokedIndexes` becomes populated instead of remaining empty.
  The public `parseAccounts(writableLoaded, readonlyLoaded)` overload uses
  `parseVersionedIncludedAccounts` even for a legacy skeleton; empty loaded-account
  lists expose the changed invoked flag on a read-only program account.
- `TransactionSkeleton.deserializeSkeleton`
  `RemoveConditionalMutator_ORDER_ELSE` (legacy instruction walk) —
  **owner decision.** Whether the walk is dead for well-formed input
  depends on whether eager validation of the legacy instruction section
  is wanted; unlike the precedents above it reads `data`.

These two rows remain untriaged. No other current tx baseline row carries
`# untriaged`; the retired entries above are historical evidence.
Baseline shrinkage requires row-specific evidence, and growth requires a reason here.

## Timed-out mutants (audited set)

`TIMED_OUT` is detected — these mutants never enter a baseline — but the
watchdog observed slowness, not wrongness: for exactly these mutants the
ratchet cannot see a weakened covering assertion, because a timeout keeps
"detecting" no matter what the test asserts. Per HARDENING.md, the summary's
`N timed out (load-dependent)` is therefore an audited set, not a count: every
member is listed here with the structural cause that makes it spin, and a
mutant timing out that is *not* on this list is something a reviewer stops on.
Membership is machine-checked: `<suite>-timeouts.csv` holds the line-less
`class,method,mutator` keys, and the verify warns on any timeout outside them.
Per-run counts sit at or below the set size — a dead mutant's covering test
racing the watchdog can land either detected flavour.

As of 2026-08-10 — 11 members across four suites (`ed25519` 4, `encoding` 1,
`tx` 1, `vanity` 5), each carrying a `cause:liveness` token in its suite's
`-timeouts.csv`. The set grew when `vanity` widened to its whole package, shrank
when `Base58.limbsLength` became killable, and halved again when four of
`vanity`'s members turned out to be bounded by a seam no test was using; every
movement is recorded with its suite below.

Two rows came BACK on 2026-08-10 — `ed25519`'s `pack25519` and `vanity`'s
`SubsequenceRecord.formatCharOptions`, both `ORDER_IF`. They were removed on
2026-08-08 on the argument that the mutator set no longer generates them, which
may well be right; what it was not is *measured* under the retirement protocol
the current plugin enforces, which asks for three consecutive quiet runs before
a member leaves. A single run that does not produce a mutant looks exactly like
a run that was lucky. Restoring them costs one line each and one quiet-member
advisory per run until the protocol is satisfied — cheap against the failure it
prevents, which is a timeout reappearing at a site whose note says it was
expected to be gone.

**A timeout is not one thing, and the difference decides whether a member
belongs here at all.** Splitting these by their written cause gives two classes.
The first is *non-termination*: a loop whose only exit the mutant removed, or a
lock it never releases. Nothing can assert against it except the watchdog, so it
is correctly audited and stays — every member below is of this kind. The second
is *resource*: the mutant returns the identical answer having done far more work,
and only crawls into the watchdog because allocation or GC caught up with it.
That is not detection, it is a race, and it reads `TIMED_OUT` under load and
`SURVIVED` when idle — which is exactly what `Base58.limbsLength` did for weeks.
A resource mutant is assertable and therefore does not belong in an audited set:
find the bound the method already claims and check it. Prefer a value assertion
on that bound over an allocation or timing harness — `limbsLength` needed only
package-private visibility and an exact `BigInteger` oracle, which cannot flap,
needs no warm-up, and killed one more mutant than a measured allocation bound
did. The harness stays what `AGENTS.md` calls it: a last resort.

**ed25519** (4 retained members, all `Ed25519Util`; 3 observed `TIMED_OUT` in
the 2026-09-05 certification)

The three observed members also read `TIMED_OUT` identically solo and under
gate load in the 2026-07-22 mode comparison. The fourth, `pack25519`, remains
retained pending retirement as described below.

- `pack25519:385` (`RemoveConditionalMutator_ORDER_IF` on the `j < 2`
  reduction-pass loop) — **restored 2026-08-10, retirement pending.** The
  2026-08-08 reading was that the mutant is no longer generated at all: `pack25519` now yields only `ORDER_ELSE`
  (`KILLED`) and `ConditionalsBoundary` at line 385, and exactly one `ORDER_IF`
  remains in the whole ed25519 population (`pow2523:420`). `vanity`'s
  `SubsequenceRecord.formatCharOptions:148` went stale the same way in the same
  pass — two `ORDER_IF` rows whose branches now produce only `ORDER_ELSE` —
  which reads as a mutator-set change rather than two coincidences. That reading
  now has to earn its retirement the same way every other member does: three
  consecutive runs with no such mutant. Until then both rows stay listed, and the
  verify's quiet-member advisory is the countdown.
- `pow2523:420` (`ORDER_IF` on `a >= 0`): the 2^252−3 exponentiation ladder
  loses its countdown exit.
- `scalarMultBase:938` (`IncrementsMutator`, `var6 -= 4` → `+= 4`): the
  window cursor walks up instead of down and never crosses the `var6 < 0`
  exit.
- `scalarMultBase:939` (`ORDER_ELSE` on `var6 < 0`): the ladder loop's only
  `return` is forced unreachable.

**encoding** (1)
- `Jex.isValid:571` (`IncrementsMutator`, second `index++` → `index--`): the
  do-while cursor oscillates over the same valid digit pair and never reaches
  `len`.
- `Base58.limbsLength:94` — **retired from the set 2026-08-05.** It inflated
  the limb-count estimate so the run crawled under allocation and GC instead
  of failing, which is why it flapped `SURVIVED`↔`TIMED_OUT` between a busy
  and an idle machine. That is not "the loop has no exit"; it is "the method
  does far more work for the same answer", and unlike a hang it is
  assertable. `Base58LimbBoundTests` now kills all six mutants outright, so
  the member matches nothing and both baseline copies were pruned. Kept here
  as the worked example of the distinction below.

**tx** (1)
- `AddressLookupTableOverlay.lambda$keysToString$1:128`
  (`PrimitiveReturnsMutator` on the `IntStream.iterate` step
  `i -> i + PUBLIC_KEY_LENGTH` → `0`): the offset cursor collapses to 0,
  stays below `to` forever, and the join accumulates keys until the watchdog.

**vanity** (5 retained members; 4 observed `TIMED_OUT` in the 2026-09-05
certification)

Every mask worker's search is a `for (;;)` with exactly **two** exits: "found
enough" (`foundHitLimitOrInterrupted` / `foundLimitOrInterrupted`) and "cap
reached" (`searchExhausted(attempts)`, the bounded-attempts seam whose javadoc
names tests as its reason for existing). All four worker members below disable the cap
itself — they break either the counter that feeds it or the branch that consumes
it — so the loop is left with no exit any test can reach. This is the
non-termination class: nothing but the watchdog can observe them.

- `MaskWorker.run:46` and `BeginsWithMaskWorker.run:38` (`MathMutator`,
  `++attempts` → `--attempts`): the attempt counter runs backwards, so
  `searchExhausted(attempts)` compares an ever-decreasing value against
  `maxSearches` and never becomes true. No budget can bound this, because the
  budget is what the mutant destroys.
- `MaskWorker.run:79` and `BeginsWithMaskWorker.run:56`
  (`RemoveConditionalMutator_EQUAL_ELSE`): the branch that acts on the exhausted
  cap is forced the way that keeps the loop going, removing the same exit from
  the consuming side.

**The deterministic seam, and why this set halved.** The four members retired on
2026-08-08 were listed under the same "the cap is the only exit left" argument
as the four above, and that argument was wrong for them. Their mutants do not
touch the cap — they break the *match* path (`MaskWorker.run:45` and
`BaseMaskWorker.generateKeyPair:234`, `VoidMethodCallMutator`, dropping key-pair
generation so the same bytes are retested forever; `BaseMaskWorker.queueResult:119`,
`RemoveConditionalMutator_EQUAL_ELSE`, forcing the result-queuing predicate
false; and `MaskWorker.run:54`, `MathMutator`, `& 0xFFFF` → `| 0xFFFF`,
corrupting the resumed-encode offset so a real tail match fails its prefix
check). Each of those still *reaches* a working cap. They timed out only because
every satisfiable test in `MaskWorkerTests` passed `Long.MAX_VALUE` for
`maxSearches`, so the cap was set to a value it could never hit — the seam
existed but no test used it. Giving those tests a finite `MAX_SEARCHES` (10,000
attempts; the worst real search on its fixed seed takes 529, so the bound is
~19x headroom and never fires on working code) converts all four from timeouts
into ordinary assertion failures, and they are now `KILLED` with named covering
tests. That is the rule the plugin states as "only `cause:liveness` is
admissible **after deterministic seams/budgets are exhausted**": a mutant that a
budget can bound is not a liveness member, it is an unexercised seam.

`SubsequenceRecord.formatCharOptions` (`RemoveConditionalMutator_ORDER_IF`)
was removed on 2026-08-08 for a different reason: the member matched no mutant
in that report — `formatCharOptions` yielded only `ORDER_ELSE` and
`ConditionalsBoundary`, both `KILLED`. It was **restored 2026-08-10, retirement
pending**, and remains the fifth retained member despite being absent from the
2026-09-05 report. This was the known
`KILLED`↔`TIMED_OUT` flapper in the `HARDENING_NOTES.md` mode comparisons.

The fixture bound is worth recording explicitly, since the plugin asks whether a
claimed bound can fail first: `MAX_SEARCHES` is *not* the oracle for the four
worker members. For them it is the seam that had to be exhausted before
liveness could be claimed at all, and the mutant's whole effect is to make it
unreachable — which is exactly why the watchdog is the only remaining observer.
