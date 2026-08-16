# Agave Sync Guide

sava re-implements Solana wire formats, account layouts, and the JSON-RPC API in Java.
The canonical definitions live in the Agave validator codebase and its interface crates.
This document maps each sava surface to its canonical source so sync-and-test tasks can go
straight to the right files on both sides.

The process contract — repo scope, quality gate, mutation ratchet — is in `AGENTS.md`.
Read that first; this file is reference material to consult when a task actually needs it.

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
- **agave-sdk** — `https://github.com/anza-xyz/agave-sdk.git` — the wire-format parsers and
  sanitizers split out of agave: `transaction-view` (the zero-copy legacy/v0/v1 transaction
  parser and its `sanitize`), `short-vec`. **`transaction-view` is here, not inside `agave`** —
  citing `agave:transaction-view/` sends a reader to a path that does not exist.
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
  meaning none; `solana_zero_copy::unaligned::Bool` = 1 byte, with every nonzero value
  meaning true; ElGamal pubkey = 32; ElGamal ciphertext = 64;
  AE (decryptable) ciphertext = 36; integers little-endian; `f64` for scaled-UI multipliers.
- `TokenMetadata` fields are borsh (u32 length-prefixed UTF-8 strings) per
  `spl-token-metadata-interface`. Its pinned `VariableLenPack::unpack_from_slice`
  uses unchecked Borsh decoding: strings remain strict UTF-8, but a complete value may
  have trailing bytes inside its declared TLV slice. Sava matches both properties and
  returns the typed fields; writing the parsed value emits canonical Borsh without the
  ignored suffix. This is read-side parity: the reviewed Token-2022 v3.1.1 initialization
  and reallocation paths size program-produced values to their exact canonical packed length.
  `TokenGroup`/`TokenGroupMember` come from `spl-token-group-interface`.

Optional-pubkey semantics: Rust maps all-zero to `None`; the Java records keep the raw
32-byte key. This is intentional — callers defensively check `null` and `PublicKey.NONE`.

Tests: `sava-core/src/test/java/software/sava/core/accounts/token/extensions/`
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
- `SolanaUpstreamLayoutConformanceTests.java`, replayed in the owning Token-2022 suite by
  `SolanaUpstreamToken2022ConformanceTests.java` — committed output from the locked Rust
  generator under `src/test/solana/upstream-layout-vectors`: all 29 ordinals and exact
  fixed sizes, short/long acceptance, the TokenMetadata TLV `u16` boundary, paired
  forward/reverse ordered metadata, accepted trailing metadata bytes with canonical
  repacking, strict UTF-8 rejection in every metadata string position, and all nine
  `solana_zero_copy::Bool` fields with byte
  `2` (every nonzero value is true). The same
  generator packs and re-unpacks a complete Mint and Account, following Agave's
  account-decoder test construction, with thirteen extension instances across the two
  full wire images and non-default values in every valued extension. Gradle replays the
  TSVs offline; Rust is only needed to regenerate or verify them.

The 2026-08-13 direct pass also made the TLV declaration authoritative: a value may not
borrow bytes from the next extension, fixed-size values must match their Rust size,
and variable-length TokenMetadata follows Rust by accepting a complete Borsh value with
trailing bytes inside that declaration, rejecting malformed UTF-8, and canonicalizing it
when written. The trailing-byte rule mirrors the Rust reader; it is not a claim that the
reviewed program construction paths produce non-canonical suffixes.
Repeated nonzero extension types are rejected before known/unknown dispatch, and writers
reject type/value lengths outside `u16` before touching the destination buffer. Parsed
TokenMetadata retains the ordered, unique key/value pairs carried on the wire.

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
- `getProgramAccountsBase64Zstd.json` is a live-mainnet capture replayed end to end through
  the public `ProgramAccountsRequest.Builder.encoding(base64_zstd)` selector, including
  request JSON, envelope fields, bounded zstd decompression, and an independently verified
  byte digest. Applications supply their preferred streaming implementation through
  `zstdDecompressor`; sava has no runtime zstd dependency, and rejects `base64+zstd` when
  no implementation was supplied. Tests use Aircompressor only as a fixture decoder.
  Other account convenience methods deliberately continue to request base64; measured
  StakeHistory data saved only about 9% beyond HTTP gzip, which did not justify widening
  the public client interface.
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
  identity. The 8 baseline keys are all triaged equivalent (int-clamp boundaries,
  unsigned reinterpret at zero, logging/capacity — reasons in
  `sava-rpc/config/pitest/README.md`). Fixed 2026-07-17 after the first baseline:
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

