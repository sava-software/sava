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
- `tx_simulation` — logs, return data, and nullable account/inner-instruction
  fields.
- `vote_accounts` — `current`/`delinquent` with a nested `epochCredits`
  matrix.
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
buffer flavor, terminal flag, split points — layout in the harness javadoc);
the rest is the message text. The harness confirms subscriptions with ids
11/22/33/44/66/77/55 before feeding the input, and these seeds dispatch to
them:

- `account_notification`, `program_notification`, `slot_notification`,
  `root_notification` — one whole array-backed frame per dispatch arm.
- `signature_received` — the `receivedSignature` value, the one signature
  result that must *not* remove the server-side subscription.
- `block_generic` — a `blockNotification` routed through the generic
  subscription's parser rather than a built-in channel.
- `error_response` — a JSON-RPC error envelope, the exception-subscriber
  path.
- `unknown_sub` — a notification for an unconfirmed id: the automatic
  un-subscription reply.
- `logs_fragmented` — a logs notification split in thirds across
  array-backed, array-less, and positioned buffers.
- `dangling_fragment` — ends without `last=true`: the harness's flush must
  recover the reassembly state machine.
- `large_fragmented` — over 4096 chars across four fragments, sliced flavor
  included: the only seed that reaches the reassembly buffer's growth
  arithmetic, whose replay keeps that path inside `check`.
