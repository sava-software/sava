# Agave Sync Guide

sava re-implements Solana wire formats, account layouts, and the JSON-RPC API in Java.
The canonical definitions live in the Agave validator codebase and its interface crates.
This document maps each sava surface to its canonical source so sync-and-test tasks can go
straight to the right files on both sides.

## Repo scope

Work in this repo is scoped to the three sava modules: **sava-core, sava-rpc, sava-vanity**.
`settings.gradle.kts` may composite the JSON parser source via
`includeBuild("../json-iterator")` during local development, which makes its sources and
gradle tasks reachable from here — but json-iterator is a separate project that owns its
own tests, PIT targets, and fuzz harnesses. When sava work surfaces a coverage gap or a
suspected bug in json-iterator, report it to the user; do not add tests, harness code, or
fixes in that repo from a sava task.

## Reference repositories

The canonical sources are these upstream repos. Clone them wherever you prefer (keep them
outside this repo, or in a git-ignored location); if a clone may already exist on the
machine, ask the user where before cloning. **Only clone a reference repo when the current
task actually needs context from it**, and run `git pull` in an existing clone before
comparing.

- **agave** — `https://github.com/anza-xyz/agave.git` — the validator: RPC server,
  account-decoder, storage-proto.
- **solana-sdk** — `https://github.com/anza-xyz/solana-sdk.git` — the `solana-*` interface
  crates extracted from agave's old `sdk/`: `transaction-error`, `pubkey`, `message`,
  `transaction`, `clock`, `epoch-rewards`, `short-vec`, `address-lookup-table-interface`,
  `compute-budget-interface`, and friends.
- **solana-com** — `https://github.com/solana-foundation/solana-com` — solana.com docs; RPC
  method pages at `apps/docs/content/docs/en/rpc/http/*.mdx` and
  `apps/docs/content/docs/en/rpc/websocket/*.mdx` (canonical request/response examples in
  `jsonc !response` blocks).

Repo-relative paths below are prefixed with the repo name (e.g. `agave:rpc/src/rpc.rs`).

Related sava project: **idl-clients** (`git@github.com:sava-software/idl-clients.git`). Its
`idl-clients-spl` module holds generated clients and account types for the SPL/native
programs (token, token-2022, system, stake, compute-budget, associated-token,
address-lookup-table, memo, stake-pool, precompiles) — including `Multisig` and
instruction builders. Before adding "core" program functionality to this repo, check
whether it already exists there; only sync it here if this repo already models it.

Agave's `sdk/` directory is a stub; most type definitions now live in the solana-sdk repo
or SPL interface crates, with exact versions pinned in agave's root `Cargo.toml` (search
the crate name). When a struct
is not vendored in the clone, agave's `account-decoder/src/parse_*.rs` files re-serialize
the interface-crate structs and are the practical reference for field names, order, and
types. Pinned versions worth checking on each sync: `spl-token-interface`,
`spl-token-2022-interface`, `spl-token-group-interface`, `spl-token-metadata-interface`,
`solana-zk-sdk`.

## Token-2022 extensions

Java: `sava-core/src/main/java/software/sava/core/accounts/token/`
- `extensions/ExtensionType.java` — DEPRECATED enum of all 29 on-chain extension types,
  ordinals 0 (`Uninitialized`) through 28 (`PermissionedBurn`); it duplicates the sealed
  `TokenExtension` hierarchy, which is the migration target. While it exists it **must
  stay ordinal-aligned with the Rust `ExtensionType` enum**: new SPL extensions are
  appended, so new Java entries must be appended in the same order. The live model is the
  `Set<TokenExtension>` on `Token2022`/`Token2022Account`: users iterate it and switch on
  the sealed type; `UnknownTokenExtension(type, data)` entries keep parsing alive across
  new SPL releases and round-trip through `write`. The deprecated `extensions()` map
  drops unknown entries since they cannot be keyed by the enum; a future release removes
  the map method and the enum. Append new enum entries promptly so the dispatch switch
  parses new extensions typed.
- `extensions/*.java` — one record per extension with a static `read(data, offset)` and a
  `write(data, offset)`/`l()` pair. Variable-length extensions
  (`ConfidentialTransferFeeConfig`, `ConfidentialTransferFeeAmount`) take an end bound in
  `read`.
- `Token2022.java` — mint parsing: base `Mint` (82 bytes) + 83 bytes padding + 1 accountType
  byte, then TLV entries (`u16 LE type`, `u16 LE length`, payload — both read unsigned).
  The extension dispatch switch must be exhaustive; a zeroed type terminates parsing
  (trailing re-allocated but uninitialized space) while retaining extensions already
  parsed; a type past the enum yields `UnknownTokenExtension`; an unknown extension whose
  claimed length overshoots the data end throws `IndexOutOfBoundsException` like known
  extensions do. See Token-2022 hardening below.
- `Token2022Account.java` — token account parsing: base `TokenAccount` (165 bytes) + 1
  accountType byte, then TLV entries.

Agave/SPL canonical sources:
- `agave:account-decoder/src/parse_token_extension.rs` — the `parse_extension` match lists
  every extension agave supports; the `convert_*` functions and the `Ui*` structs in
  `agave:account-decoder-client-types/src/token.rs` give field names, order, and widths.
  **To check for new extensions: confirm the match in `parse_extension` still ends where
  `ExtensionType.java` ends.**
