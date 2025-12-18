# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Calendar Versioning](https://calver.org/) (YY.MINOR.PATCH).

## [Unreleased]

## [25.1.1] - 2024-12-18

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

[Unreleased]: https://github.com/sava-software/sava/compare/25.1.1...HEAD
[25.1.1]: https://github.com/sava-software/sava/compare/25.1.0...25.1.1
[25.1.0]: https://github.com/sava-software/sava/compare/25.0.2...25.1.0
[25.0.2]: https://github.com/sava-software/sava/compare/25.0.1...25.0.2
[25.0.1]: https://github.com/sava-software/sava/compare/25.0.0...25.0.1
[25.0.0]: https://github.com/sava-software/sava/compare/24.23.3...25.0.0
[24.23.3]: https://github.com/sava-software/sava/compare/24.23.2...24.23.3
[24.23.2]: https://github.com/sava-software/sava/compare/24.23.1...24.23.2
[24.23.1]: https://github.com/sava-software/sava/compare/24.22.1...24.23.1
[24.22.1]: https://github.com/sava-software/sava/compare/24.22.0...24.22.1
[24.22.0]: https://github.com/sava-software/sava/compare/24.21.5...24.22.0
[24.21.5]: https://github.com/sava-software/sava/releases/tag/24.21.5
