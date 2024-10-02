![Sava](assets/images/solana_java_cup.svg)

# Sava [![Build](https://github.com/sava-software/sava/actions/workflows/gradle.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/gradle.yml) [![Release](https://github.com/sava-software/sava/actions/workflows/release.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/release.yml)

### Features

- HTTP and WebSocket JSON RPC Clients.
- Transaction (de)serialization.
    - Legacy
    - V0
- Crypto utilities for elliptic curve Ed25519 and Solana accounts.
- Borsh (de)serialization.

### Requirements

- The latest generally available JDK. This project will continue to move to the latest and will not maintain
  versions released against previous JDK's.

### Dependencies

- [Core](core/src/main/java/module-info.java)
    - java.base
    - [Bouncy Castle](https://www.bouncycastle.org/download/bouncy-castle-java/#latest)

- [RPC](rpc/src/main/java/module-info.java)
    - software.sava.core
    - java.net.http
    - [JSON Iterator](https://github.com/comodal/json-iterator?tab=readme-ov-file#json-iterator)

### Add Dependency

Create
a [GitHub user access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-personal-access-token-classic)
with read access to GitHub Packages.

Then add the following to your Gradle build script.

```groovy
repositories {
  maven {
    url = "https://maven.pkg.github.com/sava-software/sava"
    credentials {
      username = GITHUB_USERNAME
      password = GITHUB_PERSONAL_ACCESS_TOKEN
    }
  }
}

dependencies {
  implementation "software.sava:sava-core:$VERSION"
  implementation "software.sava:sava-rpc:$VERSION"
}
```

### Contribution

Unit tests are needed and welcomed. Otherwise, please open an issue or send an email before working on a pull request.

### Disclaimer

In addition to the MIT License, this project is under active development and breaking changes are to be expected.
