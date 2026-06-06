# Vanity Address Generator

## GitHub Access Token

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed to access
dependencies hosted on GitHub Package Repository.

Add the following properties to `$HOME/.gradle/gradle.properties`.

```gradle.properties
savaGithubPackagesUsername=GITHUB_USERNAME
savaGithubPackagesPassword=GITHUB_TOKEN
```

## Compile

```shell
./sava-vanity/compile.sh
```

## Run

```shell
./sava-vanity/genKeys.sh --prefix="abc"
```

### Docker

Instead of running the local jlink binary, you can run a Docker image (e.g. built from the
included `Dockerfile`) by passing the `docker` flag with the image name and tag.

Build the image:

```shell
docker build --build-arg PROJECT=sava-vanity -t sava-vanity:local -f sava-vanity/Dockerfile .
```

Run it:

```shell
./sava-vanity/genKeys.sh --docker="sava-vanity:local" --prefix="abc"
```

When `outDir` is provided, the host directory is created if necessary and bind-mounted into the
container with write permissions so that generated keys are persisted on the host:

```shell
./sava-vanity/genKeys.sh --docker="sava-vanity:local" --prefix="abc" --outDir=".keys"
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
- [d | docker | dockerImage]=
- screen=0
- [nt | numThreads]=
- [nk | numKeys]=1
- [kf | keyFormat]="base64KeyPair"
- [cf | checkFound]=131072
- [ld | logDelay]="5S"
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
