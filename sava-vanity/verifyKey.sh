#!/usr/bin/env bash

# Verify a saved encrypted-properties key without exposing its private material.
# Disable tracing even when the caller starts this script with bash -x.
set +x
set -euo pipefail
unset CDPATH

usage() {
  cat <<'HELP'
Usage: verifyKey.sh --keyFile=<path> --expectedPubKey=<full-base58-public-key> [options]

Verify an encrypted properties key file saved by sava-vanity.
  --passwordEnv=NAME      Read the password from an exported environment variable.
                         Otherwise use SAVA_VANITY_ENCRYPT_PASSWORD if nonempty, or
                         prompt once through the Java console.
  --docker               Run in the sava-vanity:local Docker image.
  --dockerImage=<image>   Run in the named Docker image (--docker=<image> also works).
  --dockerUser=UID:GID    Use these numeric Docker user and group ids.
  --build[=true|false]    Rebuild the runtime; missing runtimes always build.
  --jvm=<args>           Override the default -server -Xms64m -Xmx512m options.
                         Only -server, -Xms<size> and -Xmx<size> are accepted.
  --jvmArgs=<args>       Alias for --jvm; options are whitespace-separated,
                         with no shell expansion or quote interpretation.
  --maxHeap=<size>       Set the maximum heap, overriding any -Xmx option above.
                         Sizes are positive integers, optionally suffixed k, m or g.
                         The effective -Xms must not exceed the effective -Xmx.
  --legacyDockerPassword Recover a password decoded by an old ASCII Docker image.
                         Explicitly replaces each non-ASCII UTF-8 byte; no fallback.
  --help                Show this help.
HELP
}

fail() {
  printf '%s\n' "$1" >&2
  exit 2
}

keyFile=
expectedPubKey=
dockerImage=
dockerUser=
build=false
passwordEnv=
maxHeap=
legacyDockerPassword=false
jvmArgs=(-server -Xms64m -Xmx512m)

# Parse decimal bytes without letting Bash overflow or interpret leading zeroes
# as octal. heapBytes is the result; each multiplication is checked beforehand.
parseSize() {
  [[ "$1" =~ ^[0-9]+[kKmMgG]?$ ]] || return 1
  local digits="$1" multiplier=1 sizeNumber=0 digit index
  case "$digits" in
    *[kK]) multiplier=1024; digits="${digits%?}" ;;
    *[mM]) multiplier=1048576; digits="${digits%?}" ;;
    *[gG]) multiplier=1073741824; digits="${digits%?}" ;;
  esac
  for ((index = 0; index < ${#digits}; index++)); do
    digit="${digits:index:1}"
    ((sizeNumber <= (9223372036854775807 - digit) / 10)) || return 1
    sizeNumber=$((sizeNumber * 10 + digit))
  done
  ((sizeNumber > 0 && sizeNumber <= 9223372036854775807 / multiplier)) || return 1
  heapBytes=$((sizeNumber * multiplier))
}

for arg in "$@"; do
  case "$arg" in
    --help) usage; exit 0 ;;
    --keyFile=*) keyFile="${arg#*=}" ;;
    --expectedPubKey=*) expectedPubKey="${arg#*=}" ;;
    --docker) dockerImage=sava-vanity:local ;;
    --docker=* | --dockerImage=*)
      dockerImage="${arg#*=}"
      [[ -n "$dockerImage" ]] || fail 'Docker image must not be empty.'
      ;;
    --dockerUser=*)
      dockerUser="${arg#*=}"
      [[ "$dockerUser" =~ ^[0-9]+:[0-9]+$ ]] || fail '--dockerUser requires numeric UID:GID.'
      ;;
    --legacyDockerPassword) legacyDockerPassword=true ;;
    --maxHeap=*)
      maxHeap="${arg#*=}"
      parseSize "$maxHeap" || fail '--maxHeap requires a positive integer size within signed 64-bit bytes, optionally suffixed k, m or g.'
      ;;
    --build) build=true ;;
    --build=*)
      case "${arg#*=}" in
        true | 1) build=true ;;
        false | 0) build=false ;;
        *) fail '--build requires true, false, 1 or 0.' ;;
      esac
      ;;
    --passwordEnv=*)
      passwordEnv="${arg#*=}"
      [[ "$passwordEnv" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] || fail '--passwordEnv requires an environment variable name.'
      ;;
    --jvm=* | --jvmArgs=*)
      # Split options into an array without eval, globbing or shell execution.
      jvmText="${arg#*=}"
      jvmText="${jvmText//$'\n'/ }"
      [[ "$jvmText" == *[!$' \t\r']* ]] || fail '--jvm requires at least one supported option.'
      IFS=$' \t\r' read -r -a jvmArgs <<< "$jvmText"
      for option in "${jvmArgs[@]}"; do
        case "$option" in
          -server) ;;
          -Xms* | -Xmx*)
            parseSize "${option:4}" || fail 'JVM heap options require a positive integer size within signed 64-bit bytes, optionally suffixed k, m or g.'
            ;;
          *) fail 'Unsupported JVM option; only -server, -Xms<size> and -Xmx<size> are allowed.' ;;
        esac
      done
      ;;
    *) fail 'Unsupported argument; use --help for the supported flags.' ;;
  esac