- `spl-token-2022-interface` crate (version pinned in agave `Cargo.toml`) defines the actual
  packed structs. Layout conventions: `OptionalNonZeroPubkey` = 32 bytes with all-zero
  meaning none; `PodBool` = 1 byte; ElGamal pubkey = 32; ElGamal ciphertext = 64;
  AE (decryptable) ciphertext = 36; integers little-endian; `f64` for scaled-UI multipliers.
- `TokenMetadata` fields are borsh (u32 length-prefixed UTF-8 strings) per
  `spl-token-metadata-interface`; `TokenGroup`/`TokenGroupMember` per
  `spl-token-group-interface`.

Optional-pubkey semantics: Rust maps all-zero to `None`; the Java records keep the raw
32-byte key. This is intentional — callers defensively check `null` and `PublicKey.NONE`.

Tests: `sava-core/src/test/java/software/sava/core/token/extensions/`
- `ExtensionRoundTripTests.java` — write→read round trips for all 29 types through
  `Token2022`/`Token2022Account`, asserting `write(data, 0) == l()`; plus UTF-8 metadata and
  trailing-padding behavior. **Add every new extension here.**
- `ParseExtensionsTests.java` — real mainnet base64 fixtures (PYUSD mint, confidential token
  account) plus malformed-input regressions (signed-length loop, corrupt metadata counts,
  overshooting unknown lengths). Prefer adding real account fixtures when a new extension
  ships on mainnet.
- `ExtensionEdgeCaseTests.java` — the mutation-testing backstop: null/empty guards on every
  reader, boolean-polarity round trips, per-field equals/hashCode variants for the five
  hand-written-equality classes, enum-boundary ordinals, option-gated fields, dirty-buffer
  writes, `TokenAccount` filters.

## HTTP RPC API

Java: `sava-rpc/src/main/java/software/sava/rpc/json/http/client/`
- `SolanaRpcClient.java` — public interface (method signatures + default commitment
  overloads).
- `SolanaJsonRpcClient.java` — request bodies are built as literal JSON strings here; **this
  is the file to diff against agave method names**.
- Response parsing is hand-rolled per record (see Response records below); no codegen.

Agave canonical sources:
- `agave:rpc/src/rpc.rs` — server method registrations via `#[rpc(meta, name = "...")]`;
  the authoritative method list.
- `agave:rpc-client-types/src/config.rs` — request config shapes (`RpcAccountInfoConfig`,
  `RpcSendTransactionConfig`, …). `rpc-client-api` merely re-exports these.
- `agave:rpc-client-types/src/response.rs` — response shapes (`RpcResponseContext`,
  `RpcBlockhash`, `RpcSupply`, `RpcVoteAccountStatus`, …).

Coverage note (2026-07): the Java client implements the full active agave method set except
`getAgGenesisCert`; agave's deprecated methods are intentionally not implemented.

Tests: `sava-rpc/src/test/java/software/sava/rpc/json/http/client/`
- `RoundTripRpcRequestTests.java` — one test per RPC method against a mock HTTP server,
  asserting the exact request JSON and response parsing. **Add a test here for every new
  method.**
- `ParseRpcResponseTests.java`, `ParseTXTests.java` — parse captured responses.
- Golden fixtures: `sava-rpc/src/test/resources/rpc_response_data/*.json(.zip)` — real
  agave responses (getBlock, getProgramAccounts, getVoteAccounts, …). These detect response
  shape drift; refresh them from a live node when agave changes a shape.
- `./gradlew :sava-rpc:pitestResponses` — PIT over the response package (sava-rpc has its
  own `hardening {}` block). Baseline 2026-07-18: 524 mutations (down from 903 after the
  deprecated-accessor removals), **98% detected, 1 without coverage**;
  `response/ParseCustomErrorCodeTests` pins the `RpcCustomError` long-code range guards
  with codes that alias real codes under `(int)` truncation (`code ± (1L << 32)`, both
  overloads, both sides). Originally driven from 72%/143 by
  `response/ParseResponseFieldTests` — synthetic
  whitebox JSON per record asserting every field (the tests live inside the target
  package, kept out of the mutation set via the suite's `excludedClasses`). Techniques
  that matter here: a trailing decoy field of the same JSON type with a different value
  kills always-match dispatch mutants; a leading unknown field kills stop-iteration
  mutants; zero-value probes pin the `< 0` absent-sentinels; `assertSame` pins sentinel
  identity. The 15 remaining are classified equivalent (int-clamp boundaries, unsigned
  reinterpret at zero, logging/capacity). Fixed 2026-07-17 after the first baseline:
  `TxStatus.parse`'s nil-status dedup compared `whatIsNext()` against Java null instead of
  `ValueType.NULL` and so never fired, and `TxSimulation.unitsConsumed` read an unguarded
  `readInt` where `fee` skips non-numbers. Known parser quirks, pinned not changed:
  `JsonUtil.parseEncodedData`'s single-element-array branch always throws (real providers
  send `[data, encoding]` pairs); `BlockCommitment` keeps its doubled buffer when a
  commitment array exceeds 32 entries. A JSON fuzz harness would add little at this
  strength — revisit only if the parsers start accepting deeper provider-controlled
  structures.

