# JMH Benchmarks

Benchmarks for `sava-core` hot paths. Currently: `Base58Bench`, which prices
the Base58 decode rewrite (limb-based multiply-add, five base-58 digits per
pass, plus fixed-size decode-into overloads) against the per-output-byte
`divMod` implementation it replaced, copied verbatim into the benchmark as the
`*_old` rows.

## Running

This directory is a standalone Gradle build that includes the library build
from `..`, so benchmarks always run against the local sources.

```sh
cd jmh
../gradlew jmh                          # everything, quick-look defaults (1 fork)
../gradlew jmh -PjmhFork=3              # decision-grade
../gradlew jmh -PjmhIncludes=chars      # name-regex subset
```

Configuration comes from the shared `software.sava.build.feature.jmh`
convention plugin (resolved from the sibling `../sava-build` checkout); every
default is overridable per invocation (`-PjmhFork`, `-PjmhIncludes`,
`-PjmhWarmupIterations`, `-PjmhWarmup`, `-PjmhIterations`,
`-PjmhTimeOnIteration`, `-PjmhFailOnError`, `-PjmhJvmArgsAppend`). The forked
benchmark JVMs run the long-running-service flag set (generational ZGC,
compact object headers, pinned pre-touched heap, `-XX:+PerfDisableSharedMem`).
Each run's raw output is archived timestamped under `jmh-results/` and
`build/results/jmh/results.txt` is re-rendered as the merge of all archived
runs (newest row wins per benchmark).

**Compare with at least `-PjmhFork=3`, in isolation.** Single-fork scores
swing 10–20% on JIT inlining luck alone; treat any row with an error above
~10% of its score as contaminated and re-run it alone. Every benchmark's
`@Setup` cross-checks that all variants produce identical output, and the
default `failOnError` turns any disagreement into a hard failure.

## Base58Bench

Two workloads, the only lengths Solana decodes in practice: 32 bytes
(public keys and block hashes, ~44 chars — the shape behind
`PublicKey.fromBase58Encoded` and every account key in a parsed RPC response)
and 64 bytes (transaction signatures, ~88 chars). 1024 uniform random inputs
per length; scores are ns per decode.

- `string_old` / `chars_old` — the replaced implementation: codepoint
  iteration, exception-driven charset validation, one `divMod` pass over the
  remaining digits per output byte (O(n²) in digit count).
- `string_new` / `chars_new` — `Base58.decode` as shipped.
- `string_into` / `chars_into` — the fixed-size `decode(input, out)`
  overloads, which skip the final right-size-and-copy allocation.
  `string_into` vs `string_old` is the `PublicKey.fromBase58Encoded`
  before/after; `chars_into` is the RPC-parse shape (`applyChars` feeding the
  parser's buffer straight to `decode(char[], from, len, out)`).
- `bytes_new` / `bytes_into` — the `byte[]` ASCII overloads, the shape a
  json-iterator byte-span value hook would deliver.

### Results

Measured 2026-07-14 on an Apple Silicon macOS box, `-PjmhFork=3` (24 samples
per row), JDK 25, default service flag set. Treat the ratios as the signal,
not the absolute numbers.

| Workload | old ns/op | new ns/op | into ns/op | Speedup |
|---|---|---|---|---|
| 32-byte keys, String | 1610.9 ± 3.3 | 83.7 ± 1.2 | 78.2 ± 0.4 | **19.3×, 20.6× into** |
| 32-byte keys, char[] | 1603.8 ± 12.9 | 83.8 ± 2.9 | 70.5 ± 0.7 | **19.1×, 22.8× into** |
| 64-byte signatures, String | 7803.2 ± 9.8 | 183.5 ± 0.3 | 185.0 ± 1.2 | **42.5×** |
| 64-byte signatures, char[] | 7777.0 ± 14.0 | 172.6 ± 1.7 | 170.8 ± 1.7 | **45.1×** |

The gap widens with length because the old conversion is quadratic in digit
count (each output byte re-scans the remaining digits) while the limb loop
does a fifth of the passes over quarter-length data. The `into` overloads add
a further 7–16% at 32 bytes — one less allocation and copy per decode, the
shape `PublicKey.fromBase58Encoded` and the RPC key parse paths now use — and
are a wash within error at 64 bytes, where the conversion itself dominates.

### byte[] vs char[] input

A dedicated same-session control run (`-PjmhFork=3`,
`-PjmhIncludes=bytes_new,bytes_into,chars_new,chars_into`, 24 samples per
row) prices the `byte[]` overloads against the char path:

| Workload | chars ns/op | bytes ns/op |
|---|---|---|
| 32-byte keys, into | 70.1 ± 0.9 | 70.1 ± 0.8 |
| 64-byte signatures, into | 167.1 ± 0.6 | 168.4 ± 1.3 |
| 32-byte keys, alloc | 82.6 ± 3.8 | 85.0 ± 2.7 |
| 64-byte signatures, alloc | 169.8 ± 0.3 | 169.6 ± 0.5 |

A dead tie on every pairing: the decode itself is indifferent to input width
at these lengths. The value of a json-iterator byte-span hook is therefore
entirely on the parser side — skipping the byte-to-char widening copy
`BytesJsonIterator` performs before `applyChars` — not in the decoder. Any
end-to-end measurement of that hook belongs in json-iterator's jmh suite,
where both sides of the widening live.
