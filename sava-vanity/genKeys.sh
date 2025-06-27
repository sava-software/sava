#!/usr/bin/env bash

set -e

simpleProjectName="sava-vanity"
readonly simpleProjectName
moduleName="software.sava.vanity"
readonly moduleName
mainClass="software.sava.vanity.Entrypoint"
readonly mainClass

jvmArgs="-server -Xms64M -Xmx128M"

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
keyFormat=
checkFound=
logDelay=
outDir=
sigVerify=

screen=0;

for arg in "$@"
do
  if [[ "$arg" =~ ^--.* ]]; then
    key="${arg%%=*}"
    key="${key##*--}"
    val="${arg#*=}"

    case "$key" in
      jvm | jvmArgs) jvmArgs="$val";;
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

javaExe="$(pwd)/$simpleProjectName/build/images/vanity/bin/java"
readonly javaExe

jvmArgs="$jvmArgs -D$moduleName.sigVerify=$sigVerify -D$moduleName.outDir=$outDir -D$moduleName.numThreads=$numThreads -D$moduleName.numKeys=$numKeys -D$moduleName.keyFormat=$keyFormat -D$moduleName.checkFound=$checkFound -D$moduleName.logDelay=$logDelay -D$moduleName.prefix=$prefix -D$moduleName.pCaseSensitive=$pCaseSensitive -D$moduleName.p1337Numbers=$p1337Numbers -D$moduleName.p1337Letters=$p1337Letters -D$moduleName.suffix=$suffix -D$moduleName.sCaseSensitive=$sCaseSensitive -D$moduleName.s1337Numbers=$s1337Numbers -D$moduleName.s1337Letters=$s1337Letters -m $moduleName/$mainClass"
IFS=' ' read -r -a jvmArgsArray <<< "$jvmArgs"

set -x

if [[ "$screen" == 0 ]]; then
  "$javaExe" "${jvmArgsArray[@]}"
else
  screen -S "$simpleProjectName" "$javaExe" "${jvmArgsArray[@]}"
fi
