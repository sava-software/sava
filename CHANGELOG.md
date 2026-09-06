# Changelog

## [25.11.0](https://github.com/sava-software/sava/compare/25.10.0...25.11.0) (2026-09-06)


### ⚠ BREAKING CHANGES

* Consumers of the removed APIs must migrate using MIGRATION.md.

### Features

* **rpc:** enhance getTransaction with max transaction version support ([39cfa56](https://github.com/sava-software/sava/commit/39cfa5645078461fc18d6007e5fd36e88be886de))
* **rpc:** expand LiveV1ValidatorCheck to support public clusters ([d4c776d](https://github.com/sava-software/sava/commit/d4c776d65ebf82d41be954cea21bea5043e3d0c0))
* **rpc:** expose the skeleton of getBlock entries ([b5d5d98](https://github.com/sava-software/sava/commit/b5d5d98904594ab7a88b2db84a244c1de881db99))
* **tx:** add explicit signing APIs ([09aaaf1](https://github.com/sava-software/sava/commit/09aaaf13a37a14bd406fda27719f7b352fae0622))
* **tx:** add setInstruction and insertInstruction to TxBuilder ([5aa18c5](https://github.com/sava-software/sava/commit/5aa18c5b17851157167c160c02eed9cd6d9d74f3))
* **tx:** add SIMD-0385 v1 transaction support with TxBuilder and V1Transaction ([1fff465](https://github.com/sava-software/sava/commit/1fff465a398048c25b0a887cb3a406a66858377f))
* **tx:** add strict mode to enforce SIMD-0385 v1 compliance ([360f5d1](https://github.com/sava-software/sava/commit/360f5d138a53925e41cfdfee625b7b3aa64087c7))
* **tx:** add support for priority fee and config value updates ([8d4d5c5](https://github.com/sava-software/sava/commit/8d4d5c5e283ee8c1afc1718ee21c8255287d924e))
* **tx:** add support for priority fee conversion methods ([210a83e](https://github.com/sava-software/sava/commit/210a83ef50fca74140ba6174d7eae6f4dd5ccfc1))
* **tx:** centralize comparators and account meta merging in TransactionRecord ([a1b9088](https://github.com/sava-software/sava/commit/a1b9088850ec564f2d43166c9fc94becf8dc5bc5))
* **tx:** enhance heap size and account data size limit validations ([23e27b5](https://github.com/sava-software/sava/commit/23e27b5edac13a5c3577fc2c215a49128ad7b89f))
* **tx:** enhance v1 transaction support and budget validations ([82987fe](https://github.com/sava-software/sava/commit/82987fefbd5defa61ac4316f1f6763b410ec1f0d))
* **tx:** preserve config values in derived V1 transactions ([ce50c9f](https://github.com/sava-software/sava/commit/ce50c9fe0a4778743568a5f91ce6a98c2c8dc4b7))
* **tx:** refactor and modularize account merging, sorting, and transaction signing ([b3bf8eb](https://github.com/sava-software/sava/commit/b3bf8eb9113726d9a560fd12b02197788f60d123))
* **tx:** refactor transaction model into modular skeletons ([15b885f](https://github.com/sava-software/sava/commit/15b885fe1a01d51c21a0b4638ef11963f4a77b7e))
* **tx:** unify config value handling and enhance v1 transaction skeleton ([cfce281](https://github.com/sava-software/sava/commit/cfce281922c2c5a6be1415adac8e074531f3754c))
* **tx:** validate priority fee bits and skip unknown config mask values ([924d2fa](https://github.com/sava-software/sava/commit/924d2fa74dcb6281d7b81ec11b6a3ee47182ff89))


### Bug Fixes

* address compatibility cleanup review ([666c164](https://github.com/sava-software/sava/commit/666c164cf411dd2bfa7847ac2daffaa65079498b))
* **core:** bound the v1 wire count fields regardless of strict ([6e70c7d](https://github.com/sava-software/sava/commit/6e70c7dc73605166ca0843d0aca6e9b02460a40f))
* **core:** bound v1 instruction account indices by the wire, not the array ([f91ad69](https://github.com/sava-software/sava/commit/f91ad6902a18e952398e0047987a750acc02a63a))
* **core:** budget builtin instructions at the rate the runtime does ([fa0e8f6](https://github.com/sava-software/sava/commit/fa0e8f64f5d6484d64d29feb9be052306b8b0b60))
* **core:** keep the v1 interface additions binary compatible ([dabfe20](https://github.com/sava-software/sava/commit/dabfe20bf6822ec9c326cd708790ea84e388d2f2))
* **core:** port the length-bounded discriminator matching from main ([197239a](https://github.com/sava-software/sava/commit/197239aa630837b5ee9515023e7b35f339b6cdb5))
* **core:** port the two-bound instruction account resolution from main ([2b5d75e](https://github.com/sava-software/sava/commit/2b5d75eb2ab8a8c9231e60718a156b304ba5fe3b))
* **core:** reject an out-of-range v1 instruction account index ([2771e7d](https://github.com/sava-software/sava/commit/2771e7dfc0dcb374957d9b43a5c7dfda7610c401))
* **core:** reject unknown v1 TransactionConfigMask bits ([27bd050](https://github.com/sava-software/sava/commit/27bd0504db1a9192c1325f60dd5f765e23cdac2c))
* **core:** treat an explicit zero compute unit limit as unstated ([ab9e80d](https://github.com/sava-software/sava/commit/ab9e80dbb774aa220afff7471c4f8efd5fff471c))
* ignore extra fields when importing JSON keys ([1c8a2e1](https://github.com/sava-software/sava/commit/1c8a2e14d4759a0f587a90d88362cbdc02db54ea))
* import JSON keys regardless of field order ([20b741f](https://github.com/sava-software/sava/commit/20b741fe83dbd5507a72b171cd4e630fc6d0210c))
* keep equal instructions interchangeable as hash keys ([fff9ba4](https://github.com/sava-software/sava/commit/fff9ba4e7a3f1236a02d6e3b989887f3fbb4e6bb))
* **rpc:** handle non-numeric values in JSON parsing with ValueType checks ([b1754b0](https://github.com/sava-software/sava/commit/b1754b023ed4b4d357594c28f6d9f27e7aa61e05))
* **tx:** update terminology and validation for v1 transaction accounts ([b3a2a1c](https://github.com/sava-software/sava/commit/b3a2a1c36083326ac4a3fcfe9e47a82e2285d1a6))


### Code Refactoring

* remove deprecated compatibility APIs ([042025c](https://github.com/sava-software/sava/commit/042025c737135719167e1e13ac669243f74fa448))

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
