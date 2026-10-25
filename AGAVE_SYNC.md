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
  parser and its `sanitize`). **`transaction-view` is here, not inside `agave`** —
  citing `agave:transaction-view/` sends a reader to a path that does not exist. `short-vec`
  is *not* here; it stayed in solana-sdk (`solana-sdk:short-vec/`).
- **solana-improvement-documents** — `https://github.com/solana-foundation/solana-improvement-documents`
  — the SIMD texts; the Alpenglow section below is read against these. It has a row in "Last
  verified sync points" and belongs in this list for that row to point back at.
- **solana-com** — `https://github.com/solana-foundation/solana-com` — solana.com docs; RPC
  method pages at `apps/docs/content/docs/en/rpc/http/*.mdx` and
  `apps/docs/content/docs/en/rpc/websocket/*.mdx` (canonical request/response examples in
  `jsonc !response` blocks).
- **transaction-v1-examples** — `https://github.com/solana-foundation/transaction-v1-examples`
  — the Solana Foundation's SIMD-0385 examples: `@solana/kit`, Rust, Python and Go clients
  driving a local 4.2.1 validator. `ts/kit/src/estimate.ts` is the reference for kit's v1
  resource-limit flow.

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
- `extensions/TokenExtension.java` — sealed hierarchy covering every known on-chain
  extension type, with `UnknownTokenExtension` for the rest. Each
  concrete type's `ordinal()` returns its fixed wire ID, which **must match the Rust
  `ExtensionType` ordinal**. The model is the `Set<TokenExtension>` on
  `Token2022`/`Token2022Account`: users iterate it and switch on the sealed type;
  `UnknownTokenExtension(type, data)` entries keep parsing alive across new SPL releases
  and round-trip through `write`. `Token2022.parseExtensions` dispatches on the wire ID;
  its private names table preserves the diagnostic names used before the enum and
  `extensions()` compatibility maps were removed.
- `extensions/*.java` — extension records have a static reader and a
  `write(data, offset)`/`l()` pair. `ConfidentialTransferFeeConfig` and
  `ConfidentialTransferFeeAmount` have fixed wire lengths of 129 and 64 bytes. Their
  readers take an end bound to delimit the ciphertext; `Token2022.parseExtensions`
  validates those fixed lengths before calling them. `TokenMetadata` is variable-length
  and is read from a slice bounded by its declared TLV length.
- `Token2022.java` — mint parsing: base `Mint` (82 bytes) + 83 bytes padding + 1 accountType
  byte, then TLV entries (`u16 LE type`, `u16 LE length`, payload — both read unsigned).
  The extension dispatch switch must cover every known wire ID; a zeroed type terminates parsing
  (trailing re-allocated but uninitialized space) while retaining extensions already
  parsed; an unrecognized type yields `UnknownTokenExtension`; an unknown extension whose
  claimed length overshoots the data end throws `IndexOutOfBoundsException` like known
  extensions do. See Token-2022 hardening below.
- `Token2022Account.java` — token account parsing: base `TokenAccount` (165 bytes) + 1
  accountType byte, then TLV entries.

Agave/SPL canonical sources:
- `agave:account-decoder/src/parse_token_extension.rs` — the `parse_extension` match lists
  every extension agave supports; the `convert_*` functions and the `Ui*` structs in
  `agave:account-decoder-client-types/src/token.rs` give field names, order, and widths.
  **To check for new extensions: compare the match in `parse_extension` with the sealed
  hierarchy and `Token2022.parseExtensions` dispatch cases.**
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
32-byte key. This is intentional: a parsed optional key is never `null`, and absence equals
`PublicKey.NONE`.

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

Coverage note (2026-10): the Java client implements the full active agave method set except
the two Alpenglow-only methods, `getAgGenesisCert` (agave 4.2.0) and `getRankMap` (agave 4.5, in
no release the public clusters ran as of 2026-10-24), both left out under the owner's policy in
the Alpenglow section, and `getBlockCommitment`, removed in 25.13.0 because Alpenglow nodes
return no data for it (see Alpenglow); agave's deprecated methods are intentionally not
implemented.

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
  identity. Each baseline key is argued by family or as debt (int-clamp boundaries,
  unsigned reinterpret at zero, logging/capacity — reasons in
  `sava-rpc/config/pitest/README.md`). Fixed 2026-07-17 after the first baseline:
  `TxStatus.parse`'s nil-status dedup compared `whatIsNext()` against Java null instead of
  `ValueType.NULL` and so never fired, and `TxSimulation.unitsConsumed` read an unguarded
  `readInt` where `fee` skips non-numbers. Known parser quirk, pinned not changed:
  `JsonUtil.parseEncodedData`'s single-element-array branch always throws (real providers
  send `[data, encoding]` pairs). A JSON fuzz harness would add little at this
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
serves `slotsUpdatesSubscribe` and `blockSubscribe`, which have no typed Java wrapper
(reachable via the generic passthrough), and still registers `voteSubscribe`: every agave
release through v4.5.0-alpha.2 (so the 4.3.x/4.4.x servers on the public clusters) refuses it
with `-32601 Method not found` unless started with `--rpc-pubsub-enable-vote-subscription`, and
master `1513b7a5bb` (unreleased) drops that gate; wherever it is accepted under Alpenglow it
never notifies (see Alpenglow and Known gaps).

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

### Observed on Agave 4.2.1

Raw JSON-RPC bodies captured 2026-08-24 from a `solana-test-validator` 4.2.1 (feature-set
`565236538`, the same set mainnet 4.2.0 reported) with `enable_tx_v1` active and then inactive.
These are historical observations; the raw captures remain in the committed Agave fixtures and
offline RPC tests. The broader original live checks have been reduced to one optional local smoke check
(`sava-rpc/src/test/solana/v1-live/README.md`): a large transaction is simulated, tightened in
place, sent and read back. It does not recheck every observation below. The gate-off rows were
captured once by hand; the local test-validator activates every gate at genesis.

1. **Version ceiling.** `getTransaction` and full `getBlock` with `maxSupportedTransactionVersion`
   omitted or `0` fail the *whole* response with `-32015` when any transaction is v1 — one v1
   transaction blinds a ceiling-0 caller for the entire slot. Message text: `Transaction version
   (1) is not supported by the requesting client. Please try the request again with the following
   configuration parameter: "maxSupportedTransactionVersion": 1`. The `signatures` and `none`
   detail levels are served regardless. A ceiling above every version present is accepted:
   mainnet 4.2.0 served full `getBlock` at ceiling 1 and 2 on legacy/v0-only blocks, so
   `MAX_SUPPORTED_TRANSACTION_VERSION = 1` is safe to send before activation. The version the
   message names is that of the *first* transaction the request could not serve, not the highest
   one present — so on a shared cluster (see "Observed on public devnet" below) a full `getBlock`
   with the ceiling omitted names `0` whenever a neighbouring v0 transaction precedes a v1 in the
   block, and only a ledger carrying nothing but sava's own v1 transactions always names `1`.
