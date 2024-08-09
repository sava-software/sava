# Sava [![Build](https://github.com/Sava-Software/sava/actions/workflows/gradle.yml/badge.svg)](https://github.com/Sava-Software/sava/actions/workflows/gradle.yml) [![Release](https://github.com/Sava-Software/sava/actions/workflows/release.yml/badge.svg)](https://github.com/Sava-Software/sava/actions/workflows/release.yml)

## Features

- HTTP and WebSocket JSON RPC Clients.
- Transaction (de)serialization.
    - Legacy
    - V0
- Crypto utilities for elliptic curve Ed25519 and Solana accounts.
- Borsh (de)serialization.

## Requirements

- The latest generally available JDK. This project will continue to move to the latest JDK and will not maintain
  versions released against previous JDK's.

## Dependencies

- java.base
- java.net.http
- [Bouncy Castle](https://www.bouncycastle.org/download/bouncy-castle-java/#latest)
- [JSON Iterator](https://github.com/comodal/json-iterator?tab=readme-ov-file#json-iterator)

## Contribution

Unit tests are needed and welcomed, as well as general usage/testing-out of the library. Otherwise, please open an issue
or send an email before working on a pull request.
