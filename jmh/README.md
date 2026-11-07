# JMH Benchmarks

Benchmarks for `sava-core` and `sava-rpc` hot paths. Currently: `Base58Bench`,
which prices the Base58 decode rewrite (limb-based multiply-add, five base-58
digits per pass, plus fixed-size decode-into overloads) against the
per-output-byte `divMod` implementation it replaced, copied verbatim into the
benchmark as the `*_old` rows; and `WsSubscribeBench`, which prices the
websocket engine's subscribe pipeline (a burst of registrations, call by call
or inside one registration scope, their replay after the peer drops the
connection, and a registration made while earlier requests still await an
answer) through its public API against a stub transport.

## Running

This directory is a standalone Gradle build that includes the library build
from `..`, so benchmarks always run against the local sources.

```sh
cd jmh
../gradlew jmh                          # everything, quick-look defaults (1 fork)
../gradlew jmh -PjmhFork=3              # decision-grade
../gradlew jmh -PjmhIncludes=chars      # name-regex subset
```

Configuration comes from the shared `software.sava.build.feature.jmh`
convention plugin, pinned to a published sava-build release in `build.gradle.kts`
(`-PsavaBuildLocalRepo` redirects it to a local test publication; see
`../AGENTS.md`); every default is overridable per invocation (`-PjmhFork`, `-PjmhIncludes`,
`-PjmhWarmupIterations`, `-PjmhWarmup`, `-PjmhIterations`,
`-PjmhTimeOnIteration`, `-PjmhFailOnError`, `-PjmhJvmArgsAppend`, and
`-PjmhVersion`, the JMH harness: 1.37 by default, and the only version whose
whole dependency graph the verification policy covers). The forked
benchmark JVMs run the long-running-service flag set (generational ZGC,
compact object headers, pinned pre-touched heap, `-XX:+PerfDisableSharedMem`).
Each run that writes results has its raw output archived timestamped under
`jmh-results/`, and `build/results/jmh/results.txt` is then re-rendered as the
merge of all archived runs (rows are keyed by benchmark name plus parameter
values, and the newest archived row wins per key). A run that fails,
or one JMH ends after listing or printing help, archives nothing; after
`clean`, a failed run leaves an empty `results.txt` until a run writes results.

**Compare with at least `-PjmhFork=3`, in isolation.** Single-fork scores
swing 10–20% on JIT inlining luck alone; treat any row with an error above
~10% of its score as contaminated and re-run it alone. `Base58Bench`'s
`@Setup` cross-checks that all its variants produce identical output;
`WsSubscribeBench` compares no variants, and its fixture throws instead on an
engine error, a refused registration or a missed deadline. The default
`failOnError` turns a disagreement or any such throw into a hard failure. (It has
reached JMH since sava-build 21.8.3, whose jmh-gradle-plugin 0.7.4 passes
`-foe true`; 0.7.3 passed `-foe 1`, which JMH read as false, so no failure ever
reached the task before.) What that costs: the first benchmark error, a fork that dies
included, ends the run and fails the `jmh` task, and the run archives
nothing, not even the benchmarks that finished before the error. JMH writes
its results file after the last benchmark, so those scores survive only in
the console output; send a long run's output to a file. Under
`-PjmhFailOnError=false` the task passes and the run is archived: a late
failure posts a short row (`Cnt` below forks × iterations) that replaces the
full row in the merge, a dead fork or a trial `@Setup` failure posts no row,
so the old row stays, and only the log's `<failure>` or
`<forked VM failed with exit code N>` says so.

## Base58Bench

Two workloads, the only lengths Solana decodes in practice: 32 bytes
(public keys and block hashes, ~44 chars — the shape behind
`PublicKey.fromBase58Encoded` and every account key in a parsed RPC response)
and 64 bytes (transaction signatures, ~88 chars). 1024 uniform random inputs
per length; scores are ns per decode.

- `string_old` / `chars_old` — the replaced implementation: codepoint
  iteration, exception-driven charset validation, one `divMod` pass over the
  remaining digits per output byte (O(n²) in digit count).
