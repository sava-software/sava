# Per-suite hardening notes

Scope decisions and deliberate exceptions for the mutation suites. The process
contract is in `AGENTS.md`; the policy behind it is sava-build's `HARDENING.md`.
This file is for "why is this suite shaped the way it is" — read it when
touching a suite's registration, not on every task.

Suites target by package wildcard with explicit exclusions, never an allowlist,
so a new class in a covered package is mutated by default. Packages without a
suite are deliberate scope decisions rather than omissions.

## sava-core

| Suite | Target | Notes |
| --- | --- | --- |
| `pitestBorsh` | `core.borsh.*` | Baseline empty — keep it that way. |
| `pitestEd25519` | `core.crypto.ed25519.*` | |
| `pitestEncoding` | `core.encoding.*` | |
| `pitestTx` | `core.tx.*`, `core.accounts.lookup.*` | Carries the largest triaged population. |
| `pitestToken2022` | `core.accounts.token.*` | |
| `pitestMeta` | `core.accounts.meta.*` | |
| `pitestDecimal` | `core.util.*` | |
| `pitestCrypto` | `core.crypto.*` | Excludes the `ed25519` subpackage, which has its own suite — the `crypto.*` wildcard spans dots. |
| `pitestVanity` | `core.accounts.vanity.Subsequence*` | **Deliberate allowlist.** See below. |

### `pitestVanity` — the one allowlist

The mask workers search in an unbounded loop, so every mutant that breaks the
match predicate runs to the PIT timeout instead of failing fast. PIT scores
`TIMED_OUT` as killed, so the ratchet stays correct, but a whole-package suite
would cost a timeout window per such mutant.

The mask logic — where a wrong answer is silent rather than a hang — is what the
suite targets. The workers stay covered by `MaskWorkerTests` without being
mutated. The reasoning is repeated at the registration site in
`sava-core/build.gradle.kts`.

Closing this properly would mean giving the workers a bounded-attempts seam so a
broken predicate fails fast; `maxSearches` already exists for exactly that and
could let the suite widen to the package.

### `pitestDecimal` — plain `STRONGER` on purpose

`EXPERIMENTAL_BIG_DECIMAL` / `EXPERIMENTAL_BIG_INTEGER` were tried here and
generate **nothing**. They only rewrite the `(BigDecimal)BigDecimal` arithmetic
methods — add, subtract, multiply, divide, remainder, min, max, abs, negate,
plus — and this package's only arithmetic is `movePointLeft`/`movePointRight`,
which take an `int`. No package in the repo currently has a call site those
mutators can reach, so do not re-enable them on a hunch.

## sava-rpc

| Suite | Target | Notes |
| --- | --- | --- |
| `pitestResponses` | `json.http.response.*` | Debt free — keep it that way. |
| `pitestClient` | `json.http.client.*` | Carries real coverage debt; see below. |

### `pitestClient` — the debt is deliberate and documented

Registered over the whole package and worked from 54% to 89% rather than being
narrowed to fit. What remains is 27 survivors (triaged for equivalence) and 29
`NO_COVERAGE` — almost all the `sendPostRequestNoWrap` / `sendGetRequestNoWrap` /
`sendGetRequest` transport paths, which `RpcRequestTests` never enters because it
routes everything through `sendPostRequest`.

Clearing those needs new harness scaffolding — a local server exercising the GET
and no-wrap routes — not another `registerRequest` line. Reasons are grouped by
family in `sava-rpc/config/pitest/README.md`.

Exclusions must name `*Check` and `Stub*` as well as `*Test*`: test sources share
this package and shared fakes are named for their role. The verify task warns if
this regresses.

## sava-vanity

Has a test source set (`EntrypointTests` — system-property parsing, key-path
resolution) but no PIT suite. It is an application module whose `module-info`
exports nothing, so its helpers are package-private by choice rather than by
accident.

## Per-package hardening history

The per-surface notes — what each package's fuzz corpus covers, which invariants
are asserted where, and the reasoning behind long-standing accepted mutants —
live in the hardening sections of `AGAVE_SYNC.md`, alongside the canonical
sources they mirror.
