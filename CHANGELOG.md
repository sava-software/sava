# Changelog

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
