# Seed corpora

Each directory here is a fuzz target's committed seed corpus (`seedCorpus` in
`sava-core/build.gradle.kts`), replayed on every `check` by a plugin-generated
`<Harness>SeedReplayTest` in the harness's package — so the corpus cannot rot
between fuzz runs, and under PIT the replay participates as a killer. New
seeds, including minimized fuzz findings, replay automatically; a fuzz finding
is only closed by a committed seed here **plus** a named regression test.

This file lives next to the corpus directories, never inside one: every file
inside a corpus directory is fed to the harness as a seed.

Two different jobs are collected here, and the distinction decides whether a
seed earns its place. `token2022` and `txSkeleton` are **bootstrap** corpora:
their formats (TLV chains, header/offset/length agreement) are ones a mutator
starting from nothing would take a long time to assemble, so the seeds buy
coverage. `base58` and `borsh` are **regression** corpora: those formats are
reachable from scratch in seconds, so the seeds buy no coverage — they exist
so a finding has somewhere to land, and so the harness runs deterministically
inside `check` instead of only during a campaign. Judge a proposed seed
against the job its corpus does.

## `base58` — [Base58Fuzz](../../java/software/sava/core/encoding/Base58Fuzz.java)

Regression corpus. The harness is differential — every decode and encode
variant must agree, and an input either round trips canonically or is rejected
by all of them — so each seed pins an agreement, not merely the absence of a
crash:

- `system_program_id` — 32 `1` characters, the System Program id: an all-zero
  key, which is the leading-zero convention in both directions at once.
- `token_program_id` — the Token Program id: a dense 32-byte key with no
  leading zeros, exercising the full limb chain.
- `leading_zeros_mixed` — leading zero bytes followed by content, where the
  `1` run and the limb chain must agree on where one ends and the other begins.
- `non_base58_alphabet` — `0OIl`, the four excluded characters: the rejection
  path, and the input that must make `nonBase58` report an index.
- `single_digit` — the shortest input that still decodes; drives the split
  encode's `maxLen` from `data[0]`.
- `high_bytes_key` — 32 `0xFF` bytes: rejected as text, but the encode
  direction's widest carry chain.

## `borsh` — [BorshFuzz](../../java/software/sava/core/borsh/BorshFuzz.java)

Regression corpus. The first byte selects the array families' `fixedLength`;
the rest is the vector payload. Whenever a read succeeds, re-serializing must
consume exactly the promised `len*` bytes and re-read equal:

- `byte_vector` — a well-formed `u32` prefix and payload, the simplest
  agreement between the two.
- `string_vector` — two length-prefixed strings, one empty: the nested prefix
  chain, where outer count and inner lengths must both be honoured.
- `string_invalid_utf8` — invalid UTF-8, which reads as replacement chars, so
  the round trip holds on parsed values rather than original bytes.
- `u128_vector` — the `BigInteger` element path, 16 bytes with the high bit set.
- `oversized_length_prefix` — a `u32` count larger than the remaining bytes can
  back: the allocation guard, the invariant this harness exists for.
- `empty_vector` — a zero-length vector, the boundary where an off-by-one in
  the prefix arithmetic is invisible to every larger case.
- `int_matrix` — two 2-element vectors: the multi-dimension reader, whose outer
  and inner counts are separate prefixes.

## `ed25519` — [Ed25519Fuzz](../../java/software/sava/core/crypto/ed25519/Ed25519Fuzz.java)

Regression corpus. Every input is one 32-byte point encoding and one keygen
seed at once, and the harness is differential — sava must agree with
BouncyCastle where BouncyCastle has a verdict, with a BigInteger decompression
reference where it does not, and with itself under a sign-bit flip — so each
seed pins one oracle path:

- `base_point` — the ed25519 base point: the canonical on-curve verdict
  BouncyCastle and sava share.
- `small_order_identity` — y = 1, the identity: on-curve for sava (dalek
  semantics), rejected up front by BouncyCastle, so the reference owns it.
- `small_order_order8` — an order-8 torsion point: the other
  BouncyCastle-reject-set shape, dense rather than degenerate.
- `boundary_p_minus_one` — y = p - 1: the largest canonical y, also in
  BouncyCastle's reject set.
- `non_canonical_p` — y = p, the smallest non-canonical encoding: must
  decompress as its reduced form, y = 0.
- `high_bytes` — 32 `0xFF` bytes: non-canonical y with the sign bit set, and
  the keygen seed with every clamp bit initially wrong.
- `rfc8032_seed` — RFC 8032 test vector 1's seed: the keygen agreement on a
  pinned known answer.
- `all_zero` — 32 zero bytes: y = 0 (reject-set path) and the degenerate
  keygen seed.

## `token2022` — [Token2022Fuzz](../../java/software/sava/core/accounts/token/Token2022Fuzz.java)

Bootstrap corpus. Real TLV chains that from-scratch tests don't assemble,
giving PIT's mutants the same round-trip oracle as the fuzzer:

- `pyusd_mint` — the PYUSD mint, 8 extensions including TokenMetadata.
- `confidential_account` — a confidential token account.

## `txSkeleton` — [TransactionSkeletonFuzz](../../java/software/sava/core/tx/TransactionSkeletonFuzz.java)

Bootstrap corpus. Real transactions whose header/offset/length agreement a
from-scratch test doesn't assemble, plus a real lookup-table account:

- `legacy` — a legacy transaction.
- `versioned_lut` — a versioned transaction using an address lookup table.
- `versioned_trunc` — a truncated versioned transaction.
- `alt_account` — a real address-lookup-table account.