- `string_new` / `chars_new` — `Base58.decode` as shipped.
- `string_into` / `chars_into` — the fixed-size `decode(input, out)`
  overloads, which skip the final right-size-and-copy allocation.
  `string_into` vs `string_old` is the `PublicKey.fromBase58Encoded`
  before/after; `chars_into` is the RPC-parse shape (`applyChars` feeding the
  parser's buffer straight to `decode(char[], from, len, out)`).
- `bytes_new` / `bytes_into` — the `byte[]` ASCII overloads, the shape a
  json-iterator byte-span value hook would deliver.

### Results

Measured 2026-07-14 on an Apple Silicon macOS box, JMH 1.36, `-PjmhFork=3`
(24 samples per row), JDK 25, default service flag set. Treat the ratios as
the signal, not the absolute numbers. Both tables in this file are JMH 1.36's,
the default harness until sava-build 21.8.3 moved it to 1.37: a 1.37 row is a
different harness, so re-measure both sides before comparing against them. A
`-PjmhVersion=1.36` run is refused until the three policy entries only JMH
1.36's graph needed come back from git history, as
`../gradle/verification/README.md` describes.

| Workload | old ns/op | new ns/op | into ns/op | Speedup |
|---|---|---|---|---|
| 32-byte keys, String | 1610.9 ± 3.3 | 83.7 ± 1.2 | 78.2 ± 0.4 | **19.3×, 20.6× into** |
| 32-byte keys, char[] | 1603.8 ± 12.9 | 83.8 ± 2.9 | 70.5 ± 0.7 | **19.1×, 22.8× into** |
| 64-byte signatures, String | 7803.2 ± 9.8 | 183.5 ± 0.3 | 185.0 ± 1.2 | **42.5×** |
| 64-byte signatures, char[] | 7777.0 ± 14.0 | 172.6 ± 1.7 | 170.8 ± 1.7 | **45.1×** |

The gap widens with length because the old conversion is quadratic in digit
count (each output byte re-scans the remaining digits) while the limb loop
does a fifth of the passes over quarter-length data. The `into` overloads add
a further 7–16% at 32 bytes — one less allocation and copy per decode, the
shape `PublicKey.fromBase58Encoded` and the RPC key parse paths now use — and
are a wash within error at 64 bytes, where the conversion itself dominates.

### byte[] vs char[] input

A dedicated same-session control run on the same harness (`-PjmhFork=3`,
`-PjmhIncludes=bytes_new,bytes_into,chars_new,chars_into`, 24 samples per
row) prices the `byte[]` overloads against the char path:

| Workload | chars ns/op | bytes ns/op |
|---|---|---|
| 32-byte keys, into | 70.1 ± 0.9 | 70.1 ± 0.8 |
| 64-byte signatures, into | 167.1 ± 0.6 | 168.4 ± 1.3 |
| 32-byte keys, alloc | 82.6 ± 3.8 | 85.0 ± 2.7 |
| 64-byte signatures, alloc | 169.8 ± 0.3 | 169.6 ± 0.5 |

A dead tie on every pairing: the decode itself is indifferent to input width
at these lengths. The value of a json-iterator byte-span hook is therefore
entirely on the parser side — skipping the byte-to-char widening copy
`BytesJsonIterator` performs before `applyChars` — not in the decoder. Any
end-to-end measurement of that hook belongs in json-iterator's jmh suite,
where both sides of the widening live.

## WsSubscribeBench

Prices `SolanaRpcWebsocket`'s subscribe pipeline, client side only: the peer is
a stub `WebSocket` handed to the engine through the public
`Builder.webSocketBuilder(WebSocket.Builder)` seam. Every `sendText` future
settles on the stub's own transport thread, which then feeds the matching
JSON-RPC confirmation into the engine's listener from that thread. That is not
the JDK transport's shape: JDK 25's `sendText` runs the send on the calling
thread and completes its future before it returns when the connection is
writable and no other thread is sending a frame on it; otherwise the future
completes later, on another thread. In the first case every send a maintenance
pass chains, and each `onSub` callback, runs inside that pass, under the
engine's lock. The stub completes each send on its transport thread, never on
the caller's, and that thread then runs the send's `onSub` callbacks and the
sends chained behind it, outside the pass, unless it settled the future before
the engine attached its callback: they then run on the engine's thread, inside
the pass, as on the JDK. So these rows are no measurement of that hold; they
include it only when that race goes the JDK's way. A batch request (a JSON
array) is answered with an array of confirmations in request order, as Agave
answers it. Scores
are milliseconds per burst (single-shot mode, a fresh engine per invocation,
since registrations are durable and a second burst on the same instance would
be all duplicates).

