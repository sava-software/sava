# Local transaction v1 (SIMD-0385) smoke check

`LiveV1ValidatorCheck.basicV1Transfer` exercises one end-to-end path against a local Agave
validator: build a transaction larger than the legacy packet limit with priority fee and heap
config, simulate it, tighten compute-unit and account-data limits in place, sign and send it,
then check the confirmed transaction's bytes, config and fee through `getTransaction` and
`getBlock`.

It runs only with `SAVA_V1_LIVE=true`; ordinary tests and CI use the committed Rust, Kit and
Agave fixtures. This optional smoke check is useful after transaction or validator changes.
The broader initial local-validator and devnet observations remain in `AGAVE_SYNC.md`.

## Run manually

Use a local `solana-test-validator` 4.2.1 or later with its default feature activation and faucet.
The original validation used Agave 4.2.1, whose test-validator activates `enable_tx_v1` at genesis.

From the repository root, start the validator in the foreground with a fresh disposable ledger:

```sh
mkdir -p sava-rpc/build
V1_LEDGER=$(mktemp -d "$PWD/sava-rpc/build/v1-smoke.XXXXXX")
NO_DNA=1 solana-test-validator --ledger "$V1_LEDGER" --rpc-port 8899
```

Once it is serving RPC, run the smoke check from the repository root in another terminal:

```sh
SAVA_V1_LIVE=true ./gradlew :sava-rpc:test \
  --tests '*LiveV1ValidatorCheck.basicV1Transfer' --rerun
```

Stop the validator with **Ctrl-C** in its terminal. Its disposable ledger stays under
`sava-rpc/build/`.

The check defaults to `http://127.0.0.1:8899`. For another local RPC port, set
`SAVA_V1_RPC_URL=http://127.0.0.1:<port>` and start the validator on that port. The check accepts
only loopback endpoints and funds a fresh in-memory signer from the local faucet.
