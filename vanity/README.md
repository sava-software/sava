# Vanity Address Generator

## GitHub Access Token

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed to access
dependencies hosted on GitHub Package Repository.

Create a `gradle.properties` file in the sava project directory root or under `$HOME/.gradle/`.

```properties
gpr.user=GITHUB_USERNAME
gpr.token=GITHUB_TOKEN
```

## Compile

```shell
./vanity/compile.sh
```

## Run

```shell
./vanity/genKeys.sh --prefix="abc"
```

### Args

A `prefix` and/or `suffix` must be provided.

`numThreads` defaults to half of the systems CPU's.

- jvmArgs="-server -Xms64M -Xmx128M"
- [nt | numThreads]=
- [nk | numKeys]=1
- [o | outDir]='.keys'
- [sv | sigVerify]=false

#### Prefix

- [p | prefix]=""
- [pc | pCaseSensitive]=false
- [pn | p1337Numbers]=true
- [pl | p1337Letters]=true

#### Suffix

- [s | suffix]=""
- [sc | sCaseSensitive]=false
- [sn | s1337Numbers]=true
- [sl | s1337Letters]=true
