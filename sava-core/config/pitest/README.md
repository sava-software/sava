# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,mutator,STATUS` — line numbers are metadata, carried as
a trailing `# line N` tag every refresh rewrites, so editing above a mutated
method churns nothing. Full policy — the three legal outcomes for a new
survivor, determinism requirements, targeting rules — lives in sava-build's
`HARDENING.md`.

## Newly adopted suites — 2026-08-04, seeded debt, not acceptance

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
for the Base58 encode family, `# extension null guard` for `parseExtensions`,
`# pack25519 passes` for the dropped `car25519` passes):
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
`TransactionRecordPlumbingTests`) removed 142 of them; the 27 keys below
are accepted equivalents, and the 13 skeleton keys under Untriaged debt
are all that remain unclassified.

**Shadowed defaults / single-implementation dispatch** — baseline label
`# shadowed default`:
- `Transaction.exceedsSizeLimit` line 652 (4 NC keys): the interface
  default is overridden by `TransactionRecord`, the only implementation —
  structurally unreachable. The record's own boundary is pinned by
  `exceedsSizeLimitBoundary` at exactly 1232/1233 bytes.
- `TransactionRecord.setBlockHash` 224/231: `instanceof TransactionRecord`
  is always true (single implementation); the mutated else-branch routes
  through the public `setRecentBlockHash` with identical bytes, and the
  unmutated else-branch line is unreachable (NC).

**Result-identical routing** — baseline label `# result identical routing`:
- `Transaction.createTx` 386: one table meta forced through the generic
  multi-table path builds the same transaction the single-table shortcut
  does.
- `Transaction.createTx` 494: when every table has indexed accounts, the
  filtered table serializer emits the same bytes as the direct loop.
- `InstructionRecord.extraAccounts` 27: a one-account list through the
  general join path yields an equal record; only the allocation differs.
- `TransactionRecord.sign` 154: the multi-signer scan resolves a single
  signer to the same slot the fast path uses.

**No-op displacement boundaries** — baseline label `# displacement boundary`
(`createTx` 253/427, 255/429): at
`i == numIncludedAccounts` the compaction degenerates to a zero-length
arraycopy plus a self-assignment, and at `len == 1` the swap fast path and
a one-element arraycopy produce identical arrays — both directions of each
check are result-identical at the boundary. The real displacement paths
(single swap and `len > 1` arraycopy) are killed by the rank-displacement
shapes in `TransactionFactoryTests`.

**Redundant work** — baseline label `# redundant work`:
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

**Dead defensive code** — baseline label `# dead defensive`:
- `TransactionRecord.lambda$static$0` 29: `Map.merge` never invokes the
  remapping function with a null existing value.
- `TransactionRecord.sign` 156: widening the signer scan by one slot probes
  an account that cannot equal a distinct signer key.
- `AccountIndexLookupTableView.compareTo` 25: forcing the `instanceof` view
  branch off routes through `toByteArray`, result-identical for every pair of
  views since the 2026-07-21 fix. (The view-vs-view branch used to compare
  `this.lookupTable` against itself rather than `view.lookupTable` — flagged
  2026-07-18, owner-approved and fixed 2026-07-21, cross-table ordering
  pinned by `viewCompareToReadsTheOtherViewsBackingTable`.)

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

**ed25519** (3, all `Ed25519Util`; read `TIMED_OUT` identically solo and under
gate load in the 2026-07-22 mode comparison)
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

**vanity** (4, cut from 8 on 2026-08-08 — see "the deterministic seam" below)

Every mask worker's search is a `for (;;)` with exactly **two** exits: "found
enough" (`foundHitLimitOrInterrupted` / `foundLimitOrInterrupted`) and "cap
reached" (`searchExhausted(attempts)`, the bounded-attempts seam whose javadoc
names tests as its reason for existing). All four members below disable the cap
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

`SubsequenceRecord.formatCharOptions:148` (`ORDER_IF`) was retired in the same
pass for a different reason: the member matches no mutant in the current report
at all — `formatCharOptions` now yields only `ORDER_ELSE` and
`ConditionalsBoundary` at that line, both `KILLED`. This was the known
`KILLED`↔`TIMED_OUT` flapper in the `HARDENING_NOTES.md` mode comparisons.

The fixture bound is worth recording explicitly, since the plugin asks whether a
claimed bound can fail first: `MAX_SEARCHES` is *not* the oracle for the four
members that remain. For them it is the seam that had to be exhausted before
liveness could be claimed at all, and the mutant's whole effect is to make it
unreachable — which is exactly why the watchdog is the only remaining observer.
