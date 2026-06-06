#!/usr/bin/env bash

set -e

simpleProjectName="sava-vanity"
readonly simpleProjectName
moduleName="software.sava.vanity"
readonly moduleName
mainClass="software.sava.vanity.Entrypoint"
readonly mainClass

jvmArgs="-server -Xms64M -Xmx128M"

dockerImage=
build=

prefix=
pCaseSensitive=
p1337Numbers=
p1337Letters=

suffix=
sCaseSensitive=
s1337Numbers=
s1337Letters=

numThreads=
numKeys=
keyFormat="base64KeyPair"
keyFileFormat=
checkFound=
logDelay=
outDir=
sigVerify=
encrypt=
encryptPassword=

screen=0;

for arg in "$@"
do
  if [[ "$arg" =~ ^--.* ]]; then
    key="${arg%%=*}"
    key="${key##*--}"
    val="${arg#*=}"

    case "$key" in
      jvm | jvmArgs) jvmArgs="$val";;
      d | docker | dockerImage) dockerImage="$val";;
      b | build)
        if [[ "$arg" != *=* ]]; then
          build="true"
        else
          case "$val" in
            "" | 1 | true) build="true" ;;
            0 | false) build="false" ;;
            *)
              printf "'%sbuild=[0|1]' or [true|false] not '%s'.\n" "--" "$arg";
              exit 2;
            ;;
          esac
        fi
        ;;
      screen)
        case "$val" in
          1|*screen) screen=1 ;;
          0) screen=0 ;;
          *)
            printf "'%sscreen=[0|1]' or '%sscreen' not '%s'.\n" "--" "--" "$arg";
            exit 2;
          ;;
        esac
        ;;

      cf | checkFound) checkFound="$val";;
      ld | logDelay) logDelay="$val";;
      nt | numThreads) numThreads="$val";;
      nk | numKeys) numKeys="$val";;
      kf | keyFormat) keyFormat="$val";;
      kff | keyFileFormat)
        case "$val" in
          json | properties) keyFileFormat="$val" ;;
          *)
            printf "'%skeyFileFormat=[json|properties]' not '%s'.\n" "--" "$arg";
            exit 2;
          ;;
        esac
        ;;
      o | outDir) outDir="$val";;

      p | prefix) prefix="$val";;
      pc | pCaseSensitive)
        case "$val" in
          1 | true) pCaseSensitive="true" ;;
          0 | false) pCaseSensitive="false" ;;
          *)
            printf "'%spCaseSensitive=[0|1]' or [true|false] not '%s'.\n" "--" "$arg";
            exit 2;
          ;;
        esac
        ;;
      pn | p1337Numbers)
        case "$val" in
          1 | true) p1337Numbers="true" ;;
          0 | false) p1337Numbers="false" ;;
          *)
            printf "'%sp1337Numbers=[0|1]' or [true|false] not '%s'.\n" "--" "$arg";
            exit 2;
          ;;
        esac
        ;;
      pl | p1337Letters)
        case "$val" in
          1 | true) p1337Letters="true" ;;
          0 | false) p1337Letters="false" ;;
          *)
            printf "'%sp1337Letters=[0|1]' or [true|false] not '%s'.\n" "--" "$arg";
            exit 2;
          ;;
        esac
        ;;

      s | suffix) suffix="$val";;
      sc | sCaseSensitive)
        case "$val" in
          1 | true) sCaseSensitive="true" ;;
          0 | false) sCaseSensitive="false" ;;
          *)
            printf "'%ssCaseSensitive=[0|1]' or [true|false] not '%s'.\n" "--" "$arg";
            exit 2;
          ;;
        esac
        ;;
      sn | s1337Numbers)
        case "$val" in
          1 | true) s1337Numbers="true" ;;
          0 | false) s1337Numbers="false" ;;
          *)
            printf "'%ss1337Numbers=[0|1]' or [true|false] not '%s'.\n" "--" "$arg";
            exit 2;
          ;;
        esac
        ;;
      sl | s1337Letters)
        case "$val" in
          1 | true) s1337Letters="true" ;;
          0 | false) s1337Letters="false" ;;
          *)
            printf "'%ss1337Letters=[0|1]' or [true|false] not '%s'.\n" "--" "$arg";
            exit 2;
          ;;
        esac
        ;;
      sv | sigVerify) sigVerify="$val";;
      e | encrypt)
        if [[ "$arg" != *=* ]]; then
          encrypt="true"
        else
          case "$val" in
            "" | 1 | true) encrypt="true" ;;
            0 | false) encrypt="false" ;;
            *)
              printf "'%sencrypt=[0|1]' or [true|false] not '%s'.\n" "--" "$arg";
              exit 2;
            ;;
          esac
        fi
        ;;
      pw | password)
        # Securely prompt for the encryption password (with confirmation) instead of
        # relying on the Java Console. The value is never echoed, stored in shell
        # history, placed on the command line, or passed as a JVM system property; it
        # is handed to the Java runtime only via an environment variable.
        encrypt="true"
        while true; do
          read -r -s -p "Enter encryption password: " encryptPassword
          printf "\n"
          read -r -s -p "Confirm encryption password: " confirmPassword
          printf "\n"
          if [[ -z "$encryptPassword" ]]; then
            printf "Password must not be empty.\n"
            continue
          fi
          if [[ "$encryptPassword" != "$confirmPassword" ]]; then
            printf "Passwords did not match, please try again.\n"
            continue
          fi
          break
        done
        unset confirmPassword
        ;;
      pe | passwordEnv)
        # Read the encryption password from an already-exported environment variable
        # named by "$val". This keeps the secret out of the process arguments and the
        # script, allowing fully non-interactive runs.
        encrypt="true"
        if [[ -z "$val" || "$arg" != *=* ]]; then
          printf "'%spasswordEnv=ENV_VAR_NAME' requires the name of an environment variable.\n" "--";
          exit 2;
        fi
        encryptPassword="${!val}"
        if [[ -z "$encryptPassword" ]]; then
          printf "Environment variable '%s' is empty or not set.\n" "$val";
          exit 2;
        fi
        ;;

      *)
          printf "Unsupported flag '%s' [key=%s] [val=%s].\n" "$arg" "$key" "$val";
          exit 1;
        ;;
    esac
  else
    printf "Unhandled argument '%s', all flags must begin with '%s'.\n" "$arg" "--";
    exit 1;
  fi