2. **Pre-activation write path** (`enable_tx_v1` inactive — mainnet, devnet and testnet as of
   2026-08-24; the feature account `txv1aq4pp281K9um3tnPgkfX8UqtFT6wcVW3hNezGLL` did not exist
   on any of them. Superseded for devnet and testnet on 2026-09-03, see below; mainnet remained
   inactive in that observation). `simulateTransaction` returns `err: "UnsupportedVersion"` with
   `unitsConsumed: 0` and no logs. `sendTransaction` with preflight returns `-32002`
   `Transaction simulation failed: Transaction version is unsupported` (`data.err:
   "UnsupportedVersion"`). `sendTransaction` with `skipPreflight` returns the signature, but the
   transaction is silently discarded by sigverify — `agave:perf/src/sigverify.rs` `verify_packet`
   returns false for `TransactionVersion::V1` while the gate is off, and
   `agave:runtime/src/bank.rs` `verify_transaction` plus
   `agave:runtime/src/bank/check_transactions.rs` `filter_v1_transactions` return
   `TransactionError::UnsupportedVersion` — so it never lands and `getSignatureStatuses` stays
   `null`. The RPC's base64 size ceiling already admits 4096-byte v1 payloads before activation
   (`agave:rpc/src/rpc.rs` `decode_and_deserialize` keys the limit on the `0x81` base64 prefix),
   so a >1232-byte v1 transaction fails with `UnsupportedVersion`, not a size error.
3. **Absent compute-unit limit.** A v1 transaction whose compute-unit-limit bit is absent fails
   preflight with `InstructionError(0, ComputationalBudgetExceeded)` and `unitsConsumed: 0` — the
   empirical basis for the `TxBuilder` defaults row in "Deliberate divergences: v1 compute budget
   values" below.
4. **kit parity.** `@solana/kit`'s recommended v1 flow
   (`transaction-v1-examples:ts/kit/src/estimate.ts`: `fillTransactionMessageProvisoryResourceLimits`,
   then `estimateAndSetResourceLimits`) also fills both limits with provisory maxima before
   simulating, so that the message simulates at its final size and cannot fail for want of the
   resources being measured. `TxBuilder`'s default-to-max shape is therefore kit-conformant, not a
   divergence; only the explicit `0`-clears-the-bit escape hatch is sava's own.
5. **Costs seen while tightening.** A plain system transfer is 150 CU and loads 149 bytes of
   account data (213 once the recipient exists). A Memo v3 instruction carrying 3000 bytes costs
   ~1,052,946 CU and loads ~75 KB (75,013 bytes) of program data — most of the 1.4M ceiling for
   one instruction, which matters when tightening limits from a simulation.

### Observed on public devnet, Agave 4.3.0-beta.3

`enable_tx_v1` activated on the public clusters: testnet at slot 437,276,256 (2026-09-02
10:39:27 UTC) and devnet at slot 492,480,000 (2026-09-03 11:38:04 UTC). At the 2026-09-03
observation, both nodes reported 4.3.0-beta.3, feature-set `2409014235`. Mainnet returned
`value: null` for the feature account on 4.2.2, feature-set `565236538`.

On 2026-09-03 the original seven-case `LiveV1ValidatorCheck` was run against
`api.devnet.solana.com` and passed. That run observed the gate-on behavior on the public cluster:
byte-exact `getTransaction` round trip, fee `5000` base plus the
priority lamports verbatim (`fees=[5000, 10000]` for priority 0 and 5000), a 3236-byte v1
transaction accepted and landed byte-exact, `simulateTransaction` → in-place tighten → execution
agreeing on units, the cleared compute-unit limit refused with `-32002`, and two signatures
charged `2 × 5000 + 5000`. Two differences from a private ledger, neither a v1 behaviour:

- **The named ceiling is context-dependent**, per the refinement in row 1 above. Measured on
  devnet block 492,653,301: ceiling omitted → `-32015` naming `0`; ceiling `0` → `-32015` naming
  `1`; ceiling `1` → the block is served. The original live `versionCeiling` case therefore
  accepted either `0` or `1` for the omitted-ceiling call alone.
- **`loadedAccountsDataSize` is a per-ledger constant** — 142 bytes on devnet for the transfer
  that loads 149 on a fresh local ledger. Not a busy-cluster effect, and nothing to do with the
  recipient, which is a fresh key that exists on neither cluster and so loads nothing
  (`agave:svm/src/account_loader.rs`, a missing account contributes no size). The whole 7-byte
  delta is the system program's own account: `agave:runtime/src/bank.rs` `add_builtin_account`
  returns early when a native-loader account already exists, so each ledger keeps whichever
  builtin name its genesis wrote. Devnet's predates the rename and still holds the 14-byte
  `system_program`, while a fresh `solana-test-validator` genesis — and testnet and mainnet —
  hold today's 21-byte `solana_system_program`. Since v1 carries the budget values in the config
  mask rather than as a ComputeBudget instruction, the only accounts loaded are the fee payer and
  the system program: `64 + 0 + (64 + 14) = 142` against `64 + 0 + (64 + 21) = 149`. The
  simulate-then-tighten flow is what makes this a non-issue: the landed limit is whatever that
  cluster measured.

During that run, `api.devnet.solana.com` enforced a per-method rate limit (`Too many requests for
a specific RPC call`) that the whole-class run tripped within seconds; the cases were run one at
a time. The current smoke check is local-only and has no public-cluster funding support.

#### A v1 transaction sava did not write, devnet slot 495,643,187

Everything above was observed on transactions sava itself built. On 2026-09-09 a **third party's**
v1 transaction was captured from the same cluster and committed as
`sava-rpc/src/test/resources/rpc_response_data/getBlock-v1-devnet-4.3.0-beta.3.json`, read by
`RoundTripRpcRequestTests.getBlockWithDevnetV1Transaction`. It is a live `getBlock` body for
finalized slot 495,643,187, trimmed from 20 transactions to three — the v1 entry (original index
12), the legacy vote before it (11), and the block's only v0 entry (19) — each byte-verbatim, with
no other member of the response touched.

What that entry carries, none of which sava chose:

- **3,723 bytes**, three times the legacy 1,232-byte packet limit, in a single Memo v3 instruction
  whose 3,541-byte payload takes **no accounts at all**; the whole message names two addresses.
- **`TransactionConfigMask` `0x0000000c`** — compute unit limit (1,400,000) and account data size
  limit (1 MiB, not the 64 MiB `TxBuilder` reserves) set; the priority fee and heap bits absent, so
  `priorityFeeLamports()` and `heapSize()` read 0 and the node charged the 5,000 lamport base fee
  alone. The 1,242,354 units it consumed fit under the limit it declared.
- Rebuilding the parsed skeleton with `createTransaction()` reproduces those 3,723 bytes exactly:
  instruction headers, ConfigValues block and trailing signature included.