- `burst` — one thread registers `subscriptions` accounts, one
  `accountSubscribe` call at a time; the sample ends when every registration has
  been transmitted and its confirmation installed on the handle its `onSub`
  callback delivered.
- `burstScoped` — the same burst made inside one
  `SolanaRpcWebsocket.registerAll(Consumer)` scope, so its registrations wake
  the maintenance loop once, when the scope returns, instead of once per call.
  The row prices that one wake for the whole burst and, at a `batchBytes` of
  4096, arrays packed to the cap. The scope also holds the sends back: unless
  the engine runs a pass of its own meanwhile (its check delay elapsing, or a
  pong), nothing goes out until the scope returns. `burst` against
  `burstScoped` is therefore the wakes and fuller arrays the scope saves, less
  the overlap of registering and sending that `burst` keeps.
- `replay` — an engine whose `subscriptions` registrations are confirmed is told
  the peer closed, and its `onClose` handler reconnects; the sample runs from the
  close to the last re-confirmation on the replacement transport. The engine
  paces a reconnect's replay by the re-send window (a re-queued registration
  keeps its last-attempt stamp; see `Builder#subscriptionResendDelay`), so the
  fixture lets the window elapse, untimed, before the close: the row prices the
  replay work, not the documented wait.
- `backlog` — one registration at a time, each timed from its `accountSubscribe`
  call to its `onSub` callback (its frame sent and the send completed), on an
  engine that holds `pending` earlier registrations the stub peer never answers;
  average time in microseconds per registration. Each registration wakes the
  maintenance loop for one pass, so the row prices what a pass pays for the
  requests still awaiting an answer. One engine serves the whole trial (its
  re-send window, `BACKLOG_RESEND_DELAY_MILLIS`, outlasts any run, so the
  withheld requests neither fall due again nor reach the unanswered deadline)
  and every invocation registers a fresh key.

`burst`, `burstScoped` and `replay` take a second parameter, `batchBytes`, the
cap the fixture hands `Builder.subscribeBatchBytes(int)`. At 0, the engine's
default, every request is its own message. At 4096 (the benchmark's
`Burst#batchBytes` doc says where that value comes from), each maintenance
pass packs the subscribes it finds due into JSON-RPC batch arrays of at most
that many UTF-8 bytes, as many requests to an array as fit, and the stub
answers each array with one array; a pass that finds a single subscribe due
sends it as its plain object, never as an array of one. The rows fill their
arrays differently. A reconnect re-queues every registration at once, and the
fixture has waited out the re-send window, so the replay's first pass finds
them all due and every array is as full as the cap allows. The same holds for
`burstScoped`, whose registrations wait for the pass its scope's exit wakes.
`burst` registers outside a `SolanaRpcWebsocket.registerAll(Consumer)` scope,
and each call wakes the maintenance loop, so an array holds only the
registrations that queued since the previous pass, and a pass that finds just
one sends it plain. That row prices batching at the pace one registering
thread sets, partly filled arrays and plain requests mixed; `burstScoped`
prices it at its fullest. Against the stub an array costs one send and one
answer like any other message, so these rows price the client's side of
batching (fewer, larger messages to compose, send and dispatch), not the
per-message work batching saves a real server; the raw transport comparison at
the end of the results gives the scale of that. The backlog row keeps batching
off.

