# Plan: remove Bouncy Castle from production

Status: planned; implementation has not started. Last assessed: 2026-09-14.

Replace production Bouncy Castle (BC) usage with JDK APIs or narrowly scoped local
code. **Keep BC in production for Argon2id until an appropriate JDK implementation
is available on Sava's supported Java baseline. Do not port Argon2id or BLAKE2b
locally.**

**Retain BC as an ongoing test-only dependency after the production migration.**
Use it for differential regression tests of the JDK and local replacements,
alongside independent vectors and fixtures. The final goal is a BC-free production
dependency graph, not removal of BC from the test environment.

Use JDK Ed25519 verification with local input checks. Preserve the existing local
public-key derivation and PDA membership code, supplying its remaining arithmetic
helpers locally. Add a private RIPEMD-160 implementation for `Hash.h160`.

This is first-party defensive maintenance of Sava's cryptographic dependencies.
The work must preserve derived keys, signatures from ordinary generated keys,
PDA results, and existing encrypted key files. Verification edge cases require the
explicit contract treatment described below. This document does not implement
changes or claim that migration validation has passed.

## Current boundary

The project targets Java 25 and its selected BOM pins BC 1.85.2. Production BC
imports are confined to six classes in `sava-core`, plus the module requirement.
`sava-rpc` and `sava-vanity` consume core functionality and need downstream checks
where affected; implementation work remains within the three Sava modules.

| Current use | Destination |
| --- | --- |
| [PublicKey](../sava-core/src/main/java/software/sava/core/accounts/PublicKey.java) raw-key signature verification | SunEC Ed25519 verification, with local parsing and error handling |
| [Ed25519Util](../sava-core/src/main/java/software/sava/core/crypto/ed25519/Ed25519Util.java) and [Scalar25519](../sava-core/src/main/java/software/sava/core/crypto/ed25519/Scalar25519.java) field/integer helpers | Private local arithmetic helpers |
| [Signer](../sava-core/src/main/java/software/sava/core/accounts/Signer.java) public-key generation and generated-key validation | Existing local derivation plus reviewed validation invariants |
| [Hash.h160](../sava-core/src/main/java/software/sava/core/crypto/Hash.java) | JDK SHA-256 followed by private local RIPEMD-160 |
| [Argon2id](../sava-core/src/main/java/software/sava/core/accounts/pbkdf/Argon2id.java) | Retain BC now; migrate to JDK later |
| [module-info.java](../sava-core/src/main/java/module-info.java) | Retain `requires org.bouncycastle.provider` until the final removal gate |

Signing, SHA-256/512, HMAC, PBKDF2, and AES-GCM already use JDK APIs. PDA curve
membership already uses local arithmetic. Neither needs a replacement algorithm.

## Rough size and risk estimates

These are planning ranges for **nonblank Java lines added or materially changed**,
including tests, fuzz harnesses, and benchmarks in their own column. They are not
net repository growth, elapsed-time estimates, or exact dependency-closure counts.
Unchanged code, license headers, generated vector data, and documentation are
excluded. The existing roughly 1,000-line `Ed25519Util` is retained and is not
counted as new code. Rows are intended to be disjoint.

| Work item | Production lines | Test / harness / benchmark lines | Principal risk |
| --- | ---: | ---: | --- |
| JDK verification adapter and input checks | 80–180 | 250–500 | **High compatibility risk:** accepted signatures and exception behavior can differ |
| Local field arithmetic, inversion, and integer helpers | 1,000–1,600 | 250–500 | **High correctness risk:** wrong keys, secret-dependent arithmetic behavior, or vanity throughput regression |
| `Signer` derivation and validation cleanup | 30–80 | 100–200 | **Medium–high risk:** accidentally weakening key-pair validation or changing input handling |
| Private RIPEMD-160 and `Hash.h160` wiring | 300–650 | 150–300 | **Medium correctness risk:** endian, round, padding, or composition errors |
| Retain and extend BC differential test coverage | 0 | 100–200 | **Medium assurance risk:** shared algorithm ancestry can hide defects; independent vectors remain necessary |
| **Subtotal before JDK Argon2id migration** | **1,410–2,510** | **850–1,700** | Field arithmetic and verification deserve the most review |