The v0 entry in the same fixture cross-checks the legacy/v0 fee derivation against the cluster: a
4,000 unit limit at 10,000 micro-lamports per unit is 40 lamports, and devnet charged 5,040. It also
sits *after* the v1 in the block, the mirror of slot 492,653,301 above — the pair is what shows the
version `-32015` names is the first entry the request could not serve, not the lowest or the highest
version the block holds.

A devnet reset reuses slot numbers, so the block this fixture came from is identified by its
blockhash `D62XQpRbzMUxQMknTRonhir9nsn2PL1pmBbVod4sUbS7`, not by slot 495,643,187 alone.

v1 traffic on devnet is still sparse — a scan of eight recent slots on 2026-09-09 found one v1
transaction among 181 — so a capture is worth keeping rather than re-derived on demand.

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
| `tx/V1Transaction.java`, `tx/V1TransactionSkeleton.java`, `tx/TxBuilder*.java` | SIMD-0385 v1 message wire format: `129` version byte, `TransactionConfigMask` + `ConfigValues`, fixed-width instruction headers, trailing signatures, no address-table lookups | `solana-improvement-documents:proposals/0385-transaction-v1.md`; `agave:runtime-transaction/src/runtime_transaction/transaction_view.rs` (`TransactionVersion::V1`) and `agave-sdk:transaction-view/`. Oracles: `sava-core/src/test/solana/v1-message-vectors/` (Rust `solana-message` `v1`, consumed by `V1MessageConformanceTests`), `sava-core/src/test/solana/kit-v1-vectors/` (`@solana/kit` 8 differential), and committed RPC captures: a local Agave 4.2.1 validator block and a public-devnet block carrying a third party's v1 transaction (`sava-rpc/src/test/resources/rpc_response_data/`). Original live checks ran against local 4.2.1/4.2.2/4.3.0-beta.3 validators and public devnet — see "Observed on Agave 4.2.1" and "Observed on public devnet" above. `LiveV1ValidatorCheck` now retains one optional local smoke check |
| `encoding/CompactU16Encoding.java` | short_vec / ShortU16 encoding | `solana-sdk:short-vec/` |
| `rpc/Filter.java`, `MemCmpFilter.java`, `DataSizeFilter.java` | `getProgramAccounts` filters; 128-byte memcmp cap | `agave:rpc-client-types/src/filter.rs` (`MAX_DATA_SIZE = 128`, checked in `RpcFilterType::verify`; `rpc-client-api` only re-exports it) + server enforcement in `agave:rpc/src/filter.rs` (`filter_allows`) |
| `zk/ElGamal.java` | ElGamal/Pedersen/AE byte-length constants used by confidential extensions | `solana-zk-sdk` `encryption::*` — crates.io only, version pinned in `agave:Cargo.toml` (`solana-zk-sdk = "8.0.0"`); agave's `zk-sdk/` was deleted in `a2ce908cfa` |
| `accounts/PublicKey.java`, `accounts/PublicKeyBytes.java`, `accounts/AccountWithSeed.java`, `accounts/lookup/AccountIndexLookupTableEntry.java`, `accounts/lookup/AccountIndexLookupTableView.java` | PDA derivation (32-byte seeds; 16 total seeds including the bump, so canonical find accepts 15 caller seeds; bump search 255..1; `"ProgramDerivedAddress"` marker; off-curve check); system create-with-seed UTF-8 bytes, rejection of unpaired Java UTF-16 surrogates to preserve Rust `&str` semantics, and illegal-owner marker guard; the ASCII off-curve helper reserves one of the 32 seed bytes for its nonce and applies the same owner guard; natural order (`compareTo` of every `PublicKey` implementation, the lookup-table entry and view included): lexicographic over the 32 bytes as unsigned values | `solana-sdk:address/src/lib.rs` (`create_with_seed`, the `Ord` derived on `Address`); `address/src/syscalls.rs` (`create_program_address`) |
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
  `sava-core/config/pitest/tx-accepted.csv` holds triaged survivors only, their
  reasons grouped by family in `sava-core/config/pitest/README.md`. New unkilled
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
  `alt_account`, a real mainnet lookup table, and the zero-signer regression pair that
  `NoSignerHeaderTests` replays), wired via the plugin's `seedCorpus`
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
leaving submission validation to the RPC. The array-based lookup is the exception: a lookup
table holds at most 256 accounts, so `AccountIndexLookupTableEntry.lookupAccountIndexOrThrow`,
which the `AccountIndexLookupTableEntry[]` overload of `Instruction.serialize` uses, rejects an
index outside 0 to 255. No address-lookup-table selection rule or within-category account
tie-break changed.

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
| `TxBuilder` defaults | CU limit `1_400_000`, data size 64MiB, both always serialized; fee and heap unset | unset stays unset | reserves both slots so they can be updated in place after simulating, and keeps the built transaction executable; `0` is the explicit clear. Verified on 4.2.1: the cleared bit fails preflight with `ComputationalBudgetExceeded` at 0 units, and `@solana/kit` fills the same provisory maxima before simulating — items 3 and 4 under "Observed on Agave 4.2.1" |
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

### v1 sanitization: rule → error mapping

Upstream's own v1 rejection rules, with the exact error each one raises, are in
`solana-sdk:message/src/versions/v1/message.rs` — `Message::validate` (the rule bodies) and its
`#[cfg(test)] mod tests` (`sanitize_*`, which pin the rule-to-variant mapping). `Sanitize for
Message` is just `validate()?`, so every rule surfaces through
`From<MessageError> for SanitizeError` in `solana-sdk:message/src/versions/v1/error.rs`: that
conversion, not the rule, decides `InvalidValue` vs `IndexOutOfBounds`. Constants come from
`v1/mod.rs` (`MAX_SIGNATURES = 12`, `MAX_ADDRESSES = 64`, `MAX_INSTRUCTIONS = 64`,
`MIN_HEAP_SIZE = 32KiB`, `MAX_HEAP_SIZE = 256KiB`). This mapping is more useful parity evidence
than any byte vector, because it is the one part of v1 that a wire fixture cannot express.

Note the layering before reading the table: upstream `deserialize` enforces **only** the two config-mask
rules; every population and index rule below lives in the separate `sanitize`/`validate` pass, which
decoding never calls. Sava has no `sanitize` entry point at all — it splits the same rules by whether
a violation still yields a faithful parse (see the paragraph above), so "sava accepts, reports via
`exceeds*Limit()`" is the deliberate stance for population limits, not an unimplemented check.

Rules are listed in `validate()` evaluation order, then the two deserialization-time rules.