Enum mirrors in this package: `RewardType` (including `DeactivatedStake`) and the
confirmation status strings map to
`agave:transaction-status-client-types/src/lib.rs` (`RewardType`, `TransactionConfirmationStatus`,
`UiTransactionEncoding`). Like `InflationReward`, `TxReward` stores the effective commission
value and flags when that value came from the canonical `commissionBps` field. Full `getBlock`
requests and all `getTransaction` requests send
`maxSupportedTransactionVersion: SolanaJsonRpcClient.MAX_SUPPORTED_TRANSACTION_VERSION` (currently
`1`), matching this client's legacy/v0/v1 transaction model. The parameter is omitted for
`transactionDetails` of `none` and `signatures`, because agave's
`ConfirmedBlock::encode_with_options` never version-checks those arms. Note that the
`== BlockTxDetails.full` condition is only sufficient while sava's `BlockTxDetails` has no
`accounts` constant; agave's `TransactionDetails::Accounts` arm does version-check via
`build_json_accounts`.

## Other sync surfaces (sava-core)

| Java (under `sava-core/.../software/sava/core/`) | Models | Canonical source |
|---|---|---|
| `accounts/SolanaAccounts.java` | native/builtin program IDs, SPL program IDs, all sysvar addresses | `agave:reserved-account-keys/src/lib.rs` (`RESERVED_ACCOUNTS`); IDs originate in `solana-sdk:sdk-ids/` |
| `accounts/lookup/AddressLookupTable.java` (+ overlay/root variants) | ALT account layout: 56-byte meta, 256 max addresses, `deactivationSlot == u64::MAX` = active | `solana-sdk:address-lookup-table-interface/`; `agave:account-decoder/src/parse_address_lookup_table.rs` |
| `accounts/sysvar/Clock.java` | Clock sysvar (40 bytes) | `solana-sdk:clock/`; `agave:account-decoder/src/parse_sysvar.rs` `UiClock` |
| `accounts/sysvar/EpochSchedule.java` | EpochSchedule sysvar; strict wincode bool | `solana-sdk:epoch-schedule/`; `wincode` |
| `accounts/sysvar/EpochRewards.java` | EpochRewards sysvar (has in-source sync link) | `solana-sdk:epoch-rewards/`; `agave:account-decoder/src/parse_sysvar.rs` `UiEpochRewards` |
| `accounts/sysvar/Rent.java` | Rent sysvar and checked `minimum_balance` integer/f64 paths | `solana-sdk:rent/` |
| `accounts/token/Mint.java` | SPL Mint, 82-byte packed layout with u32-tag COptions | `spl-token-interface` `state::Mint`; `agave:account-decoder/src/parse_token.rs` |
| `accounts/token/TokenAccount.java`, `AccountState.java` | SPL Account, 165 bytes, explicit memcmp offsets used for `getProgramAccounts` filters | `spl-token-interface` `state::Account`/`AccountState` |
| `tx/Transaction*.java`, `tx/TransactionSkeleton*.java` | legacy + v0 message wire format: 3-byte header, `0x80` version bit, compact-u16 arrays, address-table lookups | `solana-sdk:message/`, `solana-sdk:transaction/`; nearest upstream parser: `agave-sdk:transaction-view/` |
| `tx/V1Transaction.java`, `tx/V1TransactionSkeleton.java`, `tx/TxBuilder*.java` | SIMD-0385 v1 message wire format: `129` version byte, `TransactionConfigMask` + `ConfigValues`, fixed-width instruction headers, trailing signatures, no address-table lookups | `solana-improvement-documents:proposals/0385-transaction-v1.md`; `agave:runtime-transaction/src/runtime_transaction/transaction_view.rs` (`TransactionVersion::V1`) and `agave-sdk:transaction-view/` |
| `encoding/CompactU16Encoding.java` | short_vec / ShortU16 encoding | `solana-sdk:short-vec/` |
| `rpc/Filter.java`, `MemCmpFilter.java`, `DataSizeFilter.java` | `getProgramAccounts` filters; 128-byte memcmp cap | `agave:rpc-client-api/src/filter.rs` + server enforcement in `agave:rpc/` |
| `zk/ElGamal.java` | ElGamal/Pedersen/AE byte-length constants used by confidential extensions | `solana-zk-sdk` `encryption::*` (agave repo `zk-sdk/` or crates.io) |
| `accounts/PublicKey.java`, `accounts/PublicKeyBytes.java`, `accounts/AccountWithSeed.java` | PDA derivation (32-byte seeds; 16 total seeds including the bump, so canonical find accepts 15 caller seeds; bump search 255..1; `"ProgramDerivedAddress"` marker; off-curve check); system create-with-seed UTF-8 bytes, rejection of unpaired Java UTF-16 surrogates to preserve Rust `&str` semantics, and illegal-owner marker guard; the ASCII off-curve helper reserves one of the 32 seed bytes for its nonce and applies the same owner guard | `solana-sdk:address/src/lib.rs` (`create_program_address`, `create_with_seed`); `address/src/syscalls.rs` |
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

