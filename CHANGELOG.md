# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [25.1.3]

### Added

- Partial support for base64+zstd encoding in `getProgramAccounts`
- Foojay Gradle toolchain for JDK provisioning support

### Changed

- Use `withInvokeExactBehavior` when reading primitives via VarHandles to avoid boxing
- Zero singleton for `InflationReward`
- Avoid String construction when parsing encoded binary data from JSON

## [25.1.2]

### Changed

- Expose transaction skeleton fields (PR #34)

## [25.1.0]

### Added

- Support for returning accounts, pre/post balances and inner instruction data during simulation

### Changed

- GitHub Action publish improvements

### Deprecated

- Invalid use of signers/accounts `simulateTransaction` interface methods

## [25.0.2]

### Added

- Checked Borsh variants when writing fixed length Defined Type arrays

## [25.0.1]

### Added

- Checked Borsh variants when writing fixed length arrays

## [25.0.0]

### Changed

- Move to Java 25
- Update Gradle

## [24.23.3]

### Added

- Discriminator convenience methods

## [24.23.2]

### Changed

- Establish a base `SolanaJsonRpcClient`

## [24.23.1]

### Changed

- Remove unused logger

## [24.22.1]

### Changed

- Move `HttpResponse` read utilities up a level
- Expose `readBody` utility method
- Provide a response parser that passes all available context
- Move Solana specific parsers down to the Solana client

## [24.22.0]

### Added

- Support gzip compression on the RPC client
- RPC client builder for convenience

### Changed

- Move towards supporting multiple response handlers
- Establish a common hierarchy for handling HTTP JSON responses
- Provide non-JSON-RPC generic parsers

## [24.21.5]

### Changed

- Prefer a new parser for each element
