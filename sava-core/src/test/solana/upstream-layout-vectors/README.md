# Solana Token-2022 and sysvar layout vectors

This small locked Rust program generates committed fixtures for Sava's
Token-2022 extension and sysvar conformance tests. It is first-party defensive
testing of client-side parsing and serialization. It is not part of the
published library, and Gradle never runs Rust or accesses the network.

The manifest pins the versions selected by Agave commit
`b9687a87037c787fa3257dc62fa00ff4708f1879`:

- `spl-token-2022-interface` 3.1.1, `spl-token-group-interface` 0.7.2,
  `spl-token-metadata-interface` 1.0.1, and `spl-type-length-value` 0.9.1;
- `solana-epoch-rewards` 3.2.0, `solana-epoch-schedule` 3.3.0, and
  `solana-program-option` 3.1.0, and `solana-rent` 4.4.0;
- `wincode` 0.6.1, which Agave uses for the sysvar wire representation.

The extension fixture asks each concrete fixed-size Rust extension type to
accept its exact TLV value through `BaseStateWithExtensions::get_extension` and
to reject values one byte short and long. Token metadata is packed and unpacked
through the public `VariableLenPack` API, including the largest value that fits
the TLV `u16` length and the first value that does not. A separate fixture reads
byte `2` through each Token-2022 `solana_zero_copy::unaligned::Bool` field, whose
canonical conversion treats every nonzero value as true. Sysvars are serialized
with their current `wincode` derives, including invalid-bool rejection probes;
rent outcomes come directly from `Rent::try_minimum_balance`.

The full-account fixture follows Agave's own construction pattern from
`account-decoder/src/parse_token.rs` (`test_parse_token_account_with_extensions`,
`test_parse_token_mint_with_extensions`, and
`test_parse_token_mint_with_permissioned_burn`). It uses the pinned SPL
`StateWithExtensionsMut` API to allocate, populate, and pack one complete mint
and one complete token account, then asks `StateWithExtensions` to unpack the
bytes and recover the same base state and ordered extension list. The two rows
exercise thirteen extension instances between them, with non-default values in
every valued extension; their committed wire bytes are parsed and serialized
byte-for-byte by the Java tests.

The fixtures record and the Java test verifies the generator, manifest,
toolchain, and lock hashes. The generator additionally validates the semantic
crate checksums in `Cargo.lock`. Regenerate or verify from this directory with:

```sh
NO_DNA=1 cargo run --locked --release -- --write
NO_DNA=1 cargo run --locked --release -- --check
```

Any dependency change must update `Cargo.lock`, regenerate the fixtures, and
deliberately update the pinned metadata asserted by the Java test.
