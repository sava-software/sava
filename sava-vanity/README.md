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

* A `prefix` and/or `suffix` must be provided.
* `numThreads` defaults to half of the systems CPU's.
* Each thread will check every `checkFound` iterations if `numKeys` have been found.
* `p1337Letters` allows alphabetic characters to be replaced by visually similar numbers.
* `1337Numbers` allows numbers to be replaced by visually similar alphabetic characters.
* `screen` may be enabled to manage the session so that it can be re-attached if a remote session is disconnected.
    * `ctrl+a -> d` to detach
    * `screen -r` to re-attach

#### Run Control

- jvmArgs="-server -Xms64M -Xmx128M"
- screen=0
- [nt | numThreads]=
- [nk | numKeys]=1
- [kf | keyFormat]="base64KeyPair"
- [cf | checkFound]=131072
- [ld | logDelay]="5S"
- [o  | outDir]='.keys'
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