done

if [[ -n "$encrypt" ]]; then
  jvmArgs="$jvmArgs -D$moduleName.encrypt=$encrypt"
fi

jvmArgs="$jvmArgs -D$moduleName.sigVerify=$sigVerify -D$moduleName.outDir=$outDir -D$moduleName.numThreads=$numThreads -D$moduleName.numKeys=$numKeys -D$moduleName.keyFormat=$keyFormat -D$moduleName.keyFileFormat=$keyFileFormat -D$moduleName.checkFound=$checkFound -D$moduleName.logDelay=$logDelay -D$moduleName.prefix=$prefix -D$moduleName.pCaseSensitive=$pCaseSensitive -D$moduleName.p1337Numbers=$p1337Numbers -D$moduleName.p1337Letters=$p1337Letters -D$moduleName.suffix=$suffix -D$moduleName.sCaseSensitive=$sCaseSensitive -D$moduleName.s1337Numbers=$s1337Numbers -D$moduleName.s1337Letters=$s1337Letters -m $moduleName/$mainClass"
IFS=' ' read -r -a jvmArgsArray <<< "$jvmArgs"

# Export the encryption password through an environment variable so the Java runtime
# can read it without it ever appearing on the command line or as a JVM system
# property. This assignment is intentionally kept above 'set -x' so the secret is not
# leaked into the xtrace output.
if [[ -n "$encryptPassword" ]]; then
  export SAVA_VANITY_ENCRYPT_PASSWORD="$encryptPassword"
  unset encryptPassword
fi

set -x

if [[ -n "$dockerImage" ]]; then
  if [[ "$build" == "true" ]] || ! docker image inspect "$dockerImage" > /dev/null 2>&1; then
    if [[ "$build" == "true" ]]; then
      printf "Building Docker image '%s' as requested by the build flag.\n" "$dockerImage";
    else
      printf "Docker image '%s' not found locally, building it.\n" "$dockerImage";
    fi
    scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    repoRoot="$(cd "$scriptDir/.." && pwd)"
    docker build \
      --build-arg "PROJECT=$simpleProjectName" \
      --secret "id=ORG_GRADLE_PROJECT_savaGithubPackagesUsername,env=ORG_GRADLE_PROJECT_savaGithubPackagesUsername" \
      --secret "id=ORG_GRADLE_PROJECT_savaGithubPackagesPassword,env=ORG_GRADLE_PROJECT_savaGithubPackagesPassword" \
      -t "$dockerImage" \
      -f "$scriptDir/Dockerfile" \
      "$repoRoot"
  fi
  dockerArgs=(run --rm)

  # Only allocate an interactive TTY (-it) when encryption is enabled and no password
  # was supplied via this script. In that case the Java runtime must fall back to the
  # interactive Console prompt, which requires an attached terminal. When a password is
  # already provided (or encryption is disabled), the run is fully non-interactive.
  if [[ -n "$encrypt" && "$encrypt" != "false" && -z "$SAVA_VANITY_ENCRYPT_PASSWORD" ]]; then
    dockerArgs+=(-it)
  fi

  if [[ -n "$SAVA_VANITY_ENCRYPT_PASSWORD" ]]; then
    # Pass only the variable name; Docker reads its value from this process'
    # environment, keeping the secret off the command line.
    dockerArgs+=(-e SAVA_VANITY_ENCRYPT_PASSWORD)
  fi

  if [[ -n "$outDir" ]]; then
    # Resolve the host directory to an absolute path and ensure it exists so it
    # can be bind-mounted with write permissions into the container.
    hostOutDir="$(cd "$(dirname "$outDir")" 2>/dev/null && pwd)/$(basename "$outDir")"
    mkdir -p "$hostOutDir"
    dockerArgs+=(--mount "type=bind,source=$hostOutDir,target=/sava/$outDir,readonly=false")
  fi

  dockerArgs+=("$dockerImage")

  docker "${dockerArgs[@]}" "${jvmArgsArray[@]}"
else
  javaExe="$(pwd)/$simpleProjectName/build/images/sava-vanity/bin/java"
  readonly javaExe
  # When not using Docker, (re)build the local jlink binary image. It is rebuilt when
  # the build flag is set or when the image has not been built yet.
  if [[ "$build" == "true" ]] || [[ ! -x "$javaExe" ]]; then
    if [[ "$build" == "true" ]]; then
      printf "Rebuilding local binary image as requested by the build flag.\n";
    else
      printf "Local binary image not found, building it.\n";
    fi
    scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    repoRoot="$(cd "$scriptDir/.." && pwd)"
    (cd "$repoRoot" && ./gradlew ":$simpleProjectName:image")
  fi
  if [[ "$screen" == 0 ]]; then
    "$javaExe" "${jvmArgsArray[@]}"
  else
    screen -S "$simpleProjectName" "$javaExe" "${jvmArgsArray[@]}"
  fi
fi
