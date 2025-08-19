![Sava](assets/images/solana_java_cup.svg)

# Sava [![Gradle Check](https://github.com/sava-software/sava/actions/workflows/build.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/build.yml) [![Publish Release](https://github.com/sava-software/sava/actions/workflows/publish.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/publish.yml)

## Documentation

User documentation lives at [sava.software](https://sava.software/).

* [Dependency Configuration](https://sava.software/quickstart)
* [Core](https://sava.software/libraries/core): Common Solana cryptography and serialization utilities.
* [RPC](https://sava.software/libraries/rpc): HTTP and WebSocket Clients.

## Contributions

Please note that all contributions require agreeing to the [Sava Engineering, Inc. CLA](https://gist.github.com/jpe7s/09546e42783187c6d04f38e04184ecfa).

Tests are needed and welcomed. Otherwise, [please reach out](https://github.com/sava-software) before working on a pull request.


## Build

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed to access
dependencies hosted on GitHub Package Repository.

#### ~/.gradle/gradle.properties

```properties
savaGithubPackagesUsername=GITHUB_USERNAME
savaGithubPackagesPassword=GITHUB_TOKEN
```

```shell
./gradlew check
```
