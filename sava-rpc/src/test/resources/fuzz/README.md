# Seed corpora

Each directory here is a fuzz target's committed seed corpus (`seedCorpus` in
`sava-rpc/build.gradle.kts`), replayed on every `check` by a plugin-generated
`<Harness>SeedReplayTest` in the harness's package — so the corpus cannot rot
between fuzz runs, and under PIT the replay participates as a killer. New
seeds, including minimized fuzz findings, replay automatically; a fuzz finding
is only closed by a committed seed here **plus** a named regression test.

This file lives next to the corpus directories, never inside one: every file
inside a corpus directory is fed to the harness as a seed.

`responses` and `ws` are **bootstrap** corpora (see sava-core's fuzz README for
the bootstrap/regression distinction): a JSON-RPC envelope with a method or result
body a parser accepts — let alone a subscription id matching the harness's
confirmed subscriptions, or a base64 account payload — is far more structure than
a from-scratch mutator assembles, so the seeds buy coverage as well as being the
regression home for findings. `httpBody` is a **regression** corpus: any input of
five or more bytes is valid and the harness synthesises the gzip itself, so a
mutator reaches every mode from scratch in seconds; its seeds buy deterministic
`check` replay and a landing place for findings, not coverage.

## `httpBody` — [JsonHttpClientBodyFuzz](../../java/software/sava/rpc/json/http/client/JsonHttpClientBodyFuzz.java)

The first five bytes select the body mode, encoding-header shape, Content-Length
shape, maximum stream read size, and concatenated-member split point. The remaining
bytes are the original payload, capped at 8 KiB. The harness generates gzip from
that payload, keeping decompressed sizes bounded independently of fuzz mutations.

Plain bodies, single gzip members, and concatenated gzip members must decode to
the original payload through both `byte[]` and fragmented `InputStream` routes.
The malformed modes corrupt the gzip magic or checksum, or remove trailer bytes;
both routes must fail with `UncheckedIOException`. Missing, extreme, or malformed
Content-Length hints must not change these results. Streams are finite and report
their remaining bytes honestly through `available()`, which JDK 26 and 27 consult
before probing for a following gzip member (the 25.0.2 reader probes
unconditionally); this target does not model network timing or stalls.

The seeds select each mode and include empty payloads, binary data, repeated,
comma-folded, and `x-gzip`-aliased encoding headers, size-hint boundaries, and a
payload larger than the minimum inflate buffer. An empty *payload* is encoded as
a valid gzip member. An empty *wire body* marked gzip has different published
behavior for byte arrays and streams; that distinction is pinned in
`JsonHttpClientBodyTests`, outside the round-trip oracle.

## `responses` — [SolanaRpcResponseFuzz](../../java/software/sava/rpc/json/http/client/SolanaRpcResponseFuzz.java)

Byte 0 routes the body to a parser family, byte 1 picks the http status, the
rest is the response body verbatim. One seed per notable family, each a
well-formed envelope its parser accepts in full:

- `account_info` — a base64 account with `rentEpoch` at u64 max: the value
  envelope, context parsing, and encoded-data decoding in one.
- `token_accounts` — a `pubkey`/`account` list entry whose 165-byte body the
  token-account factory actually parses.
- `transaction` — a `getTransaction` result: meta, balances, and the encoded
  transaction tuple.
- `block_accounts` — selector byte `0x03` (`Block::parse`): a `getBlock` result
  served with `transactionDetails` `accounts`, the header of the devnet
  4.4.0-beta.1 block in `rpc_response_data/getBlock-accounts-devnet-4.4.0-beta.1.json`
  over two hand-reduced entries. The first keeps a real v0 transaction's
  signatures, version, fee and two of its annotated `accountKeys` (one
  `transaction` and one `lookupTable` source); its balances are cut to those
  two keys with the second substituted, and its token balances are emptied.
  The second is synthetic —
  `"meta":null` beside `"version":"legacy"` and a key without `source`, a
  pairing agave never sends — so the seed also reaches the null-meta,
  string-version and absent-source branches of `BlockTx` and `TxAccountKey`.
- `tx_simulation` — logs, return data, and nullable account/inner-instruction
  fields.
- `vote_accounts` — `current`/`delinquent` with a nested `epochCredits`
  matrix.
- `vote_accounts_alpenglow_marker` — the post-activation shape mainnet returned on
  2026-10-24: the Alpenglow migration marker (three `18446744073709551615` values) inside
  the five-entry window, the migration epoch on both sides of it with credits continuous
  across it, and a staked delinquent account whose window keeps the marker. This is the
  input that broke the signed reader before sava-rpc 25.11.2.
- `sig_statuses` — a status list containing a `null` entry, the shape that
  trips list parsers that assume objects.
- `latest_blockhash`, `supply`, `epoch_info`, `cluster_nodes` — the remaining
  value/result envelope families, each with their nullable fields exercised.
- `leader_schedule` — the real `getLeaderSchedule` fixture: a large
  key-to-slot-array map.
- `node_health_behind` — the REST-style error body with `numSlotsBehind` data.
- `error_503` — an error envelope under a non-2xx status: the envelope gate
  must let the more specific error object win over the status code.

## `ws` — [SolanaJsonRpcWebsocketFuzz](../../java/software/sava/rpc/json/http/ws/SolanaJsonRpcWebsocketFuzz.java)

A 12-byte header carves the message into fragments (count, per-fragment
buffer flavor, terminal flag, split points) and its last byte's low bit
switches subscribe batching on (layout in the harness javadoc); the rest is
the message text. The harness confirms subscriptions with ids
11/22/33/44/66/77/55, then registers account subscriptions under request ids
9 and 10 and sends them in one maintenance pass without answering them, so
the input finds both in flight; after the input, unless the input replaced
the connection, it runs one more pass, which sends whatever the input made
due. These seeds dispatch to the confirmed subscriptions:

