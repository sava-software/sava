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
  appended, so new Java entries must be appended in the same order.
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
`borsh/BorshTests.java`, `encoding/CompactU16EncodingTest.java`, `encoding/Base58Tests.java`,
`encoding/ByteUtilTests.java`, `encoding/JexTests.java`, plus the token extension tests above.

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
  their tests; report in `sava-core/build/reports/pitest/encoding`. Baseline (2026-07-15,
  re-verified 2026-07-16 on Java 25 bytecode with the same result): 1063 mutations, 98%
  detected (a timed-out mutant — an induced infinite loop — counts as detected), 0 without
  coverage; the 25 survivors were individually verified equivalent (limb over-allocation,
  dead defensive strip loops, chunk sizing) and are identical at both bytecode levels.
  Any new survivor must be either killed with a test or classified equivalent with a
  reason.
- `./gradlew :sava-core:fuzzBase58 -PmaxFuzzTime=<seconds>` — Jazzer coverage-guided
  fuzzing of `Base58Fuzz`, a differential harness: every decode variant (String, char[],
  ASCII byte[], the decode-into forms against dirty buffers) and every encode variant
  (slice, mutableEncode, the begin/continue split at a fuzzer-chosen point) must agree
  with the String reference path, plus canonicality and rejection invariants. Input length
  is capped (`maxLen = 256` — the codec is O(n²) and all interesting boundaries are
  small); the corpus persists in `sava-core/build/fuzz/base58-corpus`, so runs accumulate.

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

- Token-2022 extension parsing is Set based: `Token2022`/`Token2022Account` hold
  `Set<TokenExtension> tokenExtensions` including `UnknownTokenExtension(type, data)`
  entries for extensions newer than the library, so parsing survives new SPL releases,
  callers keep the raw data, and unknown extensions round-trip through `write`. Users
  iterate the Set and switch on the sealed type. The deprecated `extensions()` method
  builds a `Map<ExtensionType, TokenExtension>` dynamically, dropping unknown entries
  since they cannot be keyed by the enum. Append new `ExtensionType` entries promptly so
  the dispatch switch parses them typed; a future release drops the map method and enum.
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
- `CompactU16Encoding.decode`/`getByteLen(byte[], int)` are lenient where agave's
  deserializer (`solana-sdk:short-vec/` `visit_byte`) is strict: agave rejects alias
  encodings (zero continuation bytes), a continuation bit on byte three, and decoded
  values overflowing a u16, while sava decodes whatever the bytes say (an unmasked third
  byte can even yield a negative length). Relevant when parsing untrusted wire data.
  The encoder-side max was corrected from `0x3ffff` to `0xffff` on 2026-07-15 — the old
  bound silently truncated 65_536..262_143 to their low 16 bits of digits (65_536
  encoded to the same bytes as 0).
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
3. Diff the relevant canonical file(s) above against the Java mirror.
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