## WebSocket API

Java: `sava-rpc/src/main/java/software/sava/rpc/json/http/ws/`
- `SolanaRpcWebsocket.java` (interface), `SolanaJsonRpcWebsocket.java` (impl),
  `Channel.java` — enum whose names derive the `<name>Subscribe`/`<name>Unsubscribe` method
  strings: account, logs, program, root, signature, slot.
- A generic `subscribe(...)` passthrough covers non-standard methods (e.g. Helius
  `transactionSubscribe`).

Agave canonical source: `agave:rpc/src/rpc_pubsub.rs` — subscription registrations. Agave also
serves `slotsUpdatesSubscribe`, `blockSubscribe`, and `voteSubscribe`, which have no typed
Java wrapper yet (reachable via the generic passthrough).

Tests: `sava-rpc/src/test/java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocketTests.java`
and `SolanaRpcWebsocketTests.java` (subscription lifecycle and framing).

## Errors: TransactionError, InstructionError, RPC custom errors

Java: `sava-rpc/src/main/java/software/sava/rpc/json/http/response/`
- `TransactionError.java` — sealed interface, one record/singleton per variant.
- `IxError.java` — InstructionError variants (incl. `Custom(u32)`).
- `RpcCustomError.java` — JSON-RPC custom error codes `-32001..-32021`.

Canonical sources:
- `TransactionError`/`InstructionError` enums are NOT in agave — they live in the
  solana-sdk repo, `solana-sdk:transaction-error/src/lib.rs`. A secondary in-agave mirror is
  `agave:storage-proto/proto/transaction_by_addr.proto`, which enumerates the variants for
  BigTable storage. New variants are appended to the Rust enums; JSON serialization uses the
  variant name (unit variants as strings, data variants as single-key objects).
- Custom codes: `agave:rpc-client-api/src/custom_error.rs` — `JSON_RPC_SERVER_ERROR_*`
  constants and the `RpcCustomError` → JSON conversion. In sync through `-32021` as of
  2026-07.

Tests: `client/ParseTransactionErrorTests.java`, `client/ParseCustomRpcErrorTests.java`.

## Response records and JSON parsing conventions

Java: `sava-rpc/src/main/java/software/sava/rpc/json/http/response/` (~58 records).
Parsing is hand-rolled with the `systems.comodal.json_iterator` library: each record has a
static `parse(JsonIterator ji)` plus a `FieldBufferPredicate` switching on
`fieldEquals(...)`. There is no code generation — adding a response field means editing the
record components and its parser predicate by hand, then covering it via a fixture in
`rpc_response_data/`.

Enum mirrors in this package: `RewardType` and the confirmation status strings map to
`agave:transaction-status-client-types/src/lib.rs` (`RewardType`, `TransactionConfirmationStatus`,
`UiTransactionEncoding`).

## Other sync surfaces (sava-core)

| Java (under `sava-core/.../software/sava/core/`) | Models | Canonical source |
|---|---|---|
| `accounts/SolanaAccounts.java` | native/builtin program IDs, SPL program IDs, all sysvar addresses | `agave:reserved-account-keys/src/lib.rs` (`RESERVED_ACCOUNTS`); IDs originate in `solana-sdk:sdk-ids/` |
| `accounts/lookup/AddressLookupTable.java` (+ overlay/root variants) | ALT account layout: 56-byte meta, 256 max addresses, `deactivationSlot == u64::MAX` = active | `solana-sdk:address-lookup-table-interface/`; `agave:account-decoder/src/parse_address_lookup_table.rs` |
| `accounts/sysvar/Clock.java` | Clock sysvar (40 bytes) | `solana-sdk:clock/`; `agave:account-decoder/src/parse_sysvar.rs` `UiClock` |
| `accounts/sysvar/EpochRewards.java` | EpochRewards sysvar (has in-source sync link) | `solana-sdk:epoch-rewards/`; `agave:account-decoder/src/parse_sysvar.rs` `UiEpochRewards` |
| `accounts/token/Mint.java` | SPL Mint, 82-byte packed layout with u32-tag COptions | `spl-token-interface` `state::Mint`; `agave:account-decoder/src/parse_token.rs` |
| `accounts/token/TokenAccount.java`, `AccountState.java` | SPL Account, 165 bytes, explicit memcmp offsets used for `getProgramAccounts` filters | `spl-token-interface` `state::Account`/`AccountState` |
| `tx/Transaction*.java`, `tx/TransactionSkeleton*.java` | legacy + v0 message wire format: 3-byte header, `0x80` version bit, compact-u16 arrays, address-table lookups | `solana-sdk:message/`, `solana-sdk:transaction/`; nearest in-agave parser: `agave:transaction-view/` |
| `encoding/CompactU16Encoding.java` | short_vec / ShortU16 encoding | `solana-sdk:short-vec/` |
| `rpc/Filter.java`, `MemCmpFilter.java`, `DataSizeFilter.java` | `getProgramAccounts` filters; 128-byte memcmp cap | `agave:rpc-client-api/src/filter.rs` + server enforcement in `agave:rpc/` |
| `zk/ElGamal.java` | ElGamal/Pedersen/AE byte-length constants used by confidential extensions | `solana-zk-sdk` `encryption::*` (agave repo `zk-sdk/` or crates.io) |
| `accounts/PublicKey.java` | PDA derivation (`MAX_SEED_LENGTH=32`, `MAX_SEEDS=16`, `"ProgramDerivedAddress"` marker, off-curve check) | `solana-sdk:pubkey/` |
| `crypto/ed25519/Ed25519Util.java` | ed25519 decompression verdict backing the PDA off-curve check, plus public-key derivation for `Signer` | TweetNaCl/curve25519-dalek `decompress` semantics per `solana-sdk:pubkey/` `is_on_curve` — see Ed25519 hardening below |
| `borsh/Borsh.java`, `borsh/RustEnum.java` | borsh spec: u32-prefixed strings/vecs, 1-byte Option tags, enum discriminants — see Borsh hardening below | `borsh` crate spec as used by agave/SPL |
| `programs/Discriminator.java` | 8-byte Anchor sighash, 4-byte native enum tags | Anchor framework convention (not agave) |