The re-send window is the benchmark's `RESEND_DELAY_MILLIS`, and the engine's
unanswered-request deadline is `SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR`
of those windows. A frame queued behind a whole burst in the
one-outstanding-send chain ages from its admission, so a machine on which a
burst takes longer than that deadline fails the row with an "unanswered"
engine error rather than reporting a score.

### Results

Measured 2026-11-06 on an Apple Silicon macOS box (10 cores), JMH 1.37,
`-PjmhFork=3` (24 samples per row and run), JDK 25.0.5, default service flag
set, in one session with nothing else building. "Before" is the engine as it
stood before its send loop admitted requests from a ready set instead of
visiting every pending one, and so before every later change to its subscribe
pipeline, registration scopes and batching included: the commit that added the
`backlog` row, run from a second worktree. "After" is the engine at the commit
that recorded this table; the thread-local read each registration has made
since the `registerAll` scope marker changed, nanoseconds against a
registration's tens of microseconds, was not re-measured. The runs alternated,
before then after: three times
for `burst`, `burstScoped` and `replay`, whose figures are the mean of the three
run scores with the widest of the three runs' intervals, and once for
`backlog`. Burst and replay scores are milliseconds per burst, backlog scores
microseconds per registration.

| Row | size | before | after, `batchBytes` 0 | after, `batchBytes` 4096 |
|---|---|---|---|---|
| `burst` | 1,000 | 9.2 ± 1.3 | 9.4 ± 1.1 | 9.4 ± 0.9 |
| `burst` | 10,000 | 48.5 ± 5.4 | 48.3 ± 5.8 | 51.8 ± 5.0 |
| `burstScoped` | 1,000 | | 6.7 ± 0.5 | 5.8 ± 0.4 |
| `burstScoped` | 10,000 | | 40.0 ± 2.3 | 37.4 ± 1.2 |
| `replay` | 1,000 | 8.6 ± 1.5 | 9.2 ± 1.5 | 8.8 ± 1.4 |
| `replay` | 10,000 | 34.3 ± 3.8 | 38.9 ± 3.3 | 36.3 ± 3.7 |
| `backlog` | 0 pending | 37.5 ± 1.2 | 38.0 ± 0.9 | |
| `backlog` | 2,000 pending | 38.3 ± 0.8 | 37.8 ± 0.8 | |
| `backlog` | 10,000 pending | 106.6 ± 7.2 | 36.8 ± 1.7 | |

A registration made while 10,000 requests await an answer now costs what one
made with nothing pending costs; before, it cost 2.8× as much, because every
maintenance pass visited every pending request, the awaiting ones included, and
a registration's own wake ran one such pass. A burst registered call by call is
unchanged. Against that call-by-call burst with batching off, the same burst
inside one `registerAll` scope is 17% (10,000) to 29% (1,000) faster with
batching off in the scope, and 23% to 38% faster with batching on in it. A
replay of 10,000 is about 13% slower with batching off and about 6%
slower with it on, 4.6 and 2.0 ms per reconnect, and the three runs agree (33.2,
33.7 and 35.9 ms before; 38.7, 39.2 and 38.8 after, batching off). A replay is
one pass over requests that are all ready, so it pays for the ready set, which
each request enters and leaves, and saves no visit; that is the likely cost, in
the range such bookkeeping accounts for, and it was not profiled. Batching on
moves these rows little, and costs a burst registered call by call a few
percent, where arrays go out partly filled: against the stub a message is one
send and one answer whatever it holds.

Single-shot rows are noisy. Most of the intervals above exceed the 10% this
README's rule allows, in every run, so the comparison rests on the alternating
runs agreeing and not on any one interval. Replay scores also move between
sessions: the previous day's session measured the same before engine at 2.1 and
18.4 ms and, later that day, at 12.8 and 45.8 ms, so only scores taken in one
session compare.

For scale, a loopback run of the raw JDK `WebSocket` against a local Agave test
validator (2026-11-05) confirmed 10,000 individually pipelined
`accountSubscribe` requests in about 165 ms and the same requests packed into
400 JSON-RPC batch arrays of at most 4,096 bytes in about 58 ms: the
per-message work batching saves is the server's, which these rows do not price.