The differential-coverage row covers shared harness and existing-oracle changes;
algorithm-specific comparisons are included in their respective rows above.
Allow roughly another 20–50 lines of build/module configuration changes at final
production removal, including retaining BC in test configurations. Estimate the
future JDK Argon2id adapter separately once its supported API is settled; there is
no local Argon2id/BLAKE2b implementation in this scope.

The arithmetic allowance is grounded in the installed BC 1.85.2 sources:
`X25519Field` and `Mod` total 1,485 physical lines before removal of unused methods,
comments, and blanks. Required small helper extracts add some code back. For
RIPEMD-160, `RIPEMD160Digest` and `GeneralDigest` total 627 physical lines; a private
one-shot implementation can be smaller. These source counts explain the ranges,
but are not measurements of a completed port.

## Work that can proceed before JDK Argon2id

### 1. Move raw-key signature verification to the JDK

Use the existing raw-key conversion machinery and SunEC's Ed25519 `Signature`
implementation. Keep the local adapter responsible for rejecting noncanonical and
pure small-order public keys and for preserving the raw-key API's malformed-input
behavior. Normalize malformed-signature outcomes where BC currently returns
`false`; keep argument errors and provider/configuration failures distinguishable.
Do not broadly catch every exception and turn programming or runtime failures
into failed verification.

The Java-key overloads already use JCA. Their current exception behavior is a
separate published contract; migrating the raw-key overloads does not implicitly
authorize changing those overloads too.

An isolated assessment probe using OpenJDK 25.0.2 and BC 1.85.2 observed:

| Input, with an empty message | BC raw verifier | SunEC without Sava's proposed checks |
| --- | --- | --- |
| RFC 8032's first Ed25519 test vector | `true` | `true` |
| Valid public key, 63- or 65-byte signature | `false` | `SignatureException` |
| Valid public key, noncanonical scalar `S` | `false` | `SignatureException` |
| Identity public key, identity `R`, zero `S` | `IllegalArgumentException` | `true` |

Local input checks address the identity example; it does not require a local full
verifier. There is also a deeper acceptance difference: BC 1.85.2's verifier clears
the cofactor, while SunEC checks the equation directly. Some signatures involving
torsion components can therefore pass BC and fail SunEC even after matching input
checks. This source-level distinction still needs explicit mixed-order test
vectors; the probe above did not establish that whole difference.

**Chosen direction:** let SunEC define signature acceptance after the local
checks. Document and review the exact behavioral delta before changing existing
published raw-key overloads, following [AGENTS.md](../AGENTS.md). Do not describe
the adapter as exactly BC-compatible or as reproducing Solana runtime verification
without separate evidence. Do not vendor a full verifier to emulate BC's equation.

Validate with RFC 8032 vectors, existing signing fixtures, modified messages and
signatures, offset/range cases, noncanonical encodings, pure small-order keys, and
mixed-order cases. Retain BC comparisons, with explicit expected outcomes for
reviewed acceptance differences rather than requiring blanket equality. Benchmark
verification separately from public-key conversion.

### 2. Complete the local public-key derivation implementation

Extract only the used field and inversion operations and the small `Nat`,
`Interleave`, and bit-manipulation helpers. Replace trivial utilities with JDK
operations where equivalent. Keep new classes package-private and retain source
provenance, license notices, and a record of local changes. Avoid importing BC's
whole generic arithmetic or provider framework.

Preserve all existing `Ed25519Util.generatePublicKey` overloads, including offsets,
scratch-buffer use, and consumption of the caller's existing `MessageDigest`
state. The vanity workers reuse that path for every candidate. Do not replace it
with a `KeyPairGenerator` supplied by a specially scripted `SecureRandom`: the
existing test harness uses that technique as a checked oracle, not as a portable
production seed-import API.