done

[[ -n "$keyFile" ]] || fail '--keyFile=<path> is required.'
[[ -n "$expectedPubKey" ]] || fail '--expectedPubKey=<full-base58-public-key> is required.'
if [[ ! -f "$keyFile" ]]; then
  printf '%s\n' 'The key file must exist and be a regular file.' >&2
  exit 1
fi
[[ -z "$dockerUser" || -n "$dockerImage" ]] || fail '--dockerUser requires Docker verification.'
[[ -z "$maxHeap" ]] || jvmArgs+=("-Xmx$maxHeap")
# The JVM uses the final occurrence of each heap flag. --maxHeap is appended
# above so it remains the maximum-heap override regardless of CLI flag order.
initialHeap=
maximumHeap=
for option in "${jvmArgs[@]}"; do
  case "$option" in
    -Xms*) parseSize "${option:4}"; initialHeap="$heapBytes" ;;
    -Xmx*) parseSize "${option:4}"; maximumHeap="$heapBytes" ;;
  esac
done
if [[ -n "$initialHeap" && -n "$maximumHeap" ]]; then
  ((initialHeap <= maximumHeap)) || fail 'The effective -Xms heap must not exceed the effective -Xmx heap; adjust --jvm or --maxHeap.'
fi

# Resolve the directory physically so symlink/.. segments name the same file
# locally and in Docker. Keep the basename raw, including trailing newlines.
[[ "$keyFile" == /* ]] || keyFile="$PWD/$keyFile"
keyDir="$(cd -P -- "${keyFile%/*}/" && pwd -P && printf '.')"
keyDir="${keyDir%.}"
keyDir="${keyDir%$'\n'}"
keyFile="$keyDir/${keyFile##*/}"
# Resolve launcher symlinks separately, preserving newlines with a sentinel.
scriptPath="${BASH_SOURCE[0]}"
[[ "$scriptPath" == /* ]] || scriptPath="$PWD/$scriptPath"
while [[ -L "$scriptPath" ]]; do
  linkTarget="$(readlink -n "$scriptPath" && printf '.')"
  linkTarget="${linkTarget%.}"
  if [[ "$linkTarget" == /* ]]; then
    scriptPath="$linkTarget"
  else
    scriptPath="${scriptPath%/*}/$linkTarget"
  fi
done
scriptDir="$(cd -P -- "${scriptPath%/*}/" && pwd -P && printf '.')"
scriptDir="${scriptDir%.}"
scriptDir="${scriptDir%$'\n'}"
repoRoot="$(cd -P -- "$scriptDir/.." && pwd -P && printf '.')"
repoRoot="${repoRoot%.}"
repoRoot="${repoRoot%$'\n'}"

if [[ -n "$passwordEnv" ]]; then
  printenv "$passwordEnv" >/dev/null || fail 'The password environment variable must be exported and set.'
  [[ -n "${!passwordEnv}" ]] || fail 'The password environment variable must not be empty.'
  export SAVA_VANITY_ENCRYPT_PASSWORD="${!passwordEnv}"
elif [[ -z "${SAVA_VANITY_ENCRYPT_PASSWORD:-}" ]]; then
  unset SAVA_VANITY_ENCRYPT_PASSWORD
fi

if [[ -n "$dockerImage" ]]; then
  # Docker's --mount CSV parser treats commas, quotes and newlines specially.
  # Reject ambiguous paths before building or running rather than mounting a
  # different source by accident.
  [[ "$keyFile" != *,* && "$keyFile" != *\"* && "$keyFile" != *$'\n'* ]] || fail 'Docker verification does not support commas, double quotes or newlines in the key file path; use local verification.'
  if [[ -z "${SAVA_VANITY_ENCRYPT_PASSWORD:-}" && ( ! -t 0 || ! -t 1 ) ]]; then
    fail 'Docker password prompting requires an interactive terminal; use --passwordEnv=ENV_VAR_NAME.'
  fi
  if [[ "$build" == true ]] || ! docker image inspect "$dockerImage" >/dev/null 2>&1; then
    docker build \
      --build-arg PROJECT=sava-vanity \
      --secret id=ORG_GRADLE_PROJECT_savaGithubPackagesUsername,env=ORG_GRADLE_PROJECT_savaGithubPackagesUsername \
      --secret id=ORG_GRADLE_PROJECT_savaGithubPackagesPassword,env=ORG_GRADLE_PROJECT_savaGithubPackagesPassword \
      -t "$dockerImage" -f "$scriptDir/Dockerfile" "$repoRoot"
  fi
  # Remove JVM-option defaults in the image as well as the host environment.
  # These variables can make Java exit successfully without running VerifyKey.
  dockerArgs=(run --network none --rm
    -e JDK_JAVA_OPTIONS -e JAVA_TOOL_OPTIONS -e _JAVA_OPTIONS)
  [[ -z "$dockerUser" ]] || dockerArgs+=(--user "$dockerUser")
  if [[ "${SAVA_VANITY_ENCRYPT_PASSWORD+x}" == x ]]; then
    # Docker reads the value from the environment; the secret is never an argument.
    dockerArgs+=(-e SAVA_VANITY_ENCRYPT_PASSWORD)
  else
    dockerArgs+=(-it)
  fi
  dockerArgs+=(--mount "type=bind,source=$keyFile,target=/key.properties,readonly" "$dockerImage")
  verifyArgs=(/key.properties "$expectedPubKey")
  [[ "$legacyDockerPassword" == false ]] || verifyArgs+=(--legacy-docker-password)
  unset JDK_JAVA_OPTIONS JAVA_TOOL_OPTIONS _JAVA_OPTIONS
  exec docker "${dockerArgs[@]}" "${jvmArgs[@]}" \
    -m software.sava.vanity/software.sava.vanity.VerifyKey "${verifyArgs[@]}"
else
  hostCharset="$(locale charmap)" || fail 'Unable to determine the local character encoding; select an installed UTF-8 locale for encrypted-key verification.'
  [[ "$hostCharset" =~ ^[uU][tT][fF]-?8$ ]] || fail 'Local encrypted-key verification requires a UTF-8 locale; select an installed UTF-8 locale using LANG or LC_ALL.'
  javaExe="$scriptDir/build/images/sava-vanity/bin/java"
  if [[ "$build" == true || ! -x "$javaExe" ]]; then
    (cd -- "$repoRoot" && ./gradlew :sava-vanity:image)
  fi
  verifyArgs=("$keyFile" "$expectedPubKey")
  [[ "$legacyDockerPassword" == false ]] || verifyArgs+=(--legacy-docker-password)
  # Keep build-time tuning above, but verification accepts only the CLI whitelist.
  unset JDK_JAVA_OPTIONS JAVA_TOOL_OPTIONS _JAVA_OPTIONS
  exec "$javaExe" "${jvmArgs[@]}" \
    -m software.sava.vanity/software.sava.vanity.VerifyKey "${verifyArgs[@]}"
fi
