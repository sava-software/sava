![Sava](assets/images/solana_java_cup.svg)

# Sava [![Build](https://github.com/sava-software/sava/actions/workflows/gradle.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/gradle.yml) [![Release](https://github.com/sava-software/sava/actions/workflows/release.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/release.yml)

## Documentation

User documentation lives at [sava.software](https://sava.software/).

* [Dependency Configuration](https://sava.software/quickstart)
* [Core](https://sava.software/libraries/core): Common Solana cryptography and serialization utilities.
* [RPC](https://sava.software/libraries/rpc): HTTP and WebSocket Clients.

## Contribution

Unit tests are needed and welcomed. Otherwise, please open
a [discussion](https://github.com/sava-software/sava/discussions), issue, or send an email before working on a pull
request.

## Build

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed to access
dependencies hosted on GitHub Package Repository.

Add the following properties to `$HOME/.gradle/gradle.properties`.

```gradle.properties
gpr.user=GITHUB_USERNAME
gpr.token=GITHUB_TOKEN
```

```shell
./gradlew check
```