- `./gradlew :sava-core:pitestTx` — PIT over the full `tx` and `accounts/lookup`
  packages (widened 2026-07-18 from the skeleton/lookup allowlist; the lookup tables
  were folded in 2026-07-16 — versioned skeleton parsing consumes tables, and they
  parse the same untrusted account data). The builder/helper debt the widening
  exposed was killed the same day (182 → 40 baseline keys) by
  `AccountIndexLookupTableTests`, `TransactionByteHelpersTests`,
  `TransactionFactoryTests`, and `TransactionRecordPlumbingTests`; the baseline in
  `sava-core/config/pitest/tx-accepted.csv` now carries 27 triaged equivalents
  (reasons in `config/pitest/README.md`) plus the 13 long-standing skeleton
  survivors — offset arithmetic a length assertion cannot distinguish. New unkilled
  mutants fail the build via `pitestTxVerify`; triage per `config/pitest/README.md`.
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
signing), and `LegacyMessageConformanceTests` (locked current Solana Rust legacy-message
and signature vectors: role promotion, header counts, raw-u8 account indexes through 255,
ShortU16 boundaries, and signer-slot matching). Its generator lives under
`src/test/solana/legacy-message-vectors`; normal Gradle tests consume only the committed
TSV. The fixture records the Kit v7.0.0 source functions reviewed at its release commit,
but its executable byte/signature oracle is Rust. Prefer extending these over adding
unprovenanced fixtures — the cross-method invariant is
what catches offset bugs, and it has found two real ones: `serializedInstructionsLength`
skipping the program-index byte, and `CompactU16Encoding.decode` sign-extending a
three-byte length into a negative value that no bounds check ever caught (both fixed
2026-07-16).

The direct conformance pass on 2026-08-13 fixed three non-lookup-table defects: a lone
`sign(Signer)` no longer signs the only slot without checking its public key;
`sign(Collection)` now validates a complete, duplicate-free by-key assignment before
mutating any signature; and every skeleton instruction accessor reads the program account
index as the protocol's raw `u8` rather than CompactU16 (the old parser lost alignment for
valid indexes 128..255). The follow-up review also bounds positional `sign(index, signer)`
to the required-signature region, restores a cached JDK signer after any failed signing
attempt, and makes every skeleton instruction view reject a program index outside the
statically included keys instead of reading a fabricated key from later message bytes.
Skeleton deserialization remains permissive for offline analysis, but conversion to a mutable
`Transaction` now requires the serialized signature-slot count to match the message header's
required-signature count and to use the one-byte prefix assumed by mutable signing; all three
concrete creation paths enforce those boundaries before signing can overwrite message bytes.
The locked Rust generator also records that current Solana
`VersionedTransaction` decoding accepts 127 legacy/v0 signatures but rejects 128 because
the high first byte is reserved for version discrimination. Sava deliberately retains its
published permissive builder behavior at that boundary and for overflowing account/header
counts: it emits narrowed bytes so callers can construct and analyze invalid transactions,
leaving submission validation to the RPC. No address-lookup-table selection rule or
within-category account tie-break changed.