- `account_notification`, `program_notification`, `slot_notification`,
  `root_notification` — one whole array-backed frame per dispatch arm.
- `signature_received` — the `receivedSignature` value, the one signature
  result that must *not* remove the server-side subscription.
- `block_generic` — a `blockNotification` routed through the generic
  subscription's parser rather than a built-in channel.
- `error_response` — a JSON-RPC error envelope, the exception-subscriber
  path. Its `"id":9` names the first request in flight and its -32000 code
  blames the server's condition, not the request, so it is also a correlated
  refusal: request 9 is ready again on the stamp it went out with, which the
  pass after the input leaves inside the resend window
  (`aTransientRefusalIsReadyAgainOnItsCadence`).
- `unknown_sub` — a notification for an unconfirmed id: the automatic
  un-subscription reply.
- `logs_fragmented` — a logs notification split in thirds across
  array-backed, array-less, and positioned buffers.
- `dangling_fragment` — ends without `last=true`: the harness's flush must
  recover the reassembly state machine.
- `large_fragmented` — over 4096 chars across four fragments, sliced flavor
  included: the only seed that reaches the reassembly buffer's growth
  arithmetic, whose replay keeps that path inside `check`.

The batching bit builds the engine with a 4,096-byte subscribe batch cap,
Agave's inbound message limit, and the engine then reads an inbound JSON
array element by element as a batch response, each element handled as that
envelope arriving alone; with the bit clear, as in the seeds above, an array
is one unparseable message. The bit batches the harness's own requests too:
the preamble's typed subscribes go out as one batch message and the generic
one alone, in the adoption pass, and requests 9 and 10 as one more batch
message, which the engine ledgers as a batch awaiting its answer, so each of
these inputs also drives the outbound composer. These seeds set it. Their
answers name requests the preamble already confirmed (ids 3 and 4) or
requests it never minted (99), so none correlates and subscription 11 is
untouched; each shape has a named test in `BatchResponseDispatchTests`:

- `batch_mixed_response` — a `true` un-subscription acknowledgement, a
  -32602 rejection and a confirmation in one array: each element is
  classified as its own message would be
  (`aMixedArrayClassifiesEachElementLikeItsOwnMessage`).
- `batch_fragmented` — the same array split in thirds across array-backed,
  array-less, and positioned buffers, the cuts inside elements: the
  reassembly route into element dispatch
  (`aFragmentedArrayDispatchesEachElementOnce`).
- `batch_malformed_element` — a bare number and a confirmation with a string
  result after a valid acknowledgement: each malformed element is contained
  alone and the reader steps over it to the next
  (`aMalformedElementIsContainedAndTheRestDispatch`).
- `batch_empty` — `[]`, which Agave never sends: an array of no elements
  changes nothing (`anEmptyInboundArrayChangesNothing`).
- `batch_notification_element` — notifications for subscription 11 and for an
  unconfirmed id beside a confirmation: refused inside an array, so neither is
  delivered nor cancelled (`aNotificationInsideABatchResponseIsDropped`).

The remaining seeds answer requests 9 and 10; each shape has a named test in
`ReadySetTests`, `BatchResponseDispatchTests` or
`BatchRejectionFallbackTests`:

- `inflight_grant` — batching on: one array granting both requests, as
  subscriptions 88 and 89; each grant is installed and takes its request out
  of the pending map (`anArrayOfConfirmationsInstallsEveryGrant`).
- `inflight_refusal` — batching on: Agave's -32603 "Subscription refused" for
  request 9 beside a grant of request 10. The refusal concludes request 9's
  attempt and re-arms it on the stamp it went out with, so it is ready again
  and the pass after the input leaves it inside the resend window
  (`aTransientRefusalIsReadyAgainOnItsCadence`).
- `inflight_refusal_then_grant` — batching on: the same refusal of request 9,
  then a grant of request 9 in the same array; the grant must take the
  re-armed request out of the ready set as well as the pending map
  (`aGrantAfterATransientRefusalIsNotResent`).
- `inflight_rejection` — batching on: a -32602 "Invalid params" for request 9
  beside a grant of request 10. A code that blames the request concludes the
  attempt, re-arms the request and then retires it from both sets, freeing
  its registry slot (`aRequestDefectLeavesBothSetsAndFreesTheKey`).
- `inflight_collision` — batching on: request 9 granted subscription 11, which
  the canonical account registration owns for a different request, then a
  refusal of request 10. One id for two different requests replaces the
  connection by design and ends the array, so the refusal is never
  dispatched; the harness checks that replacement and that the canonical
  registration is still held (`anElementRetiringTheConnectionStopsTheRest`).
- `batch_null_id_error` — batching on: Agave's verbatim answer to a message
  it could not read at all, a -32700 parse error with `"id":null`, arriving
  whole: the one error that may release a batch. The batch of requests 9 and
  10 is the oldest outstanding and nothing sent before it is unanswered, so
  the release concludes both attempts, re-arms both at once and refuses
  batching; the pass after the input re-sends each alone, and the error still
  reaches the exception subscribers
  (`aNullIdErrorAnsweringABatchReSendsItsRequestsAlone`).
- `null_id_error_unbatched` — the same answer with batching off. Requests 9
  and 10 went out as plain messages, which are never ledgered, so it releases
  nothing, re-arms nothing and refuses nothing: the input for the harness's
  batching-off check, which requires exactly that. With batching on,
  `aNullIdErrorWithNoBatchOutstandingReleasesNothing` pins the same arm for
  plain messages.