Keep `isNotOnCurve` separate from signature-key validation. Its current membership
semantics include small-order and some noncanonical encodings, as documented in
[AGAVE_SYNC.md](../AGAVE_SYNC.md). A stricter JDK key validator would change PDA
derivation. Preserve the committed Solana curve fixture and the independent
mathematical oracle.

Arithmetic review must cover carry propagation, normalization, inversion,
conditional selection, and secret-dependent branches or memory access. Avoid
variable-time `BigInteger` arithmetic in secret-key production paths. Differential
tests establish output agreement, not a side-channel proof. Measure startup
precomputation, warmed key-generation throughput, and allocations before claiming
performance parity; the repository currently has no Ed25519 JMH baseline.

### 3. Remove the remaining `Signer` calls to BC

Route its remaining public-key derivation calls through the completed local
implementation and retain comparison with the supplied public key.

Both `validateKeyPair` overloads currently perform BC full validation on a freshly
derived public key. The small estimate assumes a reviewed argument that correct
clamped base-point derivation guarantees the property those checks enforce. Do
not substitute the PDA membership predicate or silently remove a validation step
just to eliminate an import. Resolve that invariant with tests and review before
the switch; a new general subgroup validator would require a revised estimate.

Pin mismatched key pairs, input lengths and offsets, existing copy/ownership
behavior, generated-key validity, and signatures produced by the resulting
signers. Retain independent RFC/JDK key-generation evidence, especially because
derivation and validation will share more local implementation code.

### 4. Implement private RIPEMD-160