### Deliberate divergences: v1 compute budget values

SIMD-0385 makes every unset `TransactionConfigMask` bit mean the minimum value, and agave reads
them that way (`runtime-transaction/src/runtime_transaction/transaction_view.rs`: `unwrap_or(0)` for
the priority fee, compute-unit limit and accounts-data-size limit; `unwrap_or(HEAP_LENGTH)` for the
heap). A 0 compute-unit limit is valid at every validation layer but fatal at the first metered
instruction — `program-runtime/src/invoke_context.rs` seeds the compute meter with the limit itself,
with no floor and no upfront allowance, and `consume_checked` fails whenever the meter is below the
charge. Only an empty or precompile-only transaction can succeed with one. These values are
therefore correct on the wire and useless as construction defaults, and sava splits the difference
deliberately. **These are intentional; do not "fix" them toward the canonical implementations.**

| surface | sava | agave / SIMD-0385 | why |
|---|---|---|---|
| `TransactionSkeleton` readers for an absent bit | `0` for fee, CU limit and data size | same | faithful to the wire |
| `TransactionSkeleton#heapSize()` for an absent bit | `0` | `32KiB` (`MIN_HEAP_FRAME_BYTES`) | heap is the only value whose absent and effective readings differ, so the reader must pick one: it reports what was *requested*. Reporting the effective 32KiB would make `prototypeTransaction` write a heap ConfigValue the source never had, and would split v1 from sava's legacy reader, which already returns `0` when no `RequestHeapFrame` instruction is present. An explicit 32KiB and no request behave identically at runtime |
| `TxBuilder` defaults | CU limit `1_400_000`, data size 64MiB, both always serialized; fee and heap unset | unset stays unset | reserves both slots so they can be updated in place after simulating, and keeps the built transaction executable; `0` is the explicit clear |
| `TransactionSkeleton#prototypeTransaction` on a v1 source | carries `0` through verbatim | n/a | preservation, not construction — a rebuilt transaction must equal its source |
| `Transaction#setPriorityFeeLamportsFromComputeUnitPrice` on v1 with an absent CU limit | prices against `1_400_000` | no counterpart; v1 fees are absolute lamports, never price × limit | pricing, not preservation — a fee of `0` for a transaction that cannot execute is useless, and `1_400_000` is what `TxBuilder` would have written |
| an explicit legacy/v0 `SetComputeUnitLimit(0)`, when deriving a priority fee | treated as absent: falls back to the per-instruction default | taken verbatim as a 0-unit budget | same reason. A 0-unit budget cannot execute a single metered instruction, so deriving a fee against it is pointless; the useful reading of an explicit zero is "no limit stated". Note this affects fee *derivation* only — `computeUnitLimit()` still reports the 0 that is on the wire |

The v1 skeleton enforces the equivalent boundary for the SIMD-0385 layout. Because a v1 message
appends its signatures after the instruction payloads, the split is implied only by the serialized
length, so `V1TransactionSkeleton` requires
`instructionsOffset + serializedInstructionsLength() == data.length - numSignatures * 64` before a
parsed message may become a mutable transaction; a truncated or padded payload stays readable but
cannot be signed. The static `Transaction.sign(SequencedCollection, byte[])` v1 path likewise takes
the required signature count from the header's `num_required_signatures` rather than from the
caller's collection, and rejects a mismatch before mutating. v1 program-id indexes are bounds
checked for every instruction, including ones a discriminator filter skips.

## Token-2022 hardening (sava-core `accounts/token/`)

The TLV walker and 29 extension readers parse untrusted account data fetched over RPC;
same malformed-input contract as transactions: **garbage in → `RuntimeException` out**.
Fuzzing this surface (2026-07-16) found and fixed three parsing defects, each pinned by a
regression in `ParseExtensionsTests`:

- the u16 TLV type/length were read *signed* — a negative length walked the cursor
  backwards into an infinite loop, and a type ≥ `0x8000` crashed instead of reaching the
  `UnknownTokenExtension` escape hatch; the `& 0xFFFF` masks in `parseExtensions` are
  load-bearing.
