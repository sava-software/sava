# Helius Balance Reporter

> **Docker is required to compile and run this project.**

## Setup

```bash
git clone https://github.com/sava-software/sava.git
cd sava
git checkout helius
```

## Usage

### Run Balance Report

```bash
./run_balance_report.sh --apiKey=<HELIUS_API_KEY> --account=<SOLANA_ADDRESS> [--build=true] [--firstLastOnly=true] [--trackWrappedSol=true]
```

**Parameters:**

| Parameter           | Required | Description                                                    |
|---------------------|----------|----------------------------------------------------------------|
| `--apiKey`          | Yes      | Your Helius API key                                            |
| `--account`         | Yes      | The on-chain Solana address to generate a balance report for   |
| `--build`           | No       | `true` to force rebuild the Docker image (default: `false`)    |
| `--firstLastOnly`   | No       | `true` to only fetch first and last balance (default: `false`) |
| `--trackWrappedSol` | No       | `true` to include wrapped SOL balances (default: `false`)      |

If the Docker image `helius-demo:latest` does not exist, it will be built automatically.

**Examples:**

```bash
# Run with auto-build (only builds if image does not exist)
./run_balance_report.sh --apiKey=<API_KEY> --account=<ACCOUNT>

# Force rebuild the image before running.
./run_balance_report.sh --apiKey=<API_KEY> --account=<ACCOUNT> --build=true 

# Passively track wrapped SOL balance.
./run_balance_report.sh --apiKey=<API_KEY> --account=<ACCOUNT> --trackWrappedSol=true 

# Only report the delta between the first and last balance.
./run_balance_report.sh --apiKey=<API_KEY> --account=<ACCOUNT> --firstLastOnly=true
```

The balance report CSV will be written to the current working directory.

## Assumptions

* If the account has multiple transactions in its first block of existence, only the first transaction in
  the block is considered towards its initial balance.
* Native/Rent lamports in the wrapped SOL token account are NOT included in the reported balance. The assumption is
  that sync native is always called when wrapping SOL.
* Probably more assumptions.
