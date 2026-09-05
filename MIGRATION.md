# Migrating from 25.10.0 to 25.11.0

25.11.0 introduces transaction format v1 and removes APIs that were already
deprecated in 25.10.0. These removals are intentional breaking changes.
Applications using them need source changes and recompilation. Legacy and v0
transaction factories remain available; their deprecation is deferred until v1 is
activated on mainnet, with removal to follow later. The deprecated RecentBlockhashes
sysvar accessors also remain.

| Removed API | Replacement |
| --- | --- |
| `PublicKey.verifySignature` overloads taking a `String` signature | Pass the decoded signature as `byte[]`. |
| `Hmac.hmacSHA512(byte[], byte[])` | Obtain a `Mac` from `Hmac.hmacSHA512()`, initialize it with the key, and authenticate the message. |
| `Token2022.extensions()` and `Token2022Account.extensions()` | Use `tokenExtensions()`, which returns a `Set<TokenExtension>`. |
| `ExtensionType` and `TokenExtension.extensionType()` | Use concrete extension types for dispatch and `ordinal()` for the on-chain type ID. |
| `RpcEncoding.base58` | Request `RpcEncoding.base64`, or `base64_zstd` where supported. |
| `Transaction.MAX_SERIALIZED_LENGTH` | Use the transaction's `exceedsSizeLimit()` method. |

## Signature verification

A signature is binary data. Decode a textual signature using its actual encoding
before passing it to the existing byte-array overload. For example, for a base58
signature:

```java
boolean valid = publicKey.verifySignature(message, Base58.decode(signatureBase58));
```

The removed overload encoded the signature string as UTF-8. That cannot generally
preserve an Ed25519 signature. The overloads accepting a `String` message remain.

## HMAC-SHA512

Initialize the retained factory's `Mac` explicitly:

```java
var mac = Hmac.hmacSHA512();
mac.init(new SecretKeySpec(key, "HmacSHA512"));
byte[] digest = mac.doFinal(data);
```

`SecretKeySpec` is in `javax.crypto.spec`. Handle or declare the checked
`InvalidKeyException` from `Mac.init`. This preserves the key/data roles of the
removed overload in 25.10.0. Older versions of that overload used the second
argument as the key; consumers preserving that historical output must initialize
with that argument instead.

## Token-2022 extensions

The map keyed by `ExtensionType` is removed. Iterate `tokenExtensions()` and match
the concrete type, for example:

```java
for (var extension : token2022.tokenExtensions()) {
  if (extension instanceof TransferFeeConfig transferFees) {
    // Use transferFees here.
  }
}
```

`ordinal()` is the stable on-chain numeric type ID, not an index into a Java enum.
Known extensions retain their existing IDs and serialized layouts. Unknown type
IDs remain represented by `UnknownTokenExtension`, including their raw bytes, so
consumers should preserve them when reading and writing accounts.

## RPC encodings and transaction limits

Base58 account data in RPC responses remains readable. Removing the request enum
member does not remove the response decoder. `RpcEncoding.parseEncoding` now
returns `null` for `"base58"`, just as it does for other unsupported request names.

The single transaction-size constant could not describe every transaction format.
It carried plain `@Deprecated` in 25.10.0, without `forRemoval = true`, so builds
that checked only removal warnings did not receive advance notice of this removal.
`exceedsSizeLimit()` applies the built-in transaction's limit: 1,232 bytes for
legacy/v0 and 4,096 bytes for v1. Its interface default retains the 1,232-byte
compatibility limit for third-party implementations. A transaction fitting its
format's size limit does not imply that the destination cluster accepts that format.

## Newly deprecated transaction signing APIs

These methods remain available with their existing behavior for this release, but
are now marked `@Deprecated(forRemoval = true)`.

The `SequencedCollection<Signer>` overloads of `sign` and `signAndBase64Encode` sign
positionally. A `List<Signer>` selects these overloads even when the caller expects
the by-key behavior of `sign(Collection<Signer>)`.

Use the explicit names to choose the behavior:

```java
tx.signInOrder(signers); // Preserves positional signing; supply message signer order.
tx.signByKey(signers);   // Matches required signer keys, regardless of list order.
```

Both families include blockhash and Base64 conveniences, such as
`signInOrderAndBase64Encode(recentBlockHash, signers)` and
`signByKeyAndBase64Encode(recentBlockHash, signers)`. The static raw-buffer helpers
use `Transaction.signInOrder(...)` and `Transaction.signInOrderAndBase64Encode(...)`.
Transaction-aware positional overloads retain count validation without checking each
signer's key against its slot; the explicit-offset helper trusts the supplied spans.
By-key signing validates the complete assignment before writing signatures.

The existing single-signer, explicit-index, and `Collection<Signer>` overloads remain
undeprecated. Named positional aliases delegate to the corresponding existing overload,
including custom convenience overrides. New instance methods have defaults, so existing
`Transaction` implementations do not need additional method overrides.

Migrate callers before the deprecated overload is removed. A recompiled `tx.sign(list)`
would then select the retained `sign(Collection<Signer>)` overload and change to by-key
signing without a compilation error. `signInOrder` makes the positional choice durable.

Implementors must still provide `sign(SequencedCollection<Signer>)` in this release:
it remains abstract and the new defaults preserve dispatch to existing overrides.
Implementing or overriding a removal-deprecated method can produce a compiler removal
warning; use a method-level `@SuppressWarnings("removal")` for that compatibility
implementation. At eventual removal, move its positional implementation to
`signInOrder(SequencedCollection<Signer>)`, and move any overridden positional
conveniences to their corresponding named methods. Do not drop the old overrides
while supporting releases that still require them.

The RPC conveniences taking a `SequencedCollection<Signer>` also sign positionally.
To submit a transaction signed by key through the existing RPC API, use:

```java
rpcClient.sendTransaction(tx.signByKeyAndBase64Encode(recentBlockHash, signers));
```
