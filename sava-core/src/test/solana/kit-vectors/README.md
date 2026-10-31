# Solana Kit test tooling

This directory keeps a small, locked `@solana/kit` toolchain for future conformance
fixtures. It is test support only: Gradle and CI do not run Node or pnpm, and the committed
fixtures are consumed by Java tests without network access. There is no generator script
here today.

When a future fixture needs a TypeScript generator, run `pnpm install --frozen-lockfile`,
then add the script with its own documented purpose and run it from this directory with
`pnpm exec tsx <script.ts>`. Keep its outputs
committed and have Java tests consume those outputs. Do not add routine dependency upgrades;
update the pinned toolchain only when a concrete fixture requires it, and review the resulting
lockfile change.

## Existing v1 fixture

The committed fixture is
[`../../resources/tx/kit-v1-message-vectors.tsv`](../../resources/tx/kit-v1-message-vectors.tsv),
consumed by `software.sava.core.tx.KitV1VectorTests`. It was generated with `@solana/kit`
8.0.0 and records its original dependency, generator, and runtime provenance in the TSV. The
original generator has been removed; the fixture is intentionally frozen and is not expected
to be regenerated as part of routine work. The Java tests pin the complete TSV's SHA-256 and
historical metadata, and exercise Sava's build, parse, and signing behavior against its vectors.

Nine fee-payer-signed vectors cover config masks and defaults, absent and explicit-zero
configurations, one and two signers, long instruction data, and same-role account ordering.
The deliberate exceptions are `e_explicit_zeros`, a Kit message Sava's builder cannot emit,
and `i_two_readonly_programs`, where account order may differ while the transaction remains
equivalent. The original fixture documentation and details are retained in
[`../../resources/tx/kit-v1-message-vectors.md`](../../resources/tx/kit-v1-message-vectors.md).