- `TokenMetadata.read` trusted the wire's additional-metadata count before proving that
  even the pairs' two length prefixes fit. Counts exceeding that byte-derived upper bound,
  including top-bit-set `u32` values that are negative as Java `int`s, now throw
  `IllegalArgumentException` before the entry loop.
- an unknown extension's length overshooting the data end throws
  `IndexOutOfBoundsException` like known extensions do, instead of zero-padding a
  fabricated tail.

Verification tasks:

- `./gradlew :sava-core:pitestToken2022` — PIT over `software.sava.core.accounts.token.*`
  against the `software.sava.core.accounts.token.*` tests. History-free measurement
  2026-08-13: 621 mutations, 601 killed, 0 without coverage; the 20 survivors are
  classified equivalent (mostly
  `31 * h + x` hashCode operator swaps only exact-hash assertions could kill; reasons
  grouped in `sava-core/config/pitest/README.md`).
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
- Java's default UTF-8 conversion replaced malformed input bytes and unpaired UTF-16
  surrogates. Borsh/Rust strings admit neither: all string readers now decode strictly,
  and `getBytes`, `len`, and writers reject unpaired surrogates instead of serializing
  `?`. The nested string-vector reader also advances by bytes consumed from the wire,
  rather than re-encoding parsed strings to reconstruct its cursor.

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
  infinite loop — counts as detected), 0 without coverage; the survivors (20 baseline
  keys as of 2026-07-18) are individually verified equivalent, reasons grouped in
  `sava-core/config/pitest/README.md`. Any new survivor must be either killed with a
  test or classified equivalent with a reason.
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
TweetNaCl/curve25519-dalek's, and therefore Solana's (`solana-pubkey`
`bytes_are_curve_point`): mask the sign bit, reduce y mod p, decompress. Small-order
points are ON curve; each of the 19 non-canonical y magnitudes p..p+18 takes the verdict
of its reduced form (12 on, 7 off). This deliberately diverges from BouncyCastle's
`validatePublicKeyPartial`, which pre-rejects y ∈ {0, 1, p−1, the two order-8 y values}
and anything non-canonical; any BC-based differential must exclude that set. Do not
"fix" the divergence — it would change which PDAs derive.

Tests (`Ed25519UtilTests`) follow the differential-oracle convention with two independent
references: BouncyCastle `org.bouncycastle.math.ec.rfc8032.Ed25519` (decompression
verdicts on canonical points; key generation against random and structured seeds plus the
RFC 8032 §7.1 vectors) and an in-test `BigInteger` Euler-criterion oracle covering the
full input domain, including the torsion points and non-canonical encodings BC refuses to
judge.

`SolanaEd25519CurveConformanceTests` adds a direct, pinned upstream oracle. Its committed
1,364-row fixture is generated by the locked Rust program under
`sava-core/src/test/solana/ed25519-vectors` from `solana-pubkey` 4.2.0's host SDK
predicate and `solana-curve25519` 4.0.1's Edwards validator, the backend called by Agave
v4.2.0's curve-point-validation syscall. The generator requires those two upstream
verdicts to agree for every row. Both wrappers currently reach the same pinned dalek
decompressor, so their equality checks Solana wrapper parity, while the existing
`BigInteger` oracle remains the independent mathematical differential. This checks the
shared 32-byte membership semantics; it does not claim SBF ABI, memory-translation,
compute-metering, or feature-activation coverage.

- `./gradlew :sava-core:fuzzEd25519` — Solana PDA membership against BouncyCastle on
  its valid domain and the full-domain BigInteger decompression reference, plus key
  generation against BouncyCastle.
- `./gradlew :sava-core:fuzzEd25519Jdk` — RFC 8032 seed-to-public-key derivation against
  SunEC after exact-seed readback. It is deliberately not a PDA membership oracle.
- `./gradlew :sava-core:pitestEd25519` — PIT over `Ed25519Util`, `Scalar25519`, `Codec`.
  Baseline 2026-07-16: 940 mutations, 99% detected, 0 without coverage; the survivors
  (12 baseline keys as of 2026-07-18) are equivalent-in-context — static-initializer
  precompute construction, defensive carry passes, and verdict-invisible arithmetic no
  input reachable through the public API can distinguish; reasons grouped in
  `sava-core/config/pitest/README.md`.

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
