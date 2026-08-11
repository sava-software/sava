![Sava](assets/images/solana_java_cup.svg)

# Sava [![Gradle Check](https://github.com/sava-software/sava/actions/workflows/build.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/build.yml) [![Publish Release](https://github.com/sava-software/sava/actions/workflows/publish.yml/badge.svg)](https://github.com/sava-software/sava/actions/workflows/publish.yml)

## Documentation

User documentation lives at [sava.software](https://sava.software/).

* [Dependency Configuration](https://sava.software/quickstart)
* [Core](https://sava.software/libraries/core): Common Solana cryptography and serialization utilities.
* [RPC](https://sava.software/libraries/rpc): HTTP and WebSocket Clients.

## WebSocket upgrade notes

The WebSocket lifecycle hardening release changes correlation and recovery behavior. Consumers
upgrading a long-lived client should account for these contracts:

* A failed Ping send now retires and aborts the current transport, invokes `onError` as the
  recovery policy, and then invokes `onPingError` as the Ping-specific observation. A Ping send
  which never settles, or a successful Ping which receives no peer frame, is reported only
  through `onError`. Reconnect from `onError`/`onClose`; use `onPingError` for observation.
* `pingDelay` is the peer-silence threshold and the budget for each probe phase. A successful
  send starts a fresh response window at send completion, so transport send latency is not
  charged to the peer. `connectTimeout`, `keepAliveDelay`, and `subscriptionResendDelay` now
  separate handshake, outbound keep-alive, and subscription retry policy. Their built-in
  defaults are 8,000 ms, twice `pingDelay` (saturating), and the greater of
  `reConnectDelay`, the check cadence, and 1 ms, respectively.
* `Timings` now has five record components. Its legacy three-argument constructor remains and
  derives both new values; the four-argument overload derives the resend delay. Code which
  reflects on record components or depends on record `equals`, `hashCode`, or `toString` must
  account for `keepAliveDelay` and `subscriptionResendDelay`. Existing third-party `Builder`
  implementations inherit timing getters derived from the legacy coupled settings; the new
  independent timing setters report `UnsupportedOperationException` until that implementation
  overrides them.
* `programSubscribe` retains its historical `(program, commitment)` identity. Use
  `keyedProgramSubscribe` with stable caller keys when the same program and commitment need
  multiple independently filtered durable registrations; remove each with the matching
  `keyedProgramUnsubscribe` key. Implementations other than Sava's built-in client may report
  this additive capability as unsupported.
* `connect()` is single-flight and returns caller-private views of an in-progress attempt;
  cancelling one view does not cancel the shared handshake. `onClose`, and `onError` for a
  failure attributed to the current transport, run after that transport is retired and without
  the lifecycle lock. An internal check-loop failure still invokes `onError` and then closes the
  whole instance.
  `lastMessageReceivedTimestamp()` reports application-message evidence only and returns `0`
  until the current connection receives a message; Ping/Pong traffic does not advance it.
* Signatures and custom subscription method names are now rejected locally when they cannot be
  embedded safely in a JSON frame. Custom `paramsJson` remains raw JSON and is still the
  caller's escaping and validation responsibility.

## Contributions

Please note that all contributions require agreeing to
the [Sava Engineering, Inc. CLA](https://gist.github.com/jpe7s/09546e42783187c6d04f38e04184ecfa).

[Please reach out](https://github.com/sava-software) before working on a pull request.

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
