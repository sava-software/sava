# Seed corpora

Each directory here is a fuzz target's committed seed corpus (`seedCorpus` in
`sava-core/build.gradle.kts`), replayed on every `check` by a plugin-generated
`<Harness>SeedReplayTest` in the harness's package — so the corpus cannot rot
between fuzz runs, and under PIT the replay participates as a killer. New
seeds, including minimized fuzz findings, replay automatically; a fuzz finding
is only closed by a committed seed here **plus** a named regression test.

This file lives next to the corpus directories, never inside one: every file
inside a corpus directory is fed to the harness as a seed.

## `token2022` — [Token2022Fuzz](../../java/software/sava/core/accounts/token/Token2022Fuzz.java)

Real TLV chains that from-scratch tests don't assemble, giving PIT's mutants
the same round-trip oracle as the fuzzer:

- `pyusd_mint` — the PYUSD mint, 8 extensions including TokenMetadata.
- `confidential_account` — a confidential token account.

## `txSkeleton` — [TransactionSkeletonFuzz](../../java/software/sava/core/tx/TransactionSkeletonFuzz.java)

Real transactions whose header/offset/length agreement a from-scratch test
doesn't assemble, plus a real lookup-table account:

- `legacy` — a legacy transaction.
- `versioned_lut` — a versioned transaction using an address lookup table.
- `versioned_trunc` — a truncated versioned transaction.
- `alt_account` — a real address-lookup-table account.