Existing sava-core tests: `tx/TransactionSerializationTests.java`,
`accounts/lookup/AddressLookupTableTests.java`, `accounts/PublicKeyTest.java`,
`crypto/ed25519/Ed25519UtilTests.java`, `token/TokenStateRoundTripTests.java`,
`borsh/BorshTests.java` (matrix families), `borsh/BorshCoreTests.java`,
`borsh/BorshPrimitiveVectorTests.java`, `borsh/BorshReferenceVectorTests.java`,
`borsh/RustEnumTests.java`, `encoding/CompactU16EncodingTest.java`, `encoding/Base58Tests.java`,
`encoding/ByteUtilTests.java`, `encoding/JexTests.java`, plus the token extension tests above.

## Transaction hardening (sava-core `tx/`)

Transaction wire parsing is the widest untrusted-input surface in the library (RPC
responses, user-pasted base64). Malformed-input contract: **garbage in → `RuntimeException`
out**. `TransactionSkeleton.deserializeSkeleton` and the parse methods walk raw offsets and
may throw `IllegalArgumentException`, `ArrayIndexOutOfBoundsException`, or
`NegativeArraySizeException` on hostile bytes; callers must not assume otherwise. Nothing
guarantees a *typed* rejection — see the `CompactU16Encoding.decode` leniency gap below.

- `./gradlew :sava-core:pitestTx` — PIT over `TransactionSkeleton`,
  `TransactionSkeletonRecord`, `TransactionRecord`, `InstructionRecord`, and the four
  `accounts/lookup/AddressLookupTable*` classes (folded in 2026-07-16 — versioned skeleton
  parsing consumes tables, and they parse the same untrusted account data). Baseline
  2026-07-16: 634 mutations, 94% detected, **1** without coverage —
  `TransactionRecord.setBlockHash`'s non-`TransactionRecord` branch, unreachable without a
  foreign `Transaction` implementation. The lookup-table classes stand at zero unkilled;
  the 38 survivors in the tx classes are mostly offset arithmetic that a length assertion
  cannot distinguish; treat any *new* no-coverage entry as a gap to close.
- `./gradlew :sava-core:fuzzTxSkeleton -PmaxFuzzTime=<seconds>` — Jazzer over
  `TransactionSkeletonFuzz`: tolerates any `RuntimeException` from deserialization and the
  parsers, so what it hunts is what the contract forbids — hangs, memory exhaustion, and
  any non-`RuntimeException` throwable — plus cross-method invariants that must hold
  whenever a parse fully succeeds, including a differential check of
  `AddressLookupTable.read` between its eager reverse-lookup and lazy overlay views
  (the harness's class doc enumerates the invariants; the overlay differential found the
  loop-bound bug pinned by `danglingBytesAreFloored`). Committed seeds live in
  `src/test/resources/fuzz/txSkeleton` (real legacy + versioned/lookup-table transactions,
  plus `alt_account`, a real mainnet lookup table), wired via the plugin's `seedCorpus`
  property; the writable corpus persists in `build/fuzz/txSkeleton-corpus`. Worst-case
  allocation from a hostile header is bounded (~16MB, then AIOOBE) — verified under a
  512MB heap, so a large fuzzer RSS is Jazzer's own sizing, not a per-input bomb.

Tests: `TransactionSerializationTests` (round trips against real main-net transactions),
`TransactionSkeletonParseTests` (each narrow parse accessor cross-checked against the broad
`parseAccounts`/`parseInstructions` views), `InstructionBuildingTests` (account appends,
`beginsWith` including slice bounds, instruction splicing, size limit),
`TransactionSigningTests` (bulk and indexed signing cross-checked against one-by-one
signing). Prefer extending these over adding new fixtures — the cross-method invariant is
what catches offset bugs, and it has found two real ones: `serializedInstructionsLength`
skipping the program-index byte, and `CompactU16Encoding.decode` sign-extending a
three-byte length into a negative value that no bounds check ever caught (both fixed
2026-07-16).

## Token-2022 hardening (sava-core `accounts/token/`)

The TLV walker and 29 extension readers parse untrusted account data fetched over RPC;
same malformed-input contract as transactions: **garbage in → `RuntimeException` out**.
Fuzzing this surface (2026-07-16) found and fixed three parsing defects, each pinned by a
regression in `ParseExtensionsTests`:

- the u16 TLV type/length were read *signed* — a negative length walked the cursor
  backwards into an infinite loop, and a type ≥ `0x8000` crashed instead of reaching the
  `UnknownTokenExtension` escape hatch; the `& 0xFFFF` masks in `parseExtensions` are
  load-bearing.
- `TokenMetadata.read` sized `new Map.Entry[numExtras]` from the wire before any bounds
  check, and `OutOfMemoryError` is not a `RuntimeException`; counts exceeding the
  remaining bytes now throw `IllegalArgumentException` (negative counts keep throwing
  `NegativeArraySizeException` — exception types deliberately unchanged).
- an unknown extension's length overshooting the data end throws
  `IndexOutOfBoundsException` like known extensions do, instead of zero-padding a
  fabricated tail.

Verification tasks:

- `./gradlew :sava-core:pitestToken2022` — PIT over `software.sava.core.accounts.token.*`
  against the `software.sava.core.token.*` tests. Baseline 2026-07-16: 688 mutations, 97%
  detected, 0 without coverage; the 21 survivors are classified equivalent (mostly
  `31 * h + x` hashCode operator swaps only exact-hash assertions could kill).
- `./gradlew :sava-core:fuzzToken2022 -PmaxFuzzTime=<seconds>` — Jazzer over
  `Token2022Fuzz` (its class doc has the details): whenever a parse fully succeeds,
  re-serializing must consume exactly `l()` bytes and re-parse equal. Seeds in
  `src/test/resources/fuzz/token2022` are the PYUSD mint and confidential token account
  from `ParseExtensionsTests`; `maxLen = 2048`.

## Borsh hardening (sava-core `borsh/`)

`Borsh` and `RustEnum` deserialize untrusted account data in consumer projects' generated
clients (a 2026-07-16 deprecation of the surface was reversed the next day — it is
maintained). Same malformed-input contract as the other parsing surfaces: **garbage in →
`RuntimeException` out**. Hardening (2026-07-17) fixed two defect classes, pinned by
regressions:

- every `read*Vector`/`readMultiDimension*` read its u32 length prefix as a signed int
  and sized an allocation from it — a corrupt prefix forced up to multi-GB allocations
  (`OutOfMemoryError` is not a `RuntimeException`). All 36 vector readers now validate
  the length against the bytes actually present via the shared `readVectorLength`
  (elements need at least their fixed width; strings and Borsh-typed elements at least
  their minimum); negative lengths keep throwing `NegativeArraySizeException`.
- `RustEnum.EnumInt128.l()` returned 129 and `EnumInt256.l()` 257 — bits, not bytes —
  disagreeing with the 17/33 their `write` consumes, so any container sized by `l()`
  disagreed with its own serialization. Fixed; `RustEnumTests` asserts `l() == write()`
  for every variant shape.

Verification tasks:

- `./gradlew :sava-core:pitestBorsh` — PIT over `Borsh`, `RustEnum`, and their nested
  interfaces (globbed as `Borsh$*`/`RustEnum$*` to keep test classes out). Baseline
  2026-07-17: 1070 mutations, **100% killed, 0 without coverage** — hold this bar;
  `BorshTests` covers the matrix families, `BorshCoreTests` the string/byte primitives,
  `BorshPrimitiveVectorTests`/`BorshReferenceVectorTests` the 1-D families, checked
  writers, Optionals, and length bounds, `RustEnumTests` every enum variant. Writers are
  exercised at non-zero offsets into dirty buffers — offset-arithmetic and
  dropped-write mutants are invisible at offset 0 into zeroed arrays.
- `./gradlew :sava-core:fuzzBorsh -PmaxFuzzTime=<seconds>` — Jazzer over `BorshFuzz`:
  every read family over every input; a successful read must re-serialize into exactly
  the promised `len*` bytes and re-read equal. No seed corpus: the format is shallow
  (u32 length prefix + elements), so valid prefixes are reachable from scratch.

## Encoding hardening (sava-core `encoding/`)

`Base58`, `ByteUtil`, `CompactU16Encoding`, and `Jex` back money-critical byte handling
(addresses, transaction ids, wire lengths, fixed-width fields). Their tests follow a
differential-oracle convention — round-trip tests alone cannot catch a bug shared by an
encoder/decoder pair, so each suite checks against an independent reference. Keep the
convention when extending them:

- `Base58Tests` — cross-validated against an in-test `BigInteger` reference codec, Bitcoin
  Core's `base58_encode_decode.json` vectors, and known Solana program addresses (hex ↔
  base58). Includes adversarial 31/33-byte values that pin the exact-fit length check —
  base58 has no checksum, so that throw is the only guard against a corrupted address
  decoding to a different valid destination.
- `JexTests` — every entry-point family cross-validated against `java.util.HexFormat`.
- `CompactU16EncodingTest` — exhaustive sweep of every value through every entry point,
  plus the canonical byte vectors from `solana-sdk:short-vec/src/lib.rs`
  (`test_short_vec_encode_decode`).
- Decode-into tests use dirty (non-zero) output buffers so dropped writes are observable.
- Randomized tests seed a `Random` from `SecureRandom` and embed the seed in failure
  messages; replay a failure by pinning the seed.

