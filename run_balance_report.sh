#!/usr/bin/env bash

set -euo pipefail

BUILD="false"
FIRST_LAST_ONLY="false"
TRACK_WRAPPED_SOL="false"
API_KEY=""
ACCOUNT=""

for arg in "$@"; do
  case "$arg" in
    --build=*)          BUILD="${arg#*=}" ;;
    --firstLastOnly=*)  FIRST_LAST_ONLY="${arg#*=}" ;;
    --trackWrappedSol=*) TRACK_WRAPPED_SOL="${arg#*=}" ;;
    --apiKey=*)         API_KEY="${arg#*=}" ;;
    --account=*)        ACCOUNT="${arg#*=}" ;;
    *)
      echo "Unknown argument: $arg"
      exit 1
      ;;
  esac
done

if [ -z "$API_KEY" ] || [ -z "$ACCOUNT" ]; then
  echo "Usage: $0 --apiKey=<HELIUS_API_KEY> --account=<SOLANA_ADDRESS> [--build=true] [--firstLastOnly=true] [--trackWrappedSol=true]"
  echo ""
  echo "  --apiKey          (required) Your Helius API key"
  echo "  --account         (required) The on-chain Solana address to generate a balance report for"
  echo "  --build           true|false (default: false) - force rebuild the Docker image"
  echo "  --firstLastOnly   true|false (default: false) - only fetch first and last balance"
  echo "  --trackWrappedSol true|false (default: false) - include wrapped SOL balances"
  echo ""
  echo "Example: $0 --apiKey=66bfd293-d327-46ae-8731-935ddc6d4951 --account=HkcE9sqQAnjJtECiFsqGMNmUho3ptXkapUPAqgZQbBSY"
  echo "Example: $0 --build=true --apiKey=66bfd293-d327-46ae-8731-935ddc6d4951 --account=HkcE9sqQAnjJtECiFsqGMNmUho3ptXkapUPAqgZQbBSY"
  echo "Example: $0 --firstLastOnly=true --trackWrappedSol=true --apiKey=66bfd293-d327-46ae-8731-935ddc6d4951 --account=HkcE9sqQAnjJtECiFsqGMNmUho3ptXkapUPAqgZQbBSY"
  exit 1
fi

IMAGE="helius-demo:latest"

if [ "$BUILD" = "true" ] || ! docker image inspect "$IMAGE" &>/dev/null; then
  echo "Building image $IMAGE..."
  docker build -f helius.alpine.amazon.Dockerfile -t "$IMAGE" .
fi

docker run --rm -v "$(pwd):/output" "$IMAGE" \
  -DoutputDir=/output \
  -DfirstLastOnly="$FIRST_LAST_ONLY" \
  -DtrackWrappedSol="$TRACK_WRAPPED_SOL" \
  -m software.sava.helius/software.sava.helius.demo.Entrypoint \
  "$API_KEY" "$ACCOUNT"