| # | condition rejected | upstream `MessageError` | `SanitizeError` | sava today |
|---|---|---|---|---|
| 1 | `num_required_signatures > 12` | `TooManySignatures` | `IndexOutOfBounds` | accepts; reports via `Transaction#exceedsSignatureLimit()`; `TxBuilder` rejects when `strict` |
| 2 | `instructions.len() > 64` | `TooManyInstructions` | `IndexOutOfBounds` | accepts; reports via `Transaction#exceedsInstructionLimit()`; `TxBuilder` rejects when `strict` |
| 3 | `account_keys.len() > 64` | `TooManyAddresses` | `IndexOutOfBounds` | accepts; reports via `Transaction#exceedsAccountLimit()`; `TxBuilder` rejects when `strict` |
| 4 | `num_addresses < num_required_signatures + num_readonly_unsigned` | `NotEnoughAddressesForSignatures` | `IndexOutOfBounds` | **enforced** in `V1TransactionSkeleton.deserialize` (`IllegalStateException`) — it partitions the address array, so violating it mis-assigns privileges |
| 5 | `num_readonly_signed >= num_required_signatures` (includes `num_required_signatures == 0`) | `ZeroSigners` | `InvalidValue` | **enforced** in `V1TransactionSkeleton.deserialize` (`IllegalStateException`); `V1Transaction.isV1` additionally requires `data[1] != 0`, so a 0-signer message is not even dispatched as v1 |
| 6 | duplicate addresses in `account_keys` | `DuplicateAddresses` | `InvalidValue` | no check anywhere; parse stays faithful (duplicates appear twice in `parseAccounts`), and `TxBuilder` cannot emit them because it merges metas by key |
| 7 | `heap_size % 1024 != 0` | `InvalidHeapSize` | `InvalidValue` | `heapSize()` reports the wire value unchecked; `TxBuilder#heapSize` rejects when `strict` (0 clears the request) and `V1Transaction#setHeapSize` rejects unconditionally, both via `TxBuilderImpl.checkHeapSize` |
| 8 | `heap_size` outside `[32KiB, 256KiB]` | `InvalidHeapSize` | `InvalidValue` | same as 7 — `checkHeapSize` applies both bounds and the 1KiB alignment together |
| 9 | `account_keys` empty (`len - 1` underflows) | `NotEnoughAccountKeys` | `InvalidValue` | unreachable upstream: rules 4 and 5 already reject every empty-address header. Sava rejects the same inputs transitively through rule 4 |
| 10 | `program_id_index >= num_addresses` | `InvalidInstructionAccountIndex` | `IndexOutOfBounds` | **enforced** at parse by `requireIncludedProgramAccount` (`IndexOutOfBoundsException`), for every instruction including ones a discriminator filter skips |
| 11 | `program_id_index == 0` (fee payer invoked as program) | `InvalidInstructionAccountIndex` | `IndexOutOfBounds` | skeleton has no check — index 0 is a real account, so the parse is invalid-but-faithful. `TxBuilderImpl.createTransaction` refuses to emit one, unconditionally, not gated on `strict` |
| 12 | instruction accounts `> 255` | `InstructionAccountsTooLarge` | `InvalidValue` | unrepresentable on the wire (the `u8` count field), so no parse can observe it; `TxBuilderImpl.createTransaction` rejects unconditionally |
| 13 | instruction data `> 65535` | `InstructionDataTooLarge` | `InvalidValue` | unrepresentable on the wire (the `u16` length field); `TxBuilderImpl.createTransaction` rejects unconditionally where the field is written |
| 14 | instruction account index `>= num_addresses` | `InvalidInstructionAccountIndex` | `IndexOutOfBounds` | **enforced** by `requireIncludedInstructionAccount` (`IndexOutOfBoundsException`) in the views that materialize account metas; the `*WithoutAccounts` views step over the index bytes without reading them, so they neither validate nor need to — instruction boundaries come from the fixed-width headers |
| 15 | any config-mask bit outside bits 0-4 (`has_unknown_bits`) | wincode `invalid_value`, raised in `read` before `validate` ever runs | n/a — never becomes a `SanitizeError` | **enforced** in `V1TransactionSkeleton.deserialize` (`IllegalStateException`): the ConfigValues block is sized from the mask, so an unknown bit shifts every later offset |
| 16 | exactly one of the two priority-fee bits (`has_invalid_priority_fee_bits`) | wincode `invalid_value`, same path as 15 | n/a | **enforced** in `V1TransactionSkeleton.deserialize` (`IllegalStateException`) |

Boundaries upstream pins on the accept side, and which sava's fixture should keep matching: exactly
64 addresses, exactly 64 instructions, and heap sizes of exactly `MIN_HEAP_SIZE`, exactly
`MAX_HEAP_SIZE`, and 65536 are all valid.

Three further observations from that module worth carrying:

- `validate()` deliberately does **not** re-check the config mask. Upstream's comment is explicit: the
  mask is regenerated from the typed `TransactionConfig` on serialization, so a malformed mask can
  only exist in raw wire bytes and is a deserialization-layer rule. Sava places rules 15 and 16 at the
  same layer for the same reason.
- `MessageError::InvalidProgramIdIndex` is never returned by `validate` — both program-id failures
  (rules 10 and 11) return `InvalidInstructionAccountIndex`. It would map to `IndexOutOfBounds` either
  way, so the mapping is unaffected, but do not sync a rule to that variant on name alone.
- `BufferTooSmall`, `InvalidVersion`, `TrailingData`, `TransactionTooLarge`, `InvalidConfigMask` and
  `InvalidConfigValue` are declared in `MessageError` but unused in the current solana-sdk tree; they
  have no upstream rule to mirror. Sava's nearest equivalents are `V1Transaction.isV1`'s exact `0x81`
  discriminator, the signature-block boundary check quoted above (which is what catches trailing or
  truncated bytes), and `Transaction#exceedsSizeLimit()` against `MAX_SERIALIZED_LENGTH_V1 = 4096`.

Rules 12 and 13 mark a boundary the builder draws that `strict` does not cross. `strict` decides
whether a transaction would be acceptable to the network — the 64-account, 64-instruction,
12-signature and 4096-byte limits are all gated on it, so a caller can switch it off to construct and
analyze transactions the network would refuse. The wire format's own field widths are not limits of
that kind. `NumInstructions` and `NumAddresses` are single bytes and an instruction header spends one
byte on its account count and two on its data length, so a value past those ceilings cannot be
encoded at all: writing it modulo its field leaves a header block that no longer describes its
payload block, every later instruction offset moves, and the message cannot be read back. Over-limit
bytes are the point of a relaxed builder; self-inconsistent bytes are not, so
`TxBuilderImpl.createTransaction` enforces all four ceilings unconditionally. Under `strict` the
4096-byte bound makes every one of them unreachable, which is why they only matter once it is off.

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

- `./gradlew :sava-core:pitestBorsh` — PIT over the whole `borsh` package (a package
  wildcard with test-source exclusions). Baseline
  2026-07-17: 1070 mutations, **100% killed, 0 without coverage** — hold this bar;
  `BorshTests` covers the matrix families, `BorshCoreTests` the string/byte primitives,
  `BorshPrimitiveVectorTests`/`BorshReferenceVectorTests` the 1-D families, checked
  writers, Optionals, and length bounds, `RustEnumTests` every enum variant. Writers are
  exercised at non-zero offsets into dirty buffers — offset-arithmetic and
  dropped-write mutants are invisible at offset 0 into zeroed arrays.