Verification tasks (not part of `test`; run whenever these classes change). Both are
provided by the shared `software.sava.build.feature.hardening` convention plugin
(sava-build repo) and configured via the `hardening {}` block in
`sava-core/build.gradle.kts`:

- `./gradlew :sava-core:pitestEncoding` — PIT mutation testing of the four classes against
  their tests; report in `sava-core/build/reports/pitest/encoding`. Baseline (2026-07-16,
  Java 25 bytecode): 1064 mutations, 98% detected (a timed-out mutant — an induced
  infinite loop — counts as detected), 0 without coverage; the 25 survivors were
  individually verified equivalent. Any new survivor must be either killed with a test or
  classified equivalent with a reason.
- `./gradlew :sava-core:fuzzBase58 -PmaxFuzzTime=<seconds>` — Jazzer coverage-guided
  fuzzing of `Base58Fuzz`, a differential harness: every decode variant (String, char[],
  ASCII byte[], the decode-into forms against dirty buffers) and every encode variant
  (slice, mutableEncode, the begin/continue split at a fuzzer-chosen point) must agree
  with the String reference path, plus canonicality and rejection invariants. Input length
  is capped (`maxLen = 256` — the codec is O(n²) and all interesting boundaries are
  small); the corpus persists in `sava-core/build/fuzz/base58-corpus`, so runs accumulate.

Adding a fuzz target: give it a class with `public static void fuzzerTestOneInput(byte[])`
and no Jazzer imports (so it compiles with the regular test sources), register it in the
`hardening { fuzz.register("<name>") { ... } }` block with `targetClass`, an optional
`maxLen`, and — for any structured format — a `seedCorpus` directory of committed seed
inputs (`layout.projectDirectory.dir("src/test/resources/fuzz/<name>")`, one file per
input). The plugin passes `seedCorpus` to libFuzzer as a trailing read-only corpus:
replayed every run, but only newly interesting inputs are written back to the writable
`build/fuzz/<name>-corpus`. Omit `seedCorpus` only when every prefix of the input is
already valid (e.g. a raw codec like Base58); leaving a structured target seedless is the
single most common reason a fuzzer plateaus at low coverage.

Tooling notes (also explained by comments in the hardening plugin): the plugin recompiles
the main and test sources into one plain, module-info-free classpath root per tool —
`compileForPitest` at `hardening.mutationBytecodeRelease` into `build/mutation-classes`
and `compileForFuzz` at `hardening.bytecodeRelease` into `build/fuzz-classes`, both
defaulting to 25. Current PIT and Jazzer read Java 25 class files; the per-tool releases
exist to be lowered the next time either tool's bundled ASM lags a new class-file version
(when a tool silently loses instrumentation, Jazzer's symptom is flat `cov:` with "no
interesting inputs"). Tool versions default from sava-build's `gradle/libs.versions.toml`
so Dependabot keeps them current. PIT silently discards classpath roots whose path
contains the string "pitest"; do not rename `mutation-classes` to anything containing it.
When test classes live inside a targeted package glob, list them in the suite's
`excludedClasses` — PIT otherwise mutates the tests themselves, and assertion-removal
mutants in tests survive and corrupt the score.

## Ed25519 hardening (sava-core `crypto/ed25519/`)

`Ed25519Util.isNotOnCurve` gates PDA derivation (`PublicKey.findProgramAddress` rejects
on-curve candidates) and `generatePublicKey` backs `Signer` and the vanity workers — a
wrong verdict on either side is money-critical. The decompression semantics are
TweetNaCl/curve25519-dalek's, and therefore Solana's (`solana-sdk:pubkey/`
`is_on_curve`): mask the sign bit, reduce y mod p, decompress — small-order points and
the 19 non-canonical encodings (y ≥ p) are ON curve. This deliberately diverges from
BouncyCastle's `validatePublicKeyPartial`, which pre-rejects y ∈ {0, 1, p−1, the two
order-8 y values} and anything non-canonical; any BC-based differential must exclude that
set. Do not "fix" the divergence — it would change which PDAs derive.

Tests (`Ed25519UtilTests`) follow the differential-oracle convention with two independent
references: BouncyCastle `org.bouncycastle.math.ec.rfc8032.Ed25519` (decompression
verdicts on canonical points; key generation against random and structured seeds plus the
RFC 8032 §7.1 vectors) and an in-test `BigInteger` Euler-criterion oracle covering the
full input domain, including the torsion points and non-canonical encodings BC refuses to
judge.

- `./gradlew :sava-core:pitestEd25519` — PIT over `Ed25519Util`, `Scalar25519`, `Codec`.
  Baseline 2026-07-16: 940 mutations, 99% detected, 0 without coverage; the 11 survivors
  are equivalent-in-context (defensive carry passes and dead stores no input reachable
  through the public API can distinguish; two degrade a 256-bit equality to 128 bits,
  unkillable without a crafted collision).
- No fuzz target: inputs are fixed 32-byte arrays, so the random differential coverage
  already does what a fuzzer would.

## Alpenglow (upcoming consensus replacement)

