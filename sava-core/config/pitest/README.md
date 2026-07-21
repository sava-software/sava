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

Triaged 2026-07-18 for the encoding, ed25519, and token2022 suites (the tx
population remains debt — see below); grouped by the principle that makes
them equivalent. The baseline CSVs carry the exact keys. `borsh` seeded
empty: keep it that way.

**Allocation-size only** — the mutant changes how much is allocated, never
what is computed:
- `Base58.decode` (all six variants): the limb-array sizing
  `limbsLength(to - i)` → `to + i` only over-allocates; `used` from
  `toLimbs` bounds what is read back out.
- `Base58.limbsLength`: `/ 1_000` → `* 1_000` and `>> 5` → `<< 5` inflate
  the bit-bound estimate. The formula's contract is "never under-allocate";
  these push it further over. An allocation-bound assertion could convert
  this family to killable (json-iterator's `TestAllocation` pattern) —
  accepted instead because decode has no zero-allocation contract.

**Slow-path / alternate-path routing** — both paths are result-identical,
the mutant only changes which one runs:
- `Base58.toLimbs` (three source variants): disabling the 5-digit chunk
  batching (`numDigits < 5` / `i < to` → false) degrades to per-digit
  `mulAdd` with `POW_58[1]`; same accumulated limbs, more calls.
- `Jex.decodePrimIterChecked` (both variants): removing the `len == 0` fast
  path routes empty input through the general loop, which produces the same
  empty result.
- `TokenMetadata.read`: removing the `numExtras == 0` → `Map.of()` fast
  path builds the same empty immutable map via the zero-length entry array.

**Defensive code unreachable in context**:
- `Base58` encode family (`encode`, `mutableEncode`,
  `continueMutableEncode`, `beginMutableEncode`): the surplus-`ENCODED_ZERO`
  strip loop after digit emission (and its boundary variant). No entry point
  produces surplus zero digits for it to remove — corroborated by the
  BigInteger-reference differential and Bitcoin Core vectors passing with
  the strip disabled; retained as defense.
- `Token2022.parseExtensions`: forcing the `extensionData != null` guard
  true — the extension `read(data, offset)` null returns fire only on
  null/empty whole buffers, which `parseExtensions` has already excluded.
- `Ed25519Util.pack25519`: dropping one of the three leading `car25519`
  passes, and its `changed conditional boundary`: the remaining passes plus
  the exact double conditional-subtract reduction still fully normalize
  every limb state reachable from 32-byte `Codec.decode32` inputs.

**Static-initializer construction** (`Ed25519Util$PointAccum.create`,
`$PointExtended.create`): called only while the `static {}` block builds
the comb precomputation tables, once per PIT minion JVM before any mutant
activates — unkillable by construction (same family as json-iterator's
`JHex$INIT_DIGITS`).

**Verdict-invisible arithmetic in the TweetNaCl path** (`car25519` bias
terms, `sel25519` XOR→AND, `pack25519` tail mask/shift mutants,
`scalarMultBase`'s final `cnegate`, `Scalar25519.toSignedDigits`): the
on-curve verdict consumes packed values only through equality of two
identically-packed values and low-bit parity, and both differential oracles
(BouncyCastle and the in-test BigInteger Euler-criterion oracle over the
full input domain, including torsion points and non-canonical encodings)
pass with these mutants active. Verified individually 2026-07-16 — see the
Ed25519 hardening section of `AGENTS.md`.

**Hash-mixing operator swaps** (19 keys across `ConfidentialMintBurn`,
`ConfidentialTransferAccount`, `ConfidentialTransferFeeConfig`,
`UnknownTokenExtension` `hashCode`s): `31 * h + x` → `31 * h - x` still
yields a consistent, equals-compatible hash; only exact-hash-value
assertions could kill these, and hash values are not part of the contract.

**Dead final cursor advance** (`ConfidentialMintBurn.read` line 36): the
last `i += pendingBurn.length` before the return is a dead store — nothing
reads `i` afterwards. Kept for symmetry with the preceding field reads;
refactoring it away would remove the mutant.

## Triaged equivalent mutants — meta suite

Seeded 2026-07-20 with the suite, 18 entries across three families. No
`NO_COVERAGE`.

**Identity short circuit in equals** (7 keys, `RemoveConditionalMutator_EQUAL_IF`
on the `this == o ||` prefix of every `equals`): removing the reference check
falls through to the class-and-key comparison, which returns the same answer
for every input. It is a fast path, not a branch.

**Redundant or equal-returning branches in merge** (6 keys,
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

**hashCode arithmetic** (5 keys, `MathMutator` on the `31 * result + 1` mixing):
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

`crypto-accepted.csv` is empty and the suite runs at 100% (12 mutants). Keep it
that way.

`Hash.sha256Twice` and `Hash.h160` have no caller anywhere in the repo. They
were kept rather than deprecated — unlike `Hmac.hmacSHA512`, which was
deprecated because it was wrong — since they are correct, tiny, and removing
`h160` would not shed the BouncyCastle dependency (ed25519, `Signer`,
`PublicKey` and Argon2id all need it). Being uncalled is exactly why they are
pinned to published vectors *and* differentially checked against a naive
two-instance implementation: `sha256Twice` reuses one `MessageDigest` across
both rounds and depends on `digest()` resetting it, so comparing against the
same technique twice would prove nothing.

The `ed25519` subpackage is excluded here — it has its own suite, and the
`crypto.*` wildcard spans dots.

## vanity suite — no accepted mutants

`vanity-accepted.csv` is empty and the suite runs at 100%. Keep it that way:
any new survivor here is a real gap, not debt.

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

**Allocation routing only.** Both branches build an identical value for every
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
`TransactionRecordPlumbingTests`) removed 142 of them; the 27 keys below
are accepted equivalents, and the 13 skeleton keys under Untriaged debt
are all that remain unclassified.

**Shadowed defaults / single-implementation dispatch**:
- `Transaction.exceedsSizeLimit` line 652 (4 NC keys): the interface
  default is overridden by `TransactionRecord`, the only implementation —
  structurally unreachable. The record's own boundary is pinned by
  `exceedsSizeLimitBoundary` at exactly 1232/1233 bytes.
- `TransactionRecord.setBlockHash` 224/231: `instanceof TransactionRecord`
  is always true (single implementation); the mutated else-branch routes
  through the public `setRecentBlockHash` with identical bytes, and the
  unmutated else-branch line is unreachable (NC).

**Result-identical routing**:
- `Transaction.createTx` 386: one table meta forced through the generic
  multi-table path builds the same transaction the single-table shortcut
  does.
- `Transaction.createTx` 494: when every table has indexed accounts, the
  filtered table serializer emits the same bytes as the direct loop.
- `InstructionRecord.extraAccounts` 27: a one-account list through the
  general join path yields an equal record; only the allocation differs.
- `TransactionRecord.sign` 154: the multi-signer scan resolves a single
  signer to the same slot the fast path uses.

**No-op displacement boundaries** (`createTx` 253/427, 255/429): at
`i == numIncludedAccounts` the compaction degenerates to a zero-length
arraycopy plus a self-assignment, and at `len == 1` the swap fast path and
a one-element arraycopy produce identical arrays — both directions of each
check are result-identical at the boundary. The real displacement paths
(single swap and `len > 1` arraycopy) are killed by the rank-displacement
shapes in `TransactionFactoryTests`.

**Redundant work**:
- `Transaction.createTx` 432: the multi-table compaction arraycopy shifts
  tail slots that hold already-consumed indexed accounts (captured inside
  the table metas via `addAccountIfExists`); only the front assignment is
  ever read back. The single-table path's identical-looking arraycopy is
  load-bearing — its tail feeds lookup-index serialization — and its
  removal mutant dies.
- `InstructionRecord.equals` 171: the `len` equality is a fast path; the
  ranged `Arrays.equals` re-checks range lengths, so no input can pass one
  and fail the other.
- `InstructionRecord.toString` 188 (3 keys): at `len == 0` the base64 of an
  empty range equals the `""` fast-path constant.

**Dead defensive code**:
- `TransactionRecord.lambda$static$0` 29: `Map.merge` never invokes the
  remapping function with a null existing value.
- `TransactionRecord.sign` 156: widening the signer scan by one slot probes
  an account that cannot equal a distinct signer key.
- `AccountIndexLookupTableView.compareTo` 25: forcing the `instanceof` view
  branch off routes through `toByteArray`, result-identical for views over
  one shared table — the only supported shape. NOTE the view-vs-view branch
  compares `this.lookupTable` against itself rather than
  `view.lookupTable`, so cross-table views compare incorrectly; flagged
  2026-07-18, behavior deliberately unpinned pending an owner decision.

## Untriaged debt (tx suite)

- `TransactionSkeleton.deserializeSkeleton` (7 keys) and
  `TransactionSkeletonRecord` (6 keys): the long-standing skeleton
  survivors — offset arithmetic and parse boundaries a length assertion
  cannot distinguish (see the Transaction hardening section of
  `AGENTS.md`). Equivalence-triage candidates rather than kill candidates.

Packages without a suite are deliberate scope decisions (see
`build.gradle.kts`), not omissions.

Shrinking the baseline is always an improvement; growing it requires a
reason here.