- `./gradlew :sava-core:fuzzBorsh -PmaxFuzzTime=<seconds>` — Jazzer over `BorshFuzz`:
  every read family over every input; a successful read must re-serialize into exactly
  the promised `len*` bytes and re-read equal. The format is shallow (u32 length
  prefix + elements), so valid prefixes are reachable from scratch; the committed
  seeds are its regression corpus, the landing place for findings, replayed by `check`.

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
  (`test_short_vec_encode_len` and the good-value half of `test_deserialize`; their union is
  exactly the ten vectors in `testAgaveVectors`).
- Decode-into tests use dirty (non-zero) output buffers so dropped writes are observable.
- Randomized tests seed a `Random` from `SecureRandom` and embed the seed in failure
  messages; replay a failure by pinning the seed.

Verification tasks (not part of `test`; when each is owed is the gate rule in
`AGENTS.md`: the suite once per unpushed range, after review and before the push, and
the fuzz target in the local campaign before a release). Both are provided by the
shared `software.sava.build.feature.hardening` convention plugin (sava-build repo) and
configured via the `hardening {}` block in `sava-core/build.gradle.kts`:

- `./gradlew :sava-core:pitestEncoding` — PIT mutation testing of the four classes against
  their tests; report in `sava-core/build/reports/pitest/encoding`. Baseline (2026-07-16,
  Java 25 bytecode): 1064 mutations, 98% detected (that figure counted timed-out mutants
  as detected; today a timeout is audited on its own and never counts as a kill), 0
  without coverage; the survivors (20 baseline
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
and no Jazzer imports (so it compiles with the regular test sources), and register it in
the `hardening { fuzz.register("<name>") { ... } }` block with `targetClass`, an optional
`maxLen`, and a `seedCorpus` directory of committed seed inputs
(`layout.projectDirectory.dir("src/test/resources/fuzz/<name>")`, one file per input).
Every target gets a corpus, whatever its input format. For a structured format it is the
*bootstrap* — leaving such a target seedless is the single most common reason a fuzzer
plateaus at low coverage — and for every format it is the *regression* corpus: where a
finding's committed seed lands, replayed inside `check` (and as a killer under PIT) by the
`<Harness>SeedReplayTest` the plugin generates for it. Where neither job applies, record
that with `declineSeedCorpus("<reason>")` instead. Say what each seed pins in the module's
`src/test/resources/fuzz/README.md`, next to the corpus directories and never inside one,
where it would be fed to the harness as a seed. The plugin passes `seedCorpus` to libFuzzer
as a trailing read-only corpus: replayed every run, but only newly interesting inputs are
written back to the writable `build/fuzz/<name>-corpus`. `fuzzAll` derives its task list
from the registrations, so the new target joins the release campaign by itself. Listing
it in `FUZZ_TARGETS` in `.github/workflows/fuzz.yml` is optional and only adds it to that
manual-dispatch exploration run; a listed target in a module the workflow's "Upload
findings" step does not already cover also needs that module's `crash-*`, `oom-*`,
`timeout-*` and `build/fuzz/` paths there.

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

## Alpenglow (active on every public cluster)

Alpenglow (SIMD-0326 Votor consensus, plus SIMD-0357 VAT, SIMD-0384 migration, SIMD-0387
BLS vote keys, SIMD-0388 BLS syscalls; the SIMD files still read `status: Review` as of
2026-10-24, so SIMD status lags activation) replaced TowerBFT and PoH with off-chain
BLS-signed votes and finalization certificates. Feature gates in `agave:feature-set/src/lib.rs`:
`alpenglow` = `A1pengvuM6JEcyNuTnMqepBKhwHE3N6PmUrdATGawhJS` and
`bls_pubkey_management_in_vote_account` = `AnAP9zPV4KL7czAPQbFhpDKV2tx7g4UGNbK9wvXwjaRo`, which
governs the `VoterWithBLS` path below. Status probed 2026-10-24 through each cluster's
public RPC (`getAccountInfo` on the `alpenglow` feature account, `getAgGenesisCert`,
`getEpochInfo`, `getVersion`):

| cluster | activated at slot | epoch | genesis certificate slot | nodes |
|---|---|---|---|---|
| mainnet-beta | 459,648,000 (block time 2026-10-21T13:36:05Z) | 1064 | 459,652,999 | agave 4.3.2 |
| devnet | 504,144,000 | 1167 | 504,148,999 | 4.4.0-beta.1 |
| testnet | 444,620,256 | 1042 | 444,625,255 | 4.4.0-beta.1 |

The migration began at activation plus `MIGRATION_SLOT_OFFSET` (5,000 in a release build, 32
under `dev-context-only-utils`; `agave:votor-messages/src/migration.rs`), and each genesis
certificate names the last slot before it (the genesis block is the latest ancestor below the
migration slot). Epochs come from each cluster's `getEpochInfo` (`absoluteSlot − slotIndex`),
not from dividing the slot by 432,000: testnet's epoch 0 did not start at slot 0. The related
gates activated on mainnet months earlier: SIMD-0185 vote account v4 (epoch 949), SIMD-0387 BLS
key management (999), SIMD-0337 fast-leader-handover block markers
(`discard_unexpected_data_complete_shreds` = `disCA4efguFL6Wqa4pGdG7jpjC7C5uiKzKnhEBqchBe`, 1005),
SIMD-0357 VAT (1006). The agave wiki's feature-gate tracker (`feature-gate-tracker-schedule.json`,
`e2968a3`, read 2026-10-24) lists SIMD-0326 as needing Agave ≥ 4.3.0 or Firedancer ≥ v26.10.4,
with Frankendancer not supported; the Foundation guide says neither Firedancer nor Frankendancer
was supported for the migration, and the tracker's Firedancer minimum is a release tagged after
the switch. Mainnet has also run 200 ms slots since
epoch 1053 (2026-10-09, SIMD-0525, all four gates live; `agave:runtime/src/slot_params.rs`), so
an epoch takes about 24 hours and the 150-block blockhash window about 30 seconds. The Solana
Foundation's consumer guide is `solana-com:apps/media/content/upgrades/alpenglow.mdx`.
**Policy (owner, 2026-09-24): sava carries whatever activation breaks for an existing
caller — the vote-credit parsing below — and leaves Alpenglow-only surfaces
(`getAgGenesisCert`) unimplemented until a consumer needs one.** `getRankMap` (agave
`0cc6cf3acc`, 2026-10-06) falls under the same rule.