No active OpenJDK implementation proposal or target release was found in the
2026-09-14 assessment. RIPEMD-160 is absent from the
[JDK provider algorithms](https://docs.oracle.com/en/java/javase/26/security/oracle-providers.html),
so this phase does not depend on a future JDK feature.

Keep `Hash.h160(input)` exactly `RIPEMD160(SHA256(input))`, returning 20 bytes.
Use a private helper with no new public digest API. A one-shot implementation is
sufficient; its RIPEMD input is always the 32-byte SHA-256 output. Preserve the
published method even though it currently has no production caller in this repo.

Use independently sourced RIPEMD-160/HASH160 known-answer vectors. Exercise SHA-256
input padding boundaries through `h160`; exercise RIPEMD padding boundaries only
if the private helper supports arbitrary input lengths. Preserve byte order and
existing argument behavior. The current randomized HASH160 oracle also uses BC,
so copying BC's algorithm does not make that comparison independent.

## Deferred: JDK Argon2id and final production dependency removal

As checked on 2026-09-14, OpenJDK has an
[Argon2id preview proposal](https://bugs.openjdk.org/browse/JDK-8377081) and an
[open implementation PR](https://github.com/openjdk/jdk/pull/29597). The
[implementation issue](https://bugs.openjdk.org/browse/JDK-8253914) lists JDK 28;
the JEP is still submitted and the PR is unmerged. Treat JDK 28 as a planning
target, not a delivery promise. The patch adds internal BLAKE2b support for Argon2id;
it does not register a standalone BLAKE2b `MessageDigest` service.

Keep BC and its module requirement while waiting. Continue normal dependency
maintenance. A preview implementation alone is not the removal gate: Sava must
adopt a supported minimum JDK that supplies the needed API, with a deliberate
project decision if preview APIs are still required. Prefer finalized support for
the published library. Recheck upstream status at that point.

The future JDK adapter must satisfy all of these conditions:

- Exact Argon2id v1.3 output for the same password bytes, salt, memory in KiB,
  parallelism, iterations, and output length, including memory/lane rounding.
- Explicit UTF-8 password conversion, current caller-array ownership and internal
  copy scrubbing, and the existing key-file parameter bounds.
- Compatibility with `KeyDerivation.derive` output sizing, including its current
  `keyBits / 8` behavior; input-validation changes are separate API decisions.
- Differential output tests against test-only BC, independent known-answer tests,
  and successful decryption of existing literal
  encrypted-file fixtures in [SignerTest](../sava-core/src/test/java/software/sava/core/accounts/SignerTest.java),
  including the Argon2id fixture. Keep file formats and defaults stable.
- Measured memory, CPU, and concurrency behavior at Sava's supported parameters,
  plus availability in both ordinary and packaged modular runtimes.

Once those conditions and the earlier phases pass, remove production BC imports,
the production module requirement, and production dependency declarations. Retain
BC through `testImplementation` or the equivalent dedicated test/fuzz configuration
where comparisons run; configure test module access without restoring a production
`requires org.bouncycastle.provider` declaration. Verify that BC remains available
to the regression harnesses but is absent from published production dependency
metadata and the production compile/runtime graphs for all three modules. A BOM
constraint on its own is not a runtime dependency; do not modify sibling projects
merely to remove an unused pin.

## Ongoing regression coverage with test-only BC

Keep the BC comparison suite after migration, not just during implementation:

- Compare public-key derivation and generated-key validity with BC across known
  vectors and deterministically generated seeds, including supported offsets and
  buffer usage.
- Compare signature verification results and malformed-input behavior. Require
  agreement for preserved contracts; pin each reviewed BC/SunEC acceptance
  difference with a named test and explicit expected outcomes for both paths.
  An unexplained mismatch is a regression to investigate, not an automatic reason
  to change Sava's contract or add an exclusion.
- Compare `Hash.h160` with BC's RIPEMD-160 composition over fixed vectors and
  deterministic inputs, including SHA-256 padding boundaries.
- Once the JDK Argon2id adapter exists, compare derived bytes with BC over a
  bounded parameter matrix covering password encoding, salt, output length, and
  memory/lane rounding. Retain the existing encrypted-file fixtures as a separate
  compatibility check.

Keep routine comparisons and committed fuzz-corpus replay in normal tests;
continue differential fuzzing with explicit resource bounds. Pin and maintain the
test-only BC version, rerunning comparisons when BC or the supported JDK changes.
Keep fixed historical vectors so an upstream behavior change cannot silently
redefine expected results.

BC comparisons catch divergence but do not prove correctness when local code was
derived from BC. Retain RFC vectors, independent mathematical checks, JDK oracles,
and committed Solana fixtures alongside them. Keep BC out of production code and
public API types throughout the completed migration.

## Validation and handoff

Implement each phase as a separately reviewable local change. Use ongoing BC
differential tests alongside independent oracles: RFC vectors, JDK derivation with
checked seed readback, mathematical field checks, and committed Solana fixtures.
Reusing copied arithmetic on both sides of a comparison is not independent
evidence. New or refreshed Solana reference
fixtures must follow the checkout/update rules in [AGENTS.md](../AGENTS.md).

Iterate with the affected module's tests. Before each implementation handoff, run
the mutation suites owning the changed code and relevant dependent callers.
Likely core suites are `pitestAccounts`, `pitestEd25519`, `pitestCrypto`,
`pitestVanity`, and `pitestTx`; the later Argon2id adapter also involves
`pitestPbkdf`. Determine actual reach for each diff rather than running every suite
for every phase. New production classes also require `mutationOwnershipAudit`.
Use the installed plugin's `hardeningHelp` and the repository's existing hardening
contract for task behavior and mutation-record decisions.

Run relevant differential fuzz campaigns with explicit duration and concurrency
bounds; retain committed seeds and deterministic replay. Complete the normal
local release certification and bounded fuzz campaign before publishing the
finished migration. Performance measurements belong in benchmarks, not
timing-sensitive unit-test assertions.

The principal tradeoff is ownership: JDK verification avoids maintaining a full
signature verifier, while local field arithmetic and RIPEMD-160 become Sava's
maintenance responsibility. Waiting for JDK Argon2id avoids adding a second local
cryptographic subsystem. Until that final gate passes, BC remains a production
dependency; afterward it remains test-only to detect regressions.
