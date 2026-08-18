# Changelog

## [25.10.0](https://github.com/sava-software/sava/compare/25.9.1...25.10.0) (2026-08-18)


### ⚠ BREAKING CHANGES

* **core:** TransactionSkeleton#parseInstructions and #filterInstructions now throw IndexOutOfBoundsException for an instruction account index the transaction does not declare (at or past numAccounts()), in every message format; such indices previously produced a null AccountMeta inside the returned instruction's account list. Indices the transaction declares but the supplied array cannot resolve continue to read as null.
* **core:** Transaction.sign(Signer, byte[]) and Transaction.sign(SequencedCollection<Signer>, byte[]) no longer write the signature-count prefix. A payload must declare its required signature count, as every Transaction.createTx serialization does; a count that is absent or disagrees with the message header now throws IllegalArgumentException instead of silently corrupting the payload.

### Bug Fixes

* **core:** bound discriminator matching by the instruction's own length ([f06bf0e](https://github.com/sava-software/sava/commit/f06bf0e1eccb3a5e7bdac536001a4149128d5e88))
* **core:** diagnose a header declaring more signers than addresses ([e32a323](https://github.com/sava-software/sava/commit/e32a32367971bd7477f88248085c0daf17835777))
* **core:** read the signer count from the payload instead of writing it ([727501a](https://github.com/sava-software/sava/commit/727501a4df795f7452fa3a2aa8753264fe9129bf))
* **core:** resolve instruction account indices against two bounds ([7f0430f](https://github.com/sava-software/sava/commit/7f0430fc80f0083827ad18ae7f462cebaf1c1c42))


### Miscellaneous Chores

* release 25.10.0 ([f0ea375](https://github.com/sava-software/sava/commit/f0ea3752dd093f5d28e4f14fdb061708caf67895))
* release 25.6.0 ([6044238](https://github.com/sava-software/sava/commit/604423865c6f9ca4485ca9e9aefef8223b47ad1c))

## [25.9.1](https://github.com/sava-software/sava/compare/25.9.0...25.9.1) (2026-08-14)


### Bug Fixes

* align client behavior with current Solana ([0d68f02](https://github.com/sava-software/sava/commit/0d68f0205d1e5c3936ba3a583f11b48aee39705a))
* **core:** harden transaction signing boundaries ([7377486](https://github.com/sava-software/sava/commit/73774865a284ffa8cdcbb59ddf41505fc6fa7437))
* **core:** match Rust TokenMetadata parsing ([e62048c](https://github.com/sava-software/sava/commit/e62048cbd07a5d47e3aa8dbe77b59c0628b1989c))
* **core:** match Solana PDA derivation limits ([5cbba47](https://github.com/sava-software/sava/commit/5cbba478dac01e077ef659a0efdffa6213035231))
* **core:** preserve Rust string semantics ([8fc443d](https://github.com/sava-software/sava/commit/8fc443d53906bcab525fd2c048dbbd579f1b0573))
* **core:** preserve signer state after failures ([91bc814](https://github.com/sava-software/sava/commit/91bc81470f05da06381d99035ad9e2b40561d3d4))
* **core:** preserve Token-2022 metadata order ([3160f52](https://github.com/sava-software/sava/commit/3160f52dc8d4b9477b4c93307d841c6c9cf642a1))
* **core:** reject malformed Borsh strings ([5edb2a1](https://github.com/sava-software/sava/commit/5edb2a10c8b00042a536114f9156f1ed4db9d4b7))

## [25.9.0](https://github.com/sava-software/sava/compare/25.8.3...25.9.0) (2026-08-12)


### Features

* **websocket:** detect a peer that stopped answering ([d48cd62](https://github.com/sava-software/sava/commit/d48cd6230c85bb7784c35212e9048bfe7fa66fd2))
* **websocket:** make transport recovery self-healing ([52dce85](https://github.com/sava-software/sava/commit/52dce858c98a4d03a40197a19e47a04e774757be))


### Bug Fixes

* **core:** correct sysvar and invoked-account parsing ([6297d63](https://github.com/sava-software/sava/commit/6297d63c8a8acabb7490299bba7d186e554c5520))
* **rpc:** harden websocket lifecycle and mutation contracts ([b4c0afe](https://github.com/sava-software/sava/commit/b4c0afe22a3042fc316eb82dce0515e24a7d0ef1))
* **rpc:** preserve error parser cursor ([f0eefe1](https://github.com/sava-software/sava/commit/f0eefe14cd9e623970b0bdf29cc7034ab74bfa25))
* **websocket:** adjudicate shared subscription ids by wire order ([a17daf2](https://github.com/sava-software/sava/commit/a17daf249ca03001929dc4229974076ed2726fca))
* **websocket:** an attempt's ordinal dies with the attempt ([0d4f07a](https://github.com/sava-software/sava/commit/0d4f07a8739902596bf8de0eb5ec58b2e558563e))
* **websocket:** cancel before resubscribing; pin live server behavior ([0f6e808](https://github.com/sava-software/sava/commit/0f6e8081e0aa30be3ab57b87111df88229bd2adf))
* **websocket:** carry wire order on attempt ordinals, not request ids ([05a4294](https://github.com/sava-software/sava/commit/05a42941f9ccd3c296907dd076a973a4101db752))
* **websocket:** record gate-blocked cancellations; equivalence-check obsolescence ([6bdbfb1](https://github.com/sava-software/sava/commit/6bdbfb1c8ca11de50fd99ecaeadeb85ddd57f4d2))
* **websocket:** release adjudication bookkeeping on every terminal path ([6c6f6ca](https://github.com/sava-software/sava/commit/6c6f6ca3369792dc040e976ddf468b3cecaafff8))


### Miscellaneous Chores

* release 25.9.0 ([df68beb](https://github.com/sava-software/sava/commit/df68bebca65aa6936d28dc5db5c021165d21aa0a))

## [25.8.3](https://github.com/sava-software/sava/compare/25.8.2...25.8.3) (2026-08-05)


### ⚠ BREAKING CHANGES

* **core, rpc:** New fuzz logic and corpora require a correctly configured Jazzer harness in local and CI environments.

### Features

* **core, rpc:** add fuzz targets for ed25519, RPC responses, and WebSocket input ([1611541](https://github.com/sava-software/sava/commit/16115416ea62f8ae0665458d45ddb9780fae0796))
* **core:** add regression corpus support for base58 and borsh fuzz tests ([e912abc](https://github.com/sava-software/sava/commit/e912abcd30f627345a972cadb657a16880083ed6))
* **core:** add unsigned integer support and validation for byte utilities ([b548021](https://github.com/sava-software/sava/commit/b5480218a7a9661c18e61ab28269f0745821f98d))
* **rpc:** add capacity clamping and fuzzing improvements ([141c295](https://github.com/sava-software/sava/commit/141c295043a16d223efe0d0b312a1db405eccf0f))
* **rpc:** add message size cap enforcement for WebSocket reassembly ([bcf991b](https://github.com/sava-software/sava/commit/bcf991b797e3d037ac23bf315abdcc384aa2fd0a))


### Bug Fixes

* **ci:** validate max-fuzz-time input in fuzz.yml ([4739315](https://github.com/sava-software/sava/commit/473931550e0b2070a70818278e8fe88e3d3526b6))
* **hardening:** update mutation triage labels and seed handling ([326dfe5](https://github.com/sava-software/sava/commit/326dfe50784a00ca42757433ac9692f35bb85c5f))

## [25.8.2](https://github.com/sava-software/sava/compare/25.8.1...25.8.2) (2026-07-21)


### Bug Fixes

* Fix pinging immediately after connection upgrade. ([07e3592](https://github.com/sava-software/sava/commit/07e3592706a78c2b29514a3006341a98f4032624))
* **ws:** Fix pinning the CPU when waiting for intial subscription responses. ([ef7c9f4](https://github.com/sava-software/sava/commit/ef7c9f4a0952f5e532be563bf2b5f9c9f4f8474a))

## [25.8.1](https://github.com/sava-software/sava/compare/25.8.0...25.8.1) (2026-07-21)


### Features

* **core:** Improve conversion of u64 that exceed the max value of i64. ([8fb4c56](https://github.com/sava-software/sava/commit/8fb4c56b867d9aea198c6bbbee70e10f6146b46a))


### Bug Fixes

* **core:** Merging invoked accounts into write only accounts. ([0844b5d](https://github.com/sava-software/sava/commit/0844b5d4b8a1c6f1976b5ac75f889d66d8333a86))
* **rpc:** Protect against malicious Content-Length header leading to OOM ([2a89159](https://github.com/sava-software/sava/commit/2a89159ee04f7abff55f9e987331a9aac4f6da12))

## [25.8.0](https://github.com/sava-software/sava/compare/25.7.0...25.8.0) (2026-07-19)


### ⚠ BREAKING CHANGES

* **core:** Projects must comply with mutation testing enforcement to ensure quality gate passes. Update workflows accordingly.

### Features

* **core:** add mutation testing baselines and policies ([471c770](https://github.com/sava-software/sava/commit/471c770abe87107471c576faf24e075c60dd4083))


### Miscellaneous Chores

* release 25.8.0 ([5cfea95](https://github.com/sava-software/sava/commit/5cfea954ba6a8953e0b8d3bf24f202af2fe0007a))

## [25.7.0](https://github.com/sava-software/sava/compare/25.6.1...25.7.0) (2026-07-17)


### Features

* **core/encoding:** enhance Base58 fuzzing, tooling, and verification workflows ([37b4874](https://github.com/sava-software/sava/commit/37b48747444c914d7decb34c7471c9f52f8ef61d))


### Miscellaneous Chores

* release 25.7.0 ([54c0395](https://github.com/sava-software/sava/commit/54c0395f681f41c5df731c438f779f9f46d55f70))

## [25.6.1](https://github.com/sava-software/sava/compare/25.6.0...25.6.1) (2026-07-15)


### Bug Fixes

* **core:** handle BigInteger size and signed overflow issues in ByteUtil ([3eccc12](https://github.com/sava-software/sava/commit/3eccc122378fe917f18df7a0a9b1816a5fdfbc8e))

## [25.6.0](https://github.com/sava-software/sava/compare/25.5.0...25.6.0) (2026-07-14)


### ⚠ BREAKING CHANGES

* **core:** The TokenExtensions accessor interface has been removed, and the Token2022/Token2022Account extensions map component is replaced by Set<TokenExtension> tokenExtensions. Iterate the Set and switch on the sealed TokenExtension type instead.

### Features

* **core:** add round-trip tests for Token-2022 extensions and TokenExtensions interface ([f77c24e](https://github.com/sava-software/sava/commit/f77c24e6b88b6a0a56512064eb18c331b9ac5dcf))
* **core:** add SolanaAccountsBuilder for flexible accounts customization ([c0baaa9](https://github.com/sava-software/sava/commit/c0baaa927dbd589489f1d922920aa9caaf29c252))
* **core:** parse Token-2022 extensions as a sealed Set with unknown extension support ([fa799a0](https://github.com/sava-software/sava/commit/fa799a0f2b64453019d16faafe037529bcb7bcf3))
* **rpc:** add generic subscription support for JSON-RPC websocket ([3da0a3c](https://github.com/sava-software/sava/commit/3da0a3cd0a3f6ed6a58c5bb63416852a5cebba96))
* **rpc:** add helpers for JSON request construction and improve account queries ([527d477](https://github.com/sava-software/sava/commit/527d4774e2e426ee261cd61379e23105e905f9b4))
* **rpc:** enhance websocket handling, add Tx index parsing, and improve tests ([3df238e](https://github.com/sava-software/sava/commit/3df238edec6bdfc0db3343f506402df38109d21c))
* **rpc:** expose additional configuration methods in clients ([ca8b84d](https://github.com/sava-software/sava/commit/ca8b84d3896dd55b441d6d9672f2192f3aedfd24))
* **rpc:** improve JSON parsing, inflation reward handling, and enhance tests ([1042f0e](https://github.com/sava-software/sava/commit/1042f0e5a1257c0434398776a1c5b71575581e6b))
* **rpc:** mark deprecated methods and fields for removal ([9a9c9ed](https://github.com/sava-software/sava/commit/9a9c9ed90ddd6aeb90c8302bffcb524a52bd843c))


### Bug Fixes

* **rpc:** handle "error" field in transaction signature parsing ([68d47cc](https://github.com/sava-software/sava/commit/68d47cc47724591bbcc7829ce9d75932a929824b))


### Miscellaneous Chores

* release 25.6.0 ([5617859](https://github.com/sava-software/sava/commit/56178598ea8204930768d25d0c4de8a69a95b81e))

## [25.5.0](https://github.com/sava-software/sava/compare/25.4.1...25.5.0) (2026-07-10)


### Features

* **release:** re-release 25.4.1 context ([3c1e948](https://github.com/sava-software/sava/commit/3c1e94856e00e65d4533db6eb04aede6f04f3fd3))
* **rpc:** add CommitCancelled variant to TransactionError ([d97e07d](https://github.com/sava-software/sava/commit/d97e07d1157e7c8b55317acfb56a2745abb53a53))
* **rpc:** enhance JSON parsing resilience and refactor transaction constants ([1d06e68](https://github.com/sava-software/sava/commit/1d06e68a5b094a0d74c2a9d967ac5a12122b08f6))


### Bug Fixes

* **github:** update publish workflow permissions ([3d26b44](https://github.com/sava-software/sava/commit/3d26b4423d364c6b41ad0ec56dcb5140ea9d924b))

## [25.4.1](https://github.com/sava-software/sava/compare/25.4.0...25.4.1) (2026-07-07)


### Features

* **accounts:** improve key handling with secure destruction and aad validation ([e05c8d9](https://github.com/sava-software/sava/commit/e05c8d99fdb7f30e3c381e6cc2d6899ef6a787bd))
* **rpc:** add support for returnData in transaction metadata ([c996a51](https://github.com/sava-software/sava/commit/c996a51c0fa9e7790e95904fdb74318eab2f6f7f))

## [25.4.0](https://github.com/sava-software/sava/compare/25.3.5...25.4.0) (2026-06-07)


### Features

* **accounts:** enforce stricter key property validation and secure secret handling ([cac0358](https://github.com/sava-software/sava/commit/cac0358ea970618f4e381690349a3f33e89eeefe))
* **vanity:** add Argon2id key derivation with heap tuning and JSON replacement ([10a470a](https://github.com/sava-software/sava/commit/10a470a2399829ed8cbe0fb2bdb9737322cdd7e8))
* **vanity:** add Docker support and optimize build scripts ([1f7552c](https://github.com/sava-software/sava/commit/1f7552c987f64918d02a931c3443fd4a415865c5))
* **vanity:** add password-based encrypted key file support ([ba145d6](https://github.com/sava-software/sava/commit/ba145d6b57d4b9a7521612c1a144b6fed15dccc1))


### Bug Fixes

* **accounts:** lower PBKDF2 minimum iterations in tests for faster execution ([18d5a93](https://github.com/sava-software/sava/commit/18d5a93808aed01cb81475241073044df8922274))
* **accounts:** update salt and iv validation to throw specific exceptions ([9e07aeb](https://github.com/sava-software/sava/commit/9e07aeba327ad5b43aef23a73b21df78107d27db))
* **release-please:** improve workflow condition to support forked repos ([cda3498](https://github.com/sava-software/sava/commit/cda34980d171a5fb86e36c932d7a127f7137ea49))
* **vanity:** handle null or empty keyFormat gracefully ([cda3498](https://github.com/sava-software/sava/commit/cda34980d171a5fb86e36c932d7a127f7137ea49))
* **vanity:** set default keyFormat in genKeys.sh script ([cda3498](https://github.com/sava-software/sava/commit/cda34980d171a5fb86e36c932d7a127f7137ea49))


### Miscellaneous Chores

* release 25.4.0 ([2c823f9](https://github.com/sava-software/sava/commit/2c823f9acacb124a02a1791c645af21c1fad157f))

## [25.3.5](https://github.com/sava-software/sava/compare/25.3.4...25.3.5) (2026-05-29)


### Bug Fixes

* trigger release ([09bc8aa](https://github.com/sava-software/sava/commit/09bc8aa09141d3d4a766d08ccf9b6f14dfdd2743))
