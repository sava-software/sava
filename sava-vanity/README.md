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

By default the image is built only when it is not found locally. Pass the `build` flag to force a
rebuild even if the image already exists:

```shell
./sava-vanity/genKeys.sh --docker="sava-vanity:local" --build --prefix="abc"
```

When not using Docker, the local jlink binary image (`./gradlew :sava-vanity:image`) is built
automatically when it has not been built yet. Pass the `build` flag to force a rebuild of the local
binary image even if it already exists:

```shell
./sava-vanity/genKeys.sh --build --prefix="abc"
```

When `outDir` is provided, the host directory is created if necessary and bind-mounted into the
container with write permissions so that generated keys are persisted on the host:

```shell
./sava-vanity/genKeys.sh --docker="sava-vanity:local" --prefix="abc" --outDir=".keys"
```

### Args

* A `prefix` and/or `suffix` must be provided.
* `numThreads` defaults to half of the systems CPU's.
* `keyFileFormat` controls the on-disk key file format and may be `json` (default) or `properties`. Both
  contain the same fields (and honor the `encrypt` options) and can be loaded via
  `PrivateKeyEncoding`.
* Each thread will check every `checkFound` iterations if `numKeys` have been found.
* `p1337Letters` allows alphabetic characters to be replaced by visually similar numbers.
* `1337Numbers` allows numbers to be replaced by visually similar alphabetic characters.
* `screen` may be enabled to manage the session so that it can be re-attached if a remote session is disconnected.
  * `ctrl+a -> d` to detach
  * `screen -r` to re-attach

#### Run Control

- jvmArgs="-server -Xms64M -Xmx128M"
- [d | docker | dockerImage]=
- [b | build]=false
- screen=0
- [nt | numThreads]=
- [nk | numKeys]=1
- [kf | keyFormat]="base64KeyPair"
- [kff | keyFileFormat]="json"
- [cf | checkFound]=131072
- [ld | logDelay]="5S"
- [o | outDir]='.keys'
- [sv | sigVerify]=false

#### Encryption

The generated secret key can be encrypted at rest by enabling the `encrypt` flag. The password is
never passed on the command line or as a JVM system property (both of which are visible in process
listings); it is supplied to the Java runtime only via the `SAVA_VANITY_ENCRYPT_PASSWORD`
environment variable.

- [e | encrypt]=false
- [pw | password] — securely prompts for the password (with confirmation) and forwards it to the
  Java runtime via the environment variable. Implies `encrypt=true`.
- [pe | passwordEnv]=ENV_VAR_NAME — reads the password from an already-exported environment variable
  for fully non-interactive runs. Implies `encrypt=true`.

If `encrypt=true` is set without `password`/`passwordEnv`, and the
`SAVA_VANITY_ENCRYPT_PASSWORD` environment variable is not present, the application falls back to
reading the password from the interactive Java Console.

```shell
# Securely prompt for the encryption password.
./sava-vanity/genKeys.sh --prefix="abc" --password

# Non-interactive: read the password from an existing environment variable.
export MY_VANITY_PASSWORD="..."
./sava-vanity/genKeys.sh --prefix="abc" --passwordEnv=MY_VANITY_PASSWORD
```

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
