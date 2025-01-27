# Vanity Address Generator

## Compile

```shell
./vanity/compile.sh
```

## Run

```shell
./vanity/genKeys.sh --p="abc"
```

### Args

A `prefix` and/or `suffix` must be provided.

`numThreads` defaults to half of the systems CPU's.

* jvmArgs="-server -Xms64M -Xmx128M"
* [nt | numThreads]=
* [nk | numKeys]=1
* [o | outDir]='.keys'


* [p | prefix]=""
* [pc | pCaseSensitive]=false
* [pn | p1337Numbers]=true
* [pl | p1337Letters]=true


* [s | suffix]=""
* [sc | sCaseSensitive]=false
* [sn | s1337Numbers]=true
* [sl | s1337Letters]=true