What activation changed for this library, on every public cluster:
- **Vote-credit history carries a marker no signed reader can hold.** At the migration
  epoch agave pushes `AG_MIGRATION_EPOCH_CREDIT` = `(Epoch::MAX, u64::MAX, u64::MAX)` into
  the `epoch_credits` of every vote account paid an Alpenglow reward during that epoch (`ensure_marker`,
  reached from `increment_credits` in
  `agave:runtime/src/block_component_processor/vote_reward.rs`), and `getVoteAccounts`
  serialises the tuple as plain JSON numbers, so the marker arrives as three
  `18446744073709551615` values and stays in the answer while it sits inside the RPC's
  credit window (`MAX_RPC_VOTE_ACCOUNT_INFO_EPOCH_CREDITS_HISTORY` = 5 entries,
  `agave:rpc-client-types/src/request.rs`, applied in `agave:rpc/src/rpc.rs`), one of which
  the marker itself occupies. `EpochCredits.parse` read each field with the signed
  `readLong`, which threw `value is too large for long` on any answer carrying it: every
  unfiltered `getVoteAccounts` call for as long as the marker sits inside the window. Since
  2026-09-24 (sava-rpc 25.11.2) the three fields are the `u64` bits in a `long` (CONVENTIONS.md,
  "Unsigned longs and sentinel zeros"), read without allocating: the marker reads as three `-1L`
  and is kept in the history rather than dropped; signed values, quoted bare digits and the
  no-leading-zero rule parse as before; whitespace inside the quotes, which the old reader
  tolerated and no node writes, is rejected; a value past `u64` or below `-2^63` is a
  `JsonException` naming the field (`EpochCreditsTests`, `ValidatorInfoRpcRequestTests`).
  Mainnet, probed 2026-10-24 in epoch 1066: 658 of 664 current vote accounts carried the marker
  in the window `[1064, marker, 1064, 1065, 1066]`, and the published sava-rpc 25.12.0 parsed the
  live payload (664 current, 14 delinquent). Those windows clear at each account's epoch-1068
  entry, but six staked delinquent accounts (about 289k SOL together) carry the marker in
  windows that move only when they earn credits again, and the default response lists staked
  delinquents, so for a consumer on sava-rpc below 25.11.2 the unfiltered failure has no end
  date: upgrading is the only fix.