Alpenglow (SIMD-0326 Votor consensus, plus SIMD-0357 VAT, SIMD-0384 migration, SIMD-0387
BLS vote keys, SIMD-0388 BLS syscalls — all in Review as of 2026-07) replaces TowerBFT and
PoH with off-chain BLS-signed votes and finalization certificates. Feature gates exist in
agave (`alpenglow` = `a1p3RiCfMmzm5jgCva97UUNwUiVLq5EJhtusRWHDBsp`) but are NOT activated.
**Policy: do not implement Alpenglow-specific surfaces until activation on main-net is
likely** (per project owner).

What changes for this library when it activates:
- `getAgGenesisCert` RPC (`agave:rpc/src/rpc.rs`) returns the genesis handoff certificate:
  `WireBlockCertMessage { block: {slot, blockId}, signature: BLS agg sig + validator rank
  bitmap }` (`agave:votor-messages/src/wire.rs`).
- jsonParsed vote accounts (`agave:account-decoder/src/parse_vote.rs`) gain
  `bls_pubkey_compressed` (48-byte BLS key, bs58) and the SIMD-0185 v4 commission/collector
  fields; `prior_voters` is always empty; the on-chain `votes` list empties out since
  consensus votes move off-chain.
- `voteSubscribe` stops reflecting consensus (it watches on-chain vote transactions, which
  cease); optimistic-confirmation reporting is suspended during migration and superseded by
  fast (80%, one round) / slow (60%, two rounds) finalization certificates.
- New vote instruction `VoteAuthorize::VoterWithBLS` (48-byte pubkey + 96-byte
  proof-of-possession over `"ALPENGLOW" || vote_pubkey || bls_pubkey`); BLS verification
  adds 34,500 CUs and the vote program leaves static builtin cost modeling.
- VAT (SIMD-0357): 1.6 SOL/epoch deducted from each vote account and burned; active
  validator set capped at 2,000.

Explicitly unchanged: all sysvar layouts (Clock, EpochSchedule, EpochRewards, Rent,
StakeHistory, SlotHashes, LastRestartSlot), the transaction wire format,
recent-blockhash expiry, user transaction fees, and `RewardType`. Canonical sources:
`agave:votor-messages/src/` (wire format, certificates, migration phases) and the SIMD
files in the solana-improvement-documents repo.

## Known gaps / candidate work

- HTTP RPC: `getAgGenesisCert` not implemented (deliberate — see Alpenglow above).
- WebSocket: typed wrappers for `slotsUpdatesSubscribe`, `blockSubscribe`, and
  `voteSubscribe` are deliberately omitted — public RPC infrastructure does not support
  those subscriptions, and `voteSubscribe` is obsolete under Alpenglow. The generic
  `SolanaRpcWebsocket.subscribe(...)` passthrough is the intended mechanism for
  provider-specific or unsupported methods; see the sava-software/helius-sdk repo
  (`software.sava.helius.ws.HeliusRpcWebsocket` wrapping Helius' `transactionSubscribe`)
  for a real-world example. Do not add typed wrappers without a supporting provider.
- `SolanaAccounts` deliberately omits deprecated/dormant reserved keys
  (`bpf_loader_deprecated`, `bpf_loader` v2, `loader_v4`, `native_loader`, `feature`,
  `incinerator`, `sysvar::rewards`) — do not add without need.
- Sysvar decoders: Clock and EpochRewards are public; Rent, EpochSchedule, StakeHistory,
  SlotHashes, LastRestartSlot are package-private (make public on demand). SlotHistory
  (131KB bit-vector) is not modeled. Fixture-backed tests in
  `sava-core/src/test/java/software/sava/core/accounts/sysvar/SysvarTests.java`.
- Multisig is not modeled here (generated type lives in idl-clients-spl).
- Live parser drift check: `DRIFT_CHECK=true ./gradlew :sava-rpc:test --tests
  '*LiveMainNetDriftCheck'` exercises the production parsers against current main-net
  responses; rate-limited methods are skipped and reported.
- Compute-budget instruction builders live outside sava-core; constants reference
  `agave:compute-budget/src/compute_budget_limits.rs` and
  `solana-sdk:compute-budget-interface/` (watch SIMD-0268 default changes).
- Watch for larger-transaction SIMDs: `Transaction.MAX_SERIALIZED_LENGTH=1232` and
  `MAX_ACCOUNTS=64` are already deprecated as not valid for all future versions.
- `CompactU16Encoding.decode`/`getByteLen(byte[], int)` are still lenient where agave's
  deserializer (`solana-sdk:short-vec/` `visit_byte`) is strict: agave rejects alias
  encodings (zero continuation bytes) and a continuation bit on byte three, while sava
  decodes whatever the bytes say (the third byte is masked to bits 14-15, so the result
  is always in `[0, 65535]`). Adopting agave's *strict* rejection (throw on a
  non-canonical encoding) is a deliberate non-goal for now — it would change a hot,
  mutation-tested primitive's contract from lenient-decode to reject-and-throw; do it
  only with the full pitest + fuzz re-verification, not as a drive-by.
