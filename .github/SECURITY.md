# Security Policy

## Reporting a vulnerability

Report suspected vulnerabilities privately through GitHub:
**[Report a vulnerability](https://github.com/sava-software/sava/security/advisories/new)**.

Please do not open a public issue or pull request for a suspected vulnerability.

A useful report names the affected artifact and release, Java version, class, or method, expected security impact,
and the smallest input or call sequence that reproduces the behaviour. A failing test is helpful but not required.
Use synthetic or disposable test keys, and redact real private keys, seed phrases, passwords, and access tokens.

## What to expect

- We aim to acknowledge reports within 14 calendar days.
- We assess the reported impact and keep the reporter informed as the investigation progresses. For confirmed
  vulnerabilities, we communicate a remediation plan and any available mitigations.
- We coordinate disclosure with the reporter, normally publishing a GitHub security advisory alongside a fixed
  release. We may publish an advisory with available mitigations before a fix is ready when the vulnerability is
  actively exploited, already public, or delaying disclosure would leave users unable to protect themselves.
- We credit the reporter unless they prefer otherwise.

## Supported versions

Security fixes are delivered in new stable releases; only the latest stable release is supported, and fixes are
not backported to earlier versions. Reports discovered on older releases are still welcome; remediation may
require upgrading to the latest stable release.

## Scope

In scope are the published `software.sava:sava-core` and `software.sava:sava-rpc` artifacts and the `sava-vanity`
application. The `sava-examples` and `jmh` modules are not published and are out of scope. Of particular interest:

- key generation, public key and program derived address derivation, and encrypted key files;
- transaction construction, serialization, and signing;
- parsing of data returned by RPC nodes: JSON-RPC responses, WebSocket frames, compressed HTTP bodies, and on-chain
  account data.

Vulnerabilities in the Solana protocol, validator clients, or RPC providers are out of scope here; please report those
to their maintainers.

Vulnerabilities in Sava triggered by malicious RPC nodes, malformed responses, or on-chain account data remain in
scope. This includes denial of service, incorrect parsing with security impact, and unintended signing behaviour.