- **The unit of `epochCredits` changed with the migration, and the migration epoch can appear
  twice.** After the migration, `increment_credits` adds each block's vote reward in lamports
  (the validator half of `calculate_reward`, plus the leader's share for the leader), not vote
  credits; agave's own CLI labels those deltas `lamports earned` and classifies rows by epoch
  against the migration epoch (`get_epoch_history` in `agave:cli-output/src/cli_output.rs`),
  consulting the marker only for the migration epoch's own rows. An account paid an Alpenglow
  reward during the migration epoch gets the marker and then an entry
  `(migration_epoch, reward + final_tower_credits, final_tower_credits)`, so its `credits` is
  continuous across the marker and that epoch has a TowerBFT half and an Alpenglow half; an
  account first paid in a later epoch gets no marker and its first lamport row follows the
  TowerBFT rows directly. The five-row RPC window drops the marker after four more epochs while
  it can keep the Alpenglow half, so a migration-epoch row with no marker in view cannot be
  classified from the window alone. Per-row `credits − previousCredits` stays valid within one
  unit; a sum, difference or comparison across the switch, or a map keyed by epoch, is not. The
  stake program's `credits_observed` inherits the unit, since it tracks the vote account's
  latest credits (`agave:runtime/src/inflation_rewards/points.rs`), and is documented at
  `StakeAccount.creditsObserved` in idl-clients-spl. `EpochCredits`, `VoteAccount` and
  CONVENTIONS.md carry the consumer-facing version.
- **`lastVote` and `rootSlot` stay current.** For every validator in a block's reward
  certificate (`RewardState::update_votes`, at the reward slot) and every signer of a
  finalization certificate a block carries (`FinalCertState::update_account`), `vote_reward.rs`
  (`maybe_update_votes`, `maybe_update_root`) replaces the on-chain `votes` with one `LandedVote`
  at the greatest of the certificate slot, the last vote and the root; only the finalization
  path advances `root_slot`. So the `current`/`delinquent` split of
  `getVoteAccounts` (`DELINQUENT_VALIDATOR_SLOT_DISTANCE`) still works; live, `lastVote` sat
  within about ten slots of the head. jsonParsed vote accounts
  (`agave:account-decoder/src/parse_vote.rs`) carry the SIMD-0185 v4 commission and collector
  fields and `blsPubkeyCompressed`; `prior_voters` is always empty; the `votes` list holds that
  one entry rather than emptying.
- **Commitment levels.** On an Alpenglow agave node `confirmed` and `finalized` both move when
  votor roots a block covered by a finalization certificate, through two channels: `finalized`
  from the commitment cache (`CommitmentType::Rooted` in `agave:core/src/commitment_service.rs`
  sets `highest_confirmed_slot`, `root` and `highest_super_majority_root` to the same slot) and
  `confirmed` from the optimistically-confirmed bank, which `agave:votor/src/root_utils.rs`
  advances by sending `BankNotification::OptimisticallyConfirmed(new_root_slot)` "to keep
  compatibility with the RPC API", with a note that OC may be deprecated in future. Released
  tags send `Rooted` before the OC notification; master `fa399f89f9` (in no tag) publishes
  `Rooted` only after replay applies the root, so either level may briefly lead the other, and
  no 32-slot gap stands between them. `processed` is the slot of the node's own notarize vote
  (`CommitmentType::Notarize` → `set_slot`; a non-voting RPC node makes the same decision
  without sending a vote). The Foundation guide says `confirmed` and `finalized` "describe the
  same state", that `processed` is unchanged, and that `confirmed` "is removed in a later
  release" with new code to read `finalized`; agave itself has not deprecated the level. The
  sava-rpc client and websocket builders default to `FINALIZED` since 2026-10-24 (`Commitment`
  has the consumer-facing wording).
- **`getSignatureStatuses`.** `confirmations` is `get_confirmation_count(slot).or(Some(0))`
  until the slot is rooted and `null` after (`agave:rpc/src/rpc.rs`), and the per-block lockout
  map it reads is never filled under Alpenglow, so the value is 0 until finalized and then
  absent; `confirmationStatus` can go `processed` → `finalized` with `confirmed` at most
  transient. `TxStatus` and `TxSig` document this.
- **`getBlockCommitment` is dead.** The per-block lockout array is never written for any slot
  after the Alpenglow genesis, so the answer is `{"commitment":null,"totalStake":0}` on a node
  started after the switch (live mainnet, 2026-10-24) and `null` with a stale TowerBFT total on
  one that ran through the migration (code reading: only a restart resets
  `BlockCommitmentCache`). agave, its Rust client, Kit and solana.com have not deprecated the
  method; sava removed `getBlockCommitment` and `BlockCommitment` in 25.13.0 (deprecated for
  removal and then dropped in the same release, on the owner's call) because no Alpenglow
  depth metric exists: finality is read from `getSignatureStatuses` or at
  `Commitment.FINALIZED`. For a TowerBFT test validator the array was `[u64; 32]`, index `n − 1`
  the stake with confirmation count `n` and index 31 the rooted stake
  (`agave:runtime/src/commitment.rs`).
- **TowerBFT RPC cleanup upstream.** agave master commit `1513b7a5bb` (after the v4.5.0-alpha.2
  tag, in no release and absent from the 4.5.0 changelog as of 2026-10-24) removes `tpu_vote`
  from `RpcContactInfo`, deprecates `RpcTransactionLogsFilter::AllWithVotes` ("Alpenglow has no
  vote transactions; use `All`"), documents `RpcVote` as a legacy payload kept until the next
  major release, keeps `voteSubscribe` registered with an empty `notify_vote` and turns
  `--rpc-pubsub-enable-vote-subscription` into a no-op (so master accepts the subscription by
  default and never notifies it, where every release through v4.5.0-alpha.2 refuses it with
  `-32601` unless started with that flag), and makes `getHealth` compare only against Votor's
  highest finalized slot; `ca21544655` stops binding and advertising the UDP TPU vote socket. 4.3.x and 4.4.x RPC
  servers still send `tpuVote` (3,345 of 3,922 mainnet nodes on 2026-10-24), while `tpu` and
  `tpuForwards` have been `null` from every agave ≥ 4.0 server (`tpu: None`,
  `tpu_forwards: None` in `agave:rpc/src/rpc.rs`); sava deprecated all three `ClusterNode`
  accessors for removal on 2026-10-24. sava never exposed `allWithVotes` or wrapped
  `voteSubscribe`.
- `getAgGenesisCert` (`agave:rpc/src/rpc.rs`, shipped in agave 4.2.0) returns the finalized
  bank's genesis certificate — the ≥ 82 % genesis-vote aggregate that names Alpenglow's genesis
  block (SIMD-0384): `{block: {slot, blockId}, signature: {bitmap, signature}}`
  (`agave:votor-messages/src/wire.rs`; camelCase keys since 4.3.0, from `dce3730aad` on master
  with the v4.3 backport `0ca0646bde`, while 4.2.x serialized `block_id`); every public cluster
  now returns one, for the slots in the table above. `getRankMap` (agave `0cc6cf3acc`, first in v4.5.0-alpha.2; `-32601` on
  4.3.2) returns an epoch's validators ordered by Alpenglow rank with vote and node keys, the
  compressed BLS key and stake, the rank being the index into certificate bitmaps. Both are
  deliberately unimplemented — see the policy above.
- Vote program: `VoteAuthorize::VoterWithBLS` (48-byte pubkey + 96-byte proof-of-possession
  over `"ALPENGLOW" || vote_pubkey || bls_pubkey`); BLS verification adds 34,500 CUs and the
  vote program left static builtin cost modeling. Once the migration succeeded the program
  rejects `TowerSync`, `TowerSyncSwitch` and the legacy vote instructions
  (`agave:programs/vote/src/vote_processor.rs`), so vote transactions no longer land and no
  longer count in blocks or performance samples. `TransactionSkeletonParseTests` keeps a
  pre-activation `TowerSync` capture as an offline parser fixture.
- VAT (SIMD-0357): 0.8 SOL per epoch at 200 ms slots (`vat_to_burn_per_epoch` in
  `agave:runtime/src/slot_params.rs`; 1.6 SOL at 400 ms) deducted from each vote account and
  burned, so the Foundation guide tells validators to hold at least 0.8 SOL above rent
  exemption; active validator set capped at 2,000.
- **`Clock.unix_timestamp` has a new source.** Under TowerBFT it was the stake-weighted median
  of the timestamps validators attached to their votes; under Alpenglow the leader writes a
  nanosecond timestamp into its block footer, which replay rejects unless it is strictly after
  the parent's and at most twice the slot time elapsed since the parent past it
  (`enforce_nanosecond_clock_bounds`, `nanosecond_time_bounds` in
  `agave:runtime/src/block_component_processor.rs`), and `update_clock_from_footer`
  (`agave:runtime/src/bank.rs`) stores it truncated to whole seconds, never decreasing from
  parent to child but commonly repeated across consecutive 200 ms blocks, taking
  `epoch_start_timestamp` from it at epoch boundaries. The layout is unchanged; `Clock`'s
  javadoc carries the consumer-facing version.
- Test tooling: `solana-test-validator` 4.2.x through 4.4.x runs TowerBFT unless started with
  `--alpenglow`; from agave 4.5 (`fd2f68b6ee`, v4.5.0-alpha.2) Alpenglow is on at genesis and
  the flag has no effect, so a TowerBFT test validator can no longer be started. Surfpool
  serves `confirmed` at the tip minus 1 and `finalized` at the tip minus 31
  (`FINALIZATION_SLOT_THRESHOLD` in `surfpool:crates/core/src/surfnet/mod.rs`, `bbd2c23`, read
  2026-10-25), and its `getVoteAccounts` (`surfpool:crates/core/src/rpc/minimal.rs`) returns
  empty lists, so the marker path is covered only by the fixtures and the fuzz seed.

Explicitly unchanged: all sysvar layouts (Clock, EpochSchedule, EpochRewards, Rent,
StakeHistory, SlotHashes, LastRestartSlot), the transaction wire format, recent-blockhash
expiry (150 blocks, about 30 seconds at 200 ms), user transaction fees, `RewardType`,
`RpcPerfSample.numNonVoteTransactions` (which now equals `numTransactions`), and the `sdk-ids`
and `reserved-account-keys` sets (no new program or sysvar id). Canonical sources:
`agave:votor-messages/src/` (wire format, certificates, migration phases),
`agave:votor/src/` and `agave:core/src/commitment_service.rs` (commitment),
`agave:runtime/src/block_component_processor/vote_reward.rs` (credits, votes, roots), and the
SIMD files in the solana-improvement-documents repo.

## Known gaps / candidate work

- HTTP RPC: `getAgGenesisCert` and `getRankMap` not implemented (deliberate — see Alpenglow
  above); `getBlockCommitment` removed in 25.13.0 because Alpenglow nodes answer it with no data.
- WebSocket: typed wrappers for `slotsUpdatesSubscribe`, `blockSubscribe`, and
  `voteSubscribe` are deliberately omitted — public RPC infrastructure does not support
  those subscriptions, and `voteSubscribe` is dead under Alpenglow (refused by default on
  released servers, never notified wherever accepted). The generic
  `SolanaRpcWebsocket.subscribe(...)` passthrough is the intended mechanism for
  provider-specific or unsupported methods; see the sava-software/helius-sdk repo
  (`software.sava.helius.ws.HeliusRpcWebsocket` wrapping Helius' `transactionSubscribe`)
  for a real-world example. Do not add typed wrappers without a supporting provider.
- `SolanaAccounts` deliberately omits deprecated/dormant reserved keys
  (`bpf_loader_deprecated`, `bpf_loader` v2, `loader_v4`, `native_loader`, `feature`,
  `sysvar::rewards`, `sysvar::fees`, `zk_token_proof_program`) — do not add without need.
  `sysvar::fees` is `#[allow(deprecated)]` in the reserved list itself: kept there because
  keys cannot leave it without breaking consensus, read by nothing. `zk_token_proof_program`
  is superseded by `zk_elgamal_proof_program`, which `SolanaAccounts` carries; Token-2022 no
  longer references the old program, and upstream registers it with only a test-only
  migration config. `incinerator` is *not* among them: it is an sdk id, never a reserved
  account key, so there is nothing to omit.
- Sysvar decoders: Clock and EpochRewards are public; Rent, EpochSchedule, StakeHistory,
  SlotHashes, LastRestartSlot are package-private (make public on demand). SlotHistory
  (131KB bit-vector) is not modeled. Fixture-backed tests in
  `sava-core/src/test/java/software/sava/core/accounts/sysvar/SysvarTests.java`.
- Multisig is not modeled here (generated type lives in idl-clients-spl).
- Live parser drift check: `DRIFT_CHECK=true ./gradlew :sava-rpc:test --tests
  '*LiveMainNetDriftCheck'` exercises the production parsers against current main-net
  responses; rate-limited methods are skipped and reported.
- Local transaction v1 smoke check: `SAVA_V1_LIVE=true ./gradlew :sava-rpc:test --tests
  '*LiveV1ValidatorCheck.basicV1Transfer' --rerun` simulates a large v1 transaction,
  tightens its resource limits in place, sends it and checks the confirmed bytes, config and fee
  through `getTransaction` and `getBlock`. It requires a local 4.2.1+ test-validator with v1 active
  and a faucet (`SAVA_V1_RPC_URL`, default `http://127.0.0.1:8899`, loopback endpoints only).
  `sava-rpc/src/test/solana/v1-live/README.md` documents manual startup with a disposable ledger.
- Compute-budget instruction builders live outside sava-core; constants reference
  `agave:compute-budget/src/compute_budget_limits.rs` and
  `solana-sdk:compute-budget-interface/` (watch SIMD-0268 default changes).
- Watch for larger-transaction SIMDs: use `Transaction.exceedsSizeLimit()` when checking
  transaction size. The built-in implementations apply the format-specific limit (1232 bytes
  for legacy/v0, 4096 for v1); account limits can also vary by format.
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
| solana-sdk, error enums only | `983858e1` | 2026-09-20 | `transaction-error/` and `instruction-error/` variant sets, via the committed error-variants fixture (crates 4.0.0 and 3.0.0, published from `9d02e6dc` and `5bcc7778`, source unchanged since). `sdk-ids/` and every other solana-sdk surface were not rechecked and keep the row above. |
| solana-sdk, address ordering only | `3da2b8b9` | 2026-10-06 | `address/src/lib.rs`: `Address` (re-exported as `Pubkey`) derives `Ord`/`PartialOrd` over `[u8; 32]`, with no manual impl in `address/` or `pubkey/`; `PublicKey.compareTo` mirrors that order. Nothing else in solana-sdk was checked in this pass, PDA derivation in the same crate included. |
| solana-com | `ccf32de8e` | 2026-10-24 | Documented HTTP/WebSocket method lists (`apps/docs/content/docs/en/rpc/`) re-compared to the client surface: the only documented, unimplemented HTTP method is `getAgGenesisCert` (deliberate), `getRankMap` is not yet documented, and the WebSocket gaps are the ones listed under Known gaps; `apps/media/content/upgrades/alpenglow.mdx` and `reduced-slot-times.mdx` read for the Alpenglow section |
| solana-improvement-documents | `a084840` | 2026-10-24 | Alpenglow SIMDs 0326/0357/0384/0387/0388 unchanged since `05f2ae9` and still `status: Review`; 0185 (`Accepted`) and 0525 (`Draft`, live on mainnet) read for the Alpenglow section |
| agave, Alpenglow only | `bceaf2eed1` | 2026-10-24 | `votor-messages/src/migration.rs` (marker, `MIGRATION_SLOT_OFFSET`), `runtime/src/block_component_processor/vote_reward.rs` (marker insertion, lamport credits, `maybe_update_votes`/`maybe_update_root`), `rpc/src/rpc.rs` (`getVoteAccounts` window, `getAgGenesisCert`, `getRankMap`, `fn bank`, `getSignatureStatuses` confirmations, `get_block_commitment`, `getClusterNodes`), `rpc-client-types/src/{request,response,config}.rs` (`MAX_RPC_VOTE_ACCOUNT_INFO_EPOCH_CREDITS_HISTORY`, `RpcContactInfo`, `RpcRankMap`, `AllWithVotes`), `core/src/commitment_service.rs`, `votor/src/{root_utils,commitment}.rs`, `runtime/src/commitment.rs`, `runtime/src/bank.rs` (`update_clock_from_footer`), `runtime/src/block_component_processor.rs` (`enforce_nanosecond_clock_bounds`, `nanosecond_time_bounds`), `core/src/replay_stage.rs` (Rooted publish, `fa399f89f9`), `runtime/src/slot_params.rs`, `runtime/src/inflation_rewards/points.rs`, `programs/vote/src/vote_processor.rs`, `rpc/src/{rpc_pubsub,rpc_subscriptions,rpc_health}.rs` (`voteSubscribe` gate and stub, `getHealth`), `gossip/src/node.rs` (UDP `tpu_vote`, `ca21544655`), `cli-output/src/cli_output.rs` (`lamports earned`, `get_epoch_history`), `feature-set/src/lib.rs`, `account-decoder/src/parse_vote.rs`, `votor-messages/src/wire.rs`, `CHANGELOG.md`; the same paths compared at tag `v4.3.2` where the section cites the mainnet release; feature status probed on mainnet-beta, devnet and testnet |
| agave-sdk | — | — | **Never verified.** Declared as a reference repo and cited for `transaction-view/` (the zero-copy v1 parser and its `sanitize`), but no sync pass has ever recorded a hash here, so there is no diff base. |
| transaction-v1-examples | — | — | **Never verified.** Cited for `ts/kit/src/estimate.ts` (kit's v1 resource-limit flow); no sync pass has ever recorded a hash, so there is no diff base. |

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
   `Token2022.parseExtensions`; add the record to the sealed `TokenExtension` hierarchy,
   its fixed `ordinal()`, dispatch case and diagnostic name, and a round-trip test (plus
   a real fixture when available). Keep wire IDs and lengths checked against the frozen
   Rust conformance fixtures.
5. For RPC methods: compare `rpc.rs` registrations against `SolanaJsonRpcClient.java`
   literals; add interface method, request builder, response record + parser, and a
   `RoundTripRpcRequestTests` case.
6. For errors: check `agave:rpc-client-api/src/custom_error.rs` for codes past `-32021`.
   For `TransactionError` and `InstructionError` the mirror is gated by the committed
   fixture under `sava-rpc/src/test/solana/error-variants/`: review the two crate pins in
   its `Cargo.toml` against the newest published `solana-transaction-error` and
   `solana-instruction-error`, bump them deliberately, regenerate with `--write`, and let
   `UpstreamErrorVariantsConformanceTests` name any variant the Java side lacks. Exact
   pins stay green after upstream adds a variant until someone bumps them, so the fixture
   is the regression gate and this step is the discovery.
7. Run `./gradlew :sava-core:test :sava-rpc:test` (integration tests via `integ.sh`).
8. Before pushing, the mutation gate in `AGENTS.md` ("Quality gate & mutation ratchet")
   applies once to the whole unpushed range, after review.
