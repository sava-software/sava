# Solana transaction v1 (SIMD-0385) message vectors

This locked Rust program generates the committed v1-message fixture at
`sava-core/src/test/resources/tx/solana-v1-message-vectors.tsv`. It is first-party
defensive conformance testing for Sava's transaction v1 reader, builder, and signer;
it is not part of the published library, and Gradle does not run Rust or access the
network.

The oracle is `solana-message` 4.5.0's `v1` module — `v1::Message::try_compile_with_config`,
`v1::Message::serialize`, `v1::deserialize`, `v1::Message::validate`, `TransactionConfig`,
and `TransactionConfigMask` — together with `solana-transaction` 4.2.0's
`VersionedTransaction` wincode reader and writer. Those are the same crate versions the
sibling `legacy-message-vectors` generator already pins, at the same
`anza-xyz/solana-sdk` commit recorded in the fixture; the four `message/src/versions/v1`
sources at that commit are byte-identical to the published 4.5.0 crate in the local
registry. No lookup-table semantics, no legacy or v0 message format, and no runtime
compute-budget behaviour is exercised here.

Sava's own v1 implementation supplies none of the fixture's bytes. Every offset, length,
mask value, header, and error in the TSV is produced by executing Rust, so a
self-consistent off-by-one in a Sava constant fails the consumer test instead of hiding.

## What the fixture pins

* **Framing is inverted from legacy.** A v1 transaction is the serialized message
  followed by the signature block. There is no leading compact-u16 signature count; the
  signature count comes from the message header's `num_required_signatures`, and
  `signature_block_offset` equals `message_len` on every row. `transaction_hex` carries
  the literal `VersionedTransaction` wire bytes for the `framing` rows, which are the
  only rows with real ed25519 signatures.
* **The version prefix is asymmetric.** `v1::Message::serialize()` *emits* the `0x81`
  prefix, and `VersionedMessage::serialize()` — the byte string that is actually signed —
  emits it too. `v1::deserialize()` does *not* consume it: it reads a bare message body,
  and feeding it the prefixed bytes fails. Both halves are executed at generation time
  and recorded as `v1-message-serialize-emits-version-prefix`,
  `v1-message-deserialize-consumes-version-prefix`, and the per-row
  `rust_deserialize` / `rust_deserialize_with_prefix` columns. `message_hex` always
  includes the prefix; `rust_deserialize` is computed on `message_hex[1..]`.
* **Field offsets are absolute and fixed.** `version=0`, `header=1`, `config_mask=4`,
  `lifetime_specifier=8`, `num_instructions=40`, `num_addresses=41`, `addresses=42`. The
  config-values block, instruction headers, and instruction payloads follow at
  address-count- and mask-dependent offsets. Every offset in the `offsets` column is
  recomputed from the emitted wire bytes, not from the in-memory struct.
* **The priority fee is a `u64`, not a `u32`.** Its mask is the two-bit pair `0b11`, and
  its config value occupies **eight** bytes. The other three fields are single bits over
  `u32` values. "Four bytes per set mask bit" therefore holds arithmetically, but a
  reader that treats the priority fee as a single 4-byte value will misplace every
  config value after it. All sixteen valid mask combinations are enumerated with each
  present value's absolute offset.
* **Mask errors are invisible to `validate()`.** Serialization regenerates the mask from
  the typed `TransactionConfig`, so a half-set priority-fee pair or an unknown bit can
  only exist in raw wire bytes. Those vectors are byte-patched after serialization; they
  report `rust_validate=true` and `rust_deserialize=false`. The patch is applied at the
  same absolute offset in both `message_hex` and the transaction wire, which is itself a
  consequence of the message leading the transaction.
* **Limit violations are semantic, not framing.** Too many signatures, addresses, or
  instructions, a readonly-signed count that swallows the fee payer, an address count
  below `num_required_signatures + num_readonly_unsigned_accounts`, a program id index of
  zero, an out-of-range account index, and a heap size that is not a 1KiB multiple or is
  outside `[32KiB, 256KiB]` are all rejected by `validate()` while still deserializing
  and round-tripping as bytes. Each rejection is paired with an accepted boundary vector.

Rejections carry `rust_validate`/`rust_deserialize` booleans plus the Rust error
discriminant, mirroring how the legacy fixture carries `rust_wire_round_trip`.

The `ordering` vectors reuse the exact instruction shapes the legacy fixture already
compiles, so signer/writable promotion, duplicate account references, key ordering, and
header derivation can be diffed between the two fixtures. `addresses_hex` is only
populated for those rows; everywhere else the address array is a verbatim slice of
`message_hex` at offset 42 and is not restated.

## Determinism

All keypairs come from fixed seed arrays, all filler addresses are derived from their
index, and there are no timestamps or randomness. Regenerating produces a byte-identical
fixture. The fixture records the sha256 of `Cargo.lock`, `Cargo.toml`, `src/main.rs`, and
`rust-toolchain.toml`, and the generator refuses to run unless `Cargo.lock` pins each
dependency to the exact version and checksum it expects.

The generator also asserts its own expectations: every vector declares whether Rust must
accept or reject it, and a disagreement aborts generation rather than silently rewriting
the fixture.

Regenerate or verify from this directory with:

```sh
NO_DNA=1 cargo run --locked --release -- --write
NO_DNA=1 cargo run --locked --release -- --check
```

`--check` compares generated bytes with the committed TSV. Dependency changes require a
deliberate lockfile update, fixture regeneration, and matching Java provenance updates.
