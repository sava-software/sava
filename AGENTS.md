# Agave Sync Guide

sava re-implements Solana wire formats, account layouts, and the JSON-RPC API in Java.
The canonical definitions live in the Agave validator codebase and its interface crates.
This document maps each sava surface to its canonical source so sync-and-test tasks can go
straight to the right files on both sides.

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
  method pages at `content/docs/en/rpc/http/*.mdx` and
  `content/docs/en/rpc/websocket/*.mdx` (canonical request/response examples in
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
- `extensions/ExtensionType.java` — enum of all 29 extension types, ordinals 0
  (`Uninitialized`) through 28 (`PermissionedBurn`). **Must stay ordinal-aligned with the
  Rust `ExtensionType` enum**; new SPL extensions are appended, so new Java entries must be
  appended in the same order.
- `extensions/*.java` — one record per extension with a static `read(data, offset)` and a
  `write(data, offset)`/`l()` pair. Variable-length extensions
  (`ConfidentialTransferFeeConfig`, `ConfidentialTransferFeeAmount`) take an end bound in
  `read`.
- `Token2022.java` — mint parsing: base `Mint` (82 bytes) + 83 bytes padding + 1 accountType
  byte, then TLV entries (`u16 LE type`, `u16 LE length`, payload). The extension dispatch
  switch must be exhaustive; a zeroed type terminates parsing (trailing re-allocated but
  uninitialized space) while retaining extensions already parsed.
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
  account). Prefer adding real account fixtures when a new extension ships on mainnet.

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
| `borsh/Borsh.java`, `borsh/RustEnum.java` | borsh spec: u32-prefixed strings/vecs, 1-byte Option tags, enum discriminants | `borsh` crate spec as used by agave/SPL |
| `programs/Discriminator.java` | 8-byte Anchor sighash, 4-byte native enum tags | Anchor framework convention (not agave) |

Existing sava-core tests: `tx/TransactionSerializationTests.java`,
`accounts/lookup/AddressLookupTableTests.java`, `accounts/PublicKeyTest.java`,
`borsh/BorshTests.java`, `ecnoding/CompactU16EncodingTest.java`, `ecnoding/Base58Tests.java`,
plus the token extension tests above.

## Known gaps / candidate work

- HTTP RPC: `getAgGenesisCert` not implemented.
- WebSocket: no typed wrappers for `slotsUpdatesSubscribe`, `blockSubscribe`,
  `voteSubscribe`.
- `SolanaAccounts` omits some reserved keys agave lists: `bpf_loader_deprecated`,
  `loader_v4`, `secp256r1_program`, `native_loader`, `sysvar::rewards`.
- Sysvar decoders exist only for Clock and EpochRewards; Rent, EpochSchedule, StakeHistory,
  SlotHashes, SlotHistory, LastRestartSlot are address constants only (see
  `parse_sysvar.rs` for layouts if adding).
- No unit tests for: Clock/EpochRewards layouts, base Mint/TokenAccount parsing,
  `SolanaAccounts` ID values, `Filter` JSON output, Multisig accounts (not modeled).
- Compute-budget instruction builders live outside sava-core; constants reference
  `agave:compute-budget/src/compute_budget_limits.rs` and
  `solana-sdk:compute-budget-interface/` (watch SIMD-0268 default changes).
- Watch for larger-transaction SIMDs: `Transaction.MAX_SERIALIZED_LENGTH=1232` and
  `MAX_ACCOUNTS=64` are already deprecated as not valid for all future versions.

## Sync task checklist

1. Locate (ask the user) or clone the reference repo(s) the task needs — see Reference
   repositories above — and `git pull` existing clones before comparing.
2. Diff the relevant canonical file(s) above against the Java mirror.
3. For token extensions: compare `parse_token_extension.rs`'s match against
   `ExtensionType.java`; add the record, dispatch case, `TokenExtensions` accessor, and a
   round-trip test (plus a real fixture when available).
4. For RPC methods: compare `rpc.rs` registrations against `SolanaJsonRpcClient.java`
   literals; add interface method, request builder, response record + parser, and a
   `RoundTripRpcRequestTests` case.
5. For errors: check `agave:rpc-client-api/src/custom_error.rs` for codes past `-32021`
   and `solana-sdk:transaction-error/` for new variants.
6. Run `./gradlew :sava-core:test :sava-rpc:test` (integration tests via `integ.sh`).