- A legacy header carries no invoked indexes, so `parseAccounts()` types every read-only
  account `createRead` — the **account array** never marks programs invoked for legacy
  transactions (only the versioned path consults `invokedIndexes`; the legacy branch of
  `deserializeSkeleton` stores `LEGACY_INVOKED_INDEXES` and never collects them). The
  instruction accessors compensate: all four (`parseInstructions`,
  `parseInstructionsWithoutAccounts`, `filterInstructions`,
  `filterInstructionsWithoutAccounts`) mark an instruction's `programId` invoked, so their
  results are mutually `equals` — pinned by `legacyProgramAccountsAreInvoked`. Do not
  "simplify" `parseInstructions` back to reusing the account-array meta: it silently
  reintroduces the disagreement, and because `VO_META_COMPARATOR` ranks invoked accounts
  ahead of other read-only ones, a transaction rebuilt via
  `Transaction.createTx(feePayer, parseLegacyInstructions())` would order its accounts
  differently.
- `TokenAccount.read` has no null/empty-data guard — it NPEs where every other `FACTORY`
  reader (`Mint.read`, `Token2022.read`, `Token2022Account.read`,
  `AddressLookupTable.read`) returns null. Flagged 2026-07-16, deliberately unchanged —
  adding the guard is a behavior-visible change (NPE → null) for callers that relied on
  the throw.
- `Jex.decodeChecked(byte[]/ByteBuffer)` throws `ArrayIndexOutOfBoundsException` instead
  of `IllegalArgumentException` for negative (non-ASCII) input bytes; the char-based
  variants report correctly. Fix deferred (2026-07-15): read the byte as unsigned
  (`chars[c] & 0xFF`) so 128..255 fail the `> MAX_CHAR` check — an exception-type change,
  held back from patch releases per project owner.

## Last verified sync points

Each row records the reference repo commit the mirrored surfaces were last verified
against, and which surfaces that verification covered. Future syncs only need to review
`git diff <hash>..HEAD -- <watched paths>` in the reference clone — plus a full pass over
any surface NOT listed in the scope. **Update the row (hash, date, scope) whenever a sync
completes.**

| Repo | Commit | Date | Verified scope |
|---|---|---|---|
| agave | `e9a538e726` | 2026-07-14 | Token-2022 extensions (`account-decoder/src/parse_token_extension.rs`, `account-decoder-client-types/src/token.rs`), HTTP RPC method set (`rpc/src/rpc.rs`), pubsub methods (`rpc/src/rpc_pubsub.rs`), request/response shapes (`rpc-client-types/src/{config,response}.rs`), custom error codes (`rpc-client-api/src/custom_error.rs`), reserved accounts (`reserved-account-keys/src/lib.rs`), sysvar layouts (`account-decoder/src/parse_sysvar.rs`), `transaction-status-client-types/src/lib.rs` enums |
| solana-sdk | `4fb3a9a3` | 2026-07-14 | `transaction-error/`, `instruction-error/` (all variants), `sdk-ids/` (address constants) |
| solana-com | `7719729df` | 2026-07-14 | Documented HTTP/WebSocket method lists (`apps/docs/content/docs/en/rpc/`) confirmed to match the implemented client surface |
| solana-improvement-documents | `05f2ae9` | 2026-07-14 | Alpenglow SIMDs 0326/0357/0384/0387/0388 read for the Alpenglow section above |

Example diff commands, scoped to the watched paths:

```shell
git -C <agave-clone> diff e9a538e726..HEAD -- \
  account-decoder/src/parse_token_extension.rs \
  account-decoder-client-types/src/token.rs \
  account-decoder/src/parse_sysvar.rs \
  rpc/src/rpc.rs rpc/src/rpc_pubsub.rs \
  rpc-client-types/src/config.rs rpc-client-types/src/response.rs \
  rpc-client-api/src/custom_error.rs \
  reserved-account-keys/src/lib.rs \
  transaction-status-client-types/src/lib.rs

git -C <solana-sdk-clone> diff 4fb3a9a3..HEAD -- \
  transaction-error/ instruction-error/ sdk-ids/
```

## Sync task checklist

1. Locate (ask the user) or clone the reference repo(s) the task needs — see Reference
   repositories above — and `git pull` existing clones before comparing.
2. Diff the reference clone against its "Last verified sync points" hash, scoped to the
   watched paths; only changes in the diff (plus surfaces outside the verified scope) need
   review. Update the sync-point row when done.
3. Diff the relevant canonical file(s) above against the Java mirror. If the Java file
   carries an in-source upstream link, check it still resolves on the canonical repo's
   default branch — link `blob/master` paths, not pinned commits (commit pinning lives in
   the sync-point table above); a link into agave's old `sdk/` tree means the crate moved
   to the solana-sdk repo.
4. For token extensions: compare `parse_token_extension.rs`'s match against
   `ExtensionType.java`; add the record to the sealed `TokenExtension` hierarchy, the
   enum entry and dispatch case, and a round-trip test (plus a real fixture when
   available).
5. For RPC methods: compare `rpc.rs` registrations against `SolanaJsonRpcClient.java`
   literals; add interface method, request builder, response record + parser, and a
   `RoundTripRpcRequestTests` case.
6. For errors: check `agave:rpc-client-api/src/custom_error.rs` for codes past `-32021`
   and `solana-sdk:transaction-error/` for new variants.
7. Run `./gradlew :sava-core:test :sava-rpc:test` (integration tests via `integ.sh`).
