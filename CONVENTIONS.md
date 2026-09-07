# API conventions

Behaviours that recur across this codebase and are not guessable from a
signature. Read this before writing assertions against an API you have not used
here — several of these were found by writing the *reasonable* assertion and
watching it fail.

None of it is a bug list. Where a convention is surprising it is documented at
the declaration too; this file exists so the family is visible in one place.

## How absence is represented — five different ways

There is no single convention, and the differences are deliberate. Picking the
wrong one silently misattributes data rather than failing.

| API | A missing thing is… | Detect it with |
| --- | --- | --- |
| `getAccounts(...)` | a `null` entry holding its slot | `list.get(i) == null` |
| `getMultipleAccounts(...)` | **omitted**, so later entries shift | you cannot from the list shape — dispatch on each entry's `pubKey()`, or use `getAccounts` for indexed correlation |
| `getAccountInfo(...)` | a non-null `AccountInfo` with empty fields | `owner() == null` |
| `getSignatureStatuses(...)` | a sentinel `TxStatus` in the map | `status.nil()` |
| Token-2022 extension authorities | a **non-null** all-zero `PublicKey` | `PublicKey.NONE.equals(key)` |

The two account-list families send a **byte-identical request** and differ only
in the parser, so nothing at the call site hints at it. Correlating results back
to the keys you passed by index is only safe with `getAccounts`.

`getAccountInfo` returning a hollow record means `if (info == null)` as an
"account does not exist" check is *always false*. This is long-standing
published behaviour and is not being changed — read `owner()` or `data()`.

Every optional address inside a Token-2022 extension is a `MaybeNull<Address>` on
the wire (`OptionalNonZeroPubkey` in older revisions, `zeroableOption` in the
Anchor IDL): the field is always present and absence is 32 zero bytes, not a
discriminant. sava decodes that faithfully, so `MintCloseAuthority.closeAuthority`,
both `TransferFeeConfig` authorities, `PermanentDelegate.delegate`,
`TransferHook.authority`/`programId`, `InterestBearingConfig.rateAuthority`,
`ConfidentialTransferMint.authority`/`auditorElGamalKey`,
`ConfidentialTransferFeeConfig.authority`, both components of `MetadataPointer`,
`GroupPointer` and `GroupMemberPointer`, `TokenGroup.updateAuthority`,
`TokenMetadata.updateAuthority`, `PausableConfig.authority`,
`PermissionedBurnConfig.authority` and `ScaledUiAmountConfig.authority` are
**never `null`** — an absent one equals `PublicKey.NONE`. `null` checks on them
are always false; the reverse mistake treats an unset authority as a real key and
happily encodes a transaction naming the all-zero address.

## Unsigned longs and sentinel zeros

Slots, lamports and token amounts are `u64` on the wire and `long` in Java, so:

- **A negative `long` is the top half of the range**, not an error. `-1L`
  serialises as `18446744073709551615`. `Long.toUnsignedString` /
  `new BigInteger(1, bytes)` appear throughout for this reason.
- **`0` is often a sentinel for "unset"**, not a real value.
  `getProgramAccounts(..., long minContextSlot, ...)` omits the field entirely
  when it is `0`; `dataSliceLength(offset, 0)` means "whole account". Use the
  `BigInteger` overloads when you need to express a literal zero.

## Argument-order and flag traps

- `ProgramAccountsRequest.Builder.dataSliceLength(offset, length)` takes the
  **offset first** despite the name. Calling it backwards returns the wrong
  window rather than failing.
- `sendTransaction(tx, skipPreFlight)` selects a whole *family*, not a flag: it
  also changes the `maxRetries` default (0 when skipping, 1 otherwise) and the
  preflight commitment. Use the three-argument overload to pin retries.
- `SolanaRpcClientBuilder.compressResponses()` composes with a previously set
  `extendRequest` (it silently replaced it until 2026-07-21). `extendRequest`
  itself is still a plain setter: calling it *after* `compressResponses()`
  replaces everything, compression header included.
- Both `simulateTransaction` families default `replaceRecentBlockhash` to
  `true`, so a simulation does not fail on an expired blockhash by default.
- `Transaction.sign(List<Signer>)` selects the `SequencedCollection` overload and signs
  **positionally**; the first element writes the first required-signature slot even when
  its public key belongs to another slot. `sign(Collection<Signer>)` validates the complete
  by-key assignment before signing. The positional overload is deprecated: use
  `signInOrder(signers)` to preserve it, or `signByKey(signers)` to match signers by public
  key regardless of the collection type. Neither named method requires a cast for lists.

## Byte-array ownership

`PublicKey.createPubKey(bytes)` and `Discriminator.createDiscriminator(bytes)` retain
their input arrays. Callers that will reuse or mutate the source can pass `bytes.clone()`.
The ranged discriminator factories copy, and factory-created discriminators return a
copy from `data()`.

For public keys, `copyByteArray()` returns an independent copy. `toByteArray()` may
return shared storage, so callers must copy before modifying it. Sharing depends on
the implementation: a lookup-table view copies its key slice. Mutating retained
public-key bytes can leave cached Base58 and hash-code values stale.

## Wire field names vs Java accessors

Where a response record renames a field, cross-referencing the Solana RPC docs
misleads. This table is the reference; the records themselves are left
unannotated, since the mapping is clear enough once you know to look:

| RPC field | Java accessor |
| --- | --- |
| `byIdentity` | `BlockProduction.leaderInfoMap()` |
| `identity` | `Identity.identityKey()` |
| `pubkey` | `ClusterNode.publicKey()` |

Read the record header before writing assertions — most accessor mistakes are
just this.

## Test harnesses

Two harnesses already exist; neither is obvious from the outside.

**RPC round trips** — extend `RpcRequestTests` (sava-rpc). It starts a local
`HttpServer`, and `registerRequest(expectedRequest, response)` both asserts the
outgoing body and stubs the reply. The JSON-RPC `id` is stripped before
comparison, so expected bodies can hard-code `"id":1`. This is the cheapest way
to cover an RPC method: one `registerRequest` plus one call.

Requests are queued FIFO across a test, so a test that fails mid-way can leave
entries behind and confuse the *next* assertion — read the first failure in a
run, not the last.

**WebSocket** — construct `SolanaJsonRpcWebsocket` directly and drive
`onOpen` / `onText` with a `RecordingWebSocket`. Subscribe *before* the `onOpen`
you assert on: `queueSubscription` only queues and signals, and of the two things
that flush the queue — a background thread started in the constructor, and
`onOpen` — only `onOpen` is synchronous with the test. The dedicated
`subscriptionResendDelay` controls retry and unanswered-request deadlines; set
it large when a test needs to keep the background thread away from pending
subscription work.
