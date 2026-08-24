# Live transaction v1 (SIMD-0385) check

`sava-rpc/src/test/java/software/sava/rpc/json/http/client/LiveV1ValidatorCheck.java` sends
sava-built v1 transactions to a real Agave validator and reads them back over JSON-RPC. It is
the only place the v1 builder, the in-place config setters, the v1 arms of the response
parsers and the `-32015` / `-32002` error mappings meet a validator; the offline oracles for
the wire format itself are the Rust and kit fixtures under `sava-core/src/test/solana/`. It is
not part of the default test suite or CI: it is inert unless `SAVA_V1_LIVE=true`, and it skips
itself (JUnit assumption, reported as skipped, not failed) when the cluster it reaches has not
activated `enable_tx_v1`. The scripts here start and stop a suitable validator.

## Why 4.2.1 or later

v1 is behind the `enable_tx_v1` feature gate (`txv1aq4pp281K9um3tnPgkfX8UqtFT6wcVW3hNezGLL`,
`agave:feature-set/src/lib.rs`), which the 4.2 line is the first to carry — a 3.x
`solana-test-validator` has no such gate and refuses every v1 transaction with
`UnsupportedVersion`. `solana-test-validator` activates every feature it knows at genesis, so on
a 4.2+ binary the gate is live from slot 0 with no feature-gate wrangling. The check and the
observations recorded in `AGAVE_SYNC.md` ("Observed on Agave 4.2.1") were verified against
Anza 4.2.1, the same release the reference examples pin, so `start.sh` refuses anything below
4.2. Release binaries: https://github.com/anza-xyz/agave/releases (the `solana-release`
tarball carries `bin/solana-test-validator` and `bin/solana`; no install is required, pass the
binary's path in `SOLANA_TEST_VALIDATOR`).

The gate is not active on mainnet, devnet or testnet as of 2026-08-24 — the feature account does
not exist on any of them — so pointing the check at a public cluster skips it. That is the
honest outcome, not a failure.

## The loop

```sh
cd sava-rpc/src/test/solana/v1-live
SOLANA_TEST_VALIDATOR=/path/to/solana-release/bin/solana-test-validator ./start.sh
solana -u http://127.0.0.1:8899 feature status txv1aq4pp281K9um3tnPgkfX8UqtFT6wcVW3hNezGLL
(cd ../../../../.. && SAVA_V1_LIVE=true ./gradlew :sava-rpc:test --tests '*LiveV1ValidatorCheck')
./stop.sh
```

The feature status line should read `active since epoch 0` with activation slot `0`. The check
performs the same test itself before sending anything — it decodes the feature account, owned by
`Feature111111111111111111111111111111111111`, as 9 bytes: a `1` tag then the activation slot
as u64 LE — so the CLI step is for the human, not the build.

`start.sh` takes the validator binary from `SOLANA_TEST_VALIDATOR` (default:
`solana-test-validator` on `PATH`), keeps the ledger in `LEDGER_DIR` (default `./test-ledger`
here, git-ignored), resets it on every start so the genesis is fresh, serves RPC on `RPC_PORT`
(default 8899, which is also the check's default), writes `validator.pid` and `validator.log`
next to itself, and returns once `solana cluster-version` answers. When another validator holds
the default ports, pass alternates through `VALIDATOR_ARGS`, for example
`VALIDATOR_ARGS="--faucet-port 9901 --gossip-port 8101 --dynamic-port-range 8102-8140"`; a
different `RPC_PORT` must also be given to the check as `SAVA_V1_RPC_URL=http://127.0.0.1:<port>`.
`start.sh` refuses to start when something already answers on the RPC port — a second `--reset`
would delete the ledger the running validator has open while the readiness check passed against
the wrong process. `stop.sh` kills only the pid `start.sh` recorded, after confirming that pid
is still a `solana-test-validator`.

The model for both scripts is `scripts/validator.sh` in
https://github.com/solana-foundation/transaction-v1-examples (the Solana Foundation's SIMD-0385
examples), minus the geyser plugin and the Token-2022 override sava does not need.

## What the check covers

Every case funds a fresh payer by airdrop, so the validator must serve the faucet. Keys are
fresh per run on purpose: a fixed payer re-sending the same transfer inside one blockhash window
would be refused as `AlreadyProcessed`, which says nothing about v1.

| case | pins |
|---|---|
| `basicV1Transfer` | version 1 on the wire and in `getTransaction`; landed bytes equal sent bytes; fee is exactly `5000 base + 5000 priority`; every config value reads back through `TransactionSkeleton`; full `getBlock` (sent at ceiling 1) carries the same bytes |
| `versionCeiling` | raw HTTP: `getTransaction` and full `getBlock` with the ceiling omitted or `0` fail the whole response with `-32015`, mapped to `RpcCustomError.UnsupportedTransactionVersion`, and the message names ceiling 1; ceiling 1 serves it; `transactionDetails: signatures` is served at ceiling 0 |
| `largeV1Transaction` | a 3236-byte v1 transaction (system transfer with 3000 trailing data bytes, still 150 CU) is accepted past the 1232-byte legacy packet limit and lands byte-exact; simulation and execution agree on units |
| `simulateThenTighten` | builder defaults reserve both limit slots at the maxima (`1_400_000`, 64 MiB); `setComputeUnitLimit` / `setAccountDataSizeLimit` overwrite in place; the landed transaction carries exactly the measured values and executes with them |
| `clearedComputeUnitLimitIsRejected` | `computeUnitLimit(0)` clears the mask bit, the in-place setter then throws, simulation reports `InstructionError(0, ComputationalBudgetExceeded)` with 0 units, and preflight refuses with `-32002` mapped to `SendTransactionPreflightFailure` carrying that simulation |
| `twoSigners` | two required signatures land byte-exact; fee is `2 × 5000 + 5000` |
| `priorityFeeIsAbsoluteLamports` | priority fee 0 pays exactly the base fee; 5000 pays exactly 5000 more |

Pre-activation behaviour (simulate and send while the gate is off) is deliberately not covered:
it cannot be observed on the validator these scripts start, and the raw bodies captured once by
hand are recorded in `AGAVE_SYNC.md`.
