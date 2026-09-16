package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPInputStream;

import static java.lang.System.Logger.Level.DEBUG;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;

public abstract class JsonHttpClient {

  private static final System.Logger logger = System.getLogger(JsonHttpClient.class.getName());

  /// Read buffer for the gzip path. Only a chunk size — inflating is done by
  /// `readAllBytes`, which grows on its own — but [GZIPInputStream] allocates
  /// this buffer eagerly, and the size comes from a Content-Length the server
  /// controls. Left unclamped a provider sizes a client-side allocation at will:
  /// `Content-Length: 200000000` against a 31 byte body allocates 200MB.
  ///
  /// The bound stays deliberately tight because a bigger read buffer buys
  /// nothing: `readAllBytes` owns the growth, so this is only the inflate chunk
  /// size and it does not scale with the response. Swept against a real 5.7MB
  /// mainnet `getBlock` response, from both an in-memory and a chunked
  /// network-like source:
  ///
  ///   512B    9.6-10.3 ms   11.39 MB allocated
  ///   4KiB    8.6- 8.7 ms   11.39 MB
  ///   64KiB   8.5- 8.9 ms   11.45 MB
  ///   1MiB    8.7- 8.8 ms   12.44 MB
  ///
  /// Flat from 4KiB up, and above 64KiB allocation climbs because the buffer
  /// itself is allocated per request. The only real effect is a floor — 512
  /// bytes costs 12-20% — so [#MIN_GZIP_BUFFER] is a floor worth keeping and
  /// raising either bound has no measured upside.
  private static final int MIN_GZIP_BUFFER = 4_096;
  private static final int MAX_GZIP_BUFFER = 1 << 20;
  /// The largest request timeout the exchange deadline can double without overflowing a long.
  private static final Duration MAX_DOUBLED_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE >> 1);

  protected final URI endpoint;
  protected final HttpClient httpClient;
  /// Passed to the JDK as [HttpRequest.Builder#timeout]. On JDK 25 that only bounds the wait
  /// for the response headers; JDK 26 extends it over body consumption. The routes that read
  /// the body themselves also bound the whole exchange at twice this, the JDK 25 backstop:
  /// see [#withResponseDeadline].
  protected final Duration requestTimeout;
  protected final UnaryOperator<HttpRequest.Builder> extendRequest;
  protected final BiPredicate<HttpResponse<?>, byte[]> testResponse;

  protected JsonHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout,
                           final UnaryOperator<HttpRequest.Builder> extendRequest,
                           final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
    this.endpoint = endpoint;
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
    this.extendRequest = extendRequest == null ? UnaryOperator.identity() : extendRequest;
    this.testResponse = testResponse;
  }

  protected JsonHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout) {
    this(endpoint, httpClient, requestTimeout, null, null);
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponse(final Function<JsonIterator, R> parser) {
    return new GenericJsonResponseParser<>(parser);
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponse(final BiFunction<byte[], JsonIterator, R> parser) {
    return new GenericJsonBytesResponseParser<>(parser);
  }

  private static HttpRequest.Builder newJsonRequest(final URI endpoint, final Duration requestTimeout) {
    return HttpRequest
        .newBuilder(endpoint)
        .header("Content-Type", "application/json")
        .timeout(requestTimeout);
  }

  /// The exchange deadline for the routes that read the body themselves: on JDK 25 the JDK
  /// timer bounds only the headers to the timeout on the built request -- the one `extendRequest` may have
  /// replaced, not the client default -- and the body gets the same budget again. A request
  /// without a timeout (only an extender can produce one) falls back to the client default.
  // package-private for tests
  static long responseDeadlineNanos(final HttpRequest request, final Duration defaultTimeout) {
    return responseDeadlineNanos(request.timeout().orElse(defaultTimeout));
  }

  /// Twice the timeout, saturating at `Long.MAX_VALUE` instead of overflowing: `toNanos` throws
  /// past about 292 years and the doubling wraps negative past about 146, and a negative
  /// deadline would cancel every request on the spot.
  // package-private for tests
  static long responseDeadlineNanos(final Duration requestTimeout) {
    return requestTimeout.compareTo(MAX_DOUBLED_TIMEOUT) > 0
        ? Long.MAX_VALUE
        : requestTimeout.toNanos() << 1;
  }

  /// Bounds the whole exchange, body included, on JDK 25. There [HttpRequest.Builder#timeout]
  /// only bounds the wait for the response headers, and a body that stalled after the headers
  /// arrived used to keep a thread parked in `readAllBytes` on the input-stream body path with
  /// nothing to end it (six days, in one 2026-08-11 outage). The body is now accumulated by
  /// the JDK (`ofByteArray`), so nothing parks, and a scheduled cancellation closes the exchange
  /// -- stream included -- when the body is still outstanding at [#responseDeadlineNanos]: the
  /// future then fails with a `CancellationException` instead of pending forever. Completing
  /// the response cancels the scheduled task, which the delay scheduler unlinks at once, so a
  /// finished response is not retained until the deadline would have fired.
  ///
  /// JDK 26 extends the request timeout over body consumption itself (the timer stops when the
  /// body subscriber terminates), so there the JDK fails a stalled body with an
  /// `HttpTimeoutException` at one timeout and this cancellation never fires. Once the minimum
  /// runtime is 26 the whole mechanism can go; until then it is the JDK 25 backstop.
  // package-private for tests
  static <T> CompletableFuture<HttpResponse<T>> withResponseDeadline(final CompletableFuture<HttpResponse<T>> response,
                                                                      final ScheduledExecutorService scheduler,
                                                                      final long deadlineNanos) {
    // a block body: cancel(boolean) returns a value, and an expression lambda would bind the
    // Callable overload of schedule instead of the Runnable one
    final var cancellation = scheduler.schedule(() -> {
      response.cancel(true);
    }, deadlineNanos, TimeUnit.NANOSECONDS);
    response.whenComplete((_, _) -> cancellation.cancel(false));
    return response;
  }

  private <T> CompletableFuture<HttpResponse<T>> sendWithDeadline(final HttpRequest request,
                                                                  final HttpResponse.BodyHandler<T> bodyHandler) {
    return sendWithDeadline(request, bodyHandler, ForkJoinPool.commonPool());
  }

  // Package-private scheduler seam for submission and timer-lifecycle tests.
  final <T> CompletableFuture<HttpResponse<T>> sendWithDeadline(final HttpRequest request,
                                                                final HttpResponse.BodyHandler<T> bodyHandler,
                                                                final ScheduledExecutorService scheduler) {
    // Submit first. sendAsync throws synchronously when the client's executor rejects the
    // request, and a cancellation scheduled before that would sit on the scheduler until the
    // deadline with nothing to release it: one leaked timer per rejected attempt.
    final var response = httpClient.sendAsync(request, bodyHandler);
    return withResponseDeadline(response, scheduler, responseDeadlineNanos(request, requestTimeout));
  }

  /// A Content-Encoding field value is a comma-separated list of codings (RFC 9110 §8.4), and
  /// an intermediary may fold several field lines into one, so `identity, gzip` on one line
  /// must read like `identity` and `gzip` on two. A `gzip` element anywhere in the list --
  /// or its legacy alias `x-gzip`, which §8.4.1.3 says to treat as equivalent -- selects
  /// inflation whatever its position: a no-op coding such as `identity` beside it is ignored,
  /// and a list where gzip is not the outermost coding (`gzip, br`) is still handed to the
  /// inflater, which rejects the outer coding's bytes. Nothing else is decoded.
  private static boolean isGzipEncoded(final HttpResponse<?> response) {
    for (final var header : response.headers().allValues("content-encoding")) {
      if (listsGzip(header)) {
        return true;
      }
    }
    return false;
  }

  /// Whether `gzip` or `x-gzip` is one of the comma-separated elements of `codings`, ignoring
  /// case and the optional whitespace around each element. Scanned in place rather than split:
  /// the value is provider-sized, and this class does not let a header size an allocation.
  private static boolean listsGzip(final String codings) {
    final int length = codings.length();
    int start = 0;
    for (int i = 0; i <= length; ++i) {
      if (i == length || codings.charAt(i) == ',') {
        int end = i;
        while (start < end && codings.charAt(start) <= ' ') {
          ++start;
        }
        while (end > start && codings.charAt(end - 1) <= ' ') {
          --end;
        }
        final int span = end - start;
        if ((span == 4 || span == 6 && codings.regionMatches(true, start, "x-", 0, 2))
            && codings.regionMatches(true, end - 4, "gzip", 0, 4)) {
          return true;
        }
        start = i + 1;
      }
    }
    return false;
  }

  private static byte[] readBytes(final HttpResponse<?> response, final byte[] body) {
    if (body == null || body.length == 0) {
      return body;
    }
    if (JsonHttpClient.isGzipEncoded(response)) {
      try (final var gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(body), gzipBufferSize(body.length))) {
        return gzipInputStream.readAllBytes();
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    } else {
      return body;
    }
  }

  /// Clamps the declared Content-Length into a sane buffer size. Nothing about
  /// this header is trustworthy: it is server controlled, unrelated to the bytes
  /// actually sent, and need not even be a number. A value past int range used to
  /// narrow to a negative or zero buffer and escape as an IllegalArgumentException
  /// rather than the UncheckedIOException the rest of this path throws; a large
  /// in-range value used to allocate exactly that many bytes up front.
  ///
  /// Against the Solana RPC servers the header is simply never here: they
  /// compress on the fly, so the compressed length is not known when the headers
  /// are written and they omit it (HTTP/2) or fall back to chunked (HTTP/1.1).
  /// Verified with curl — an uncompressed `getSlot` returns `content-length: 44`
  /// on both HTTP versions, and the same request with `Accept-Encoding: gzip`
  /// returns none. Content-Length and gzip are mutually exclusive by
  /// construction for any server streaming its compression, so there is nothing
  /// to raise upstream; this call just falls through to [#MIN_GZIP_BUFFER].
  ///
  /// It is still read because a buffering proxy or CDN compresses fully before
  /// responding and can legally send both — which is exactly the untrusted,
  /// non-Solana-origin value the clamp exists to bound.
  ///
  /// @return a size between [#MIN_GZIP_BUFFER] and [#MAX_GZIP_BUFFER], falling
  /// back to the minimum when the header is absent or unparseable.
  /// The inflate buffer for a body already in memory. Its compressed length is exact rather
  /// than a header, but the measurements above still apply -- nothing over [#MAX_GZIP_BUFFER]
  /// inflates faster -- and without the clamp an 8 MiB compressed body would add an 8 MiB
  /// buffer to the 8 MiB it already holds.
  // package-private for tests
  static int gzipBufferSize(final int compressedLength) {
    return Math.clamp(compressedLength, MIN_GZIP_BUFFER, MAX_GZIP_BUFFER);
  }

  private static int gzipBufferSize(final HttpResponse<?> response) {
    final long contentLength;
    try {
      contentLength = response.headers().firstValueAsLong("Content-Length").orElse(MIN_GZIP_BUFFER);
    } catch (final NumberFormatException e) {
      logger.log(System.Logger.Level.DEBUG, "Ignoring unparseable Content-Length from {0}", response.uri());
      return MIN_GZIP_BUFFER;
    }
    return Math.clamp(contentLength, MIN_GZIP_BUFFER, MAX_GZIP_BUFFER);
  }

  private static byte[] readInputStream(final HttpResponse<?> response, final InputStream inputStream) {
    if (inputStream == null) {
      return null;
    }
    try {
      if (JsonHttpClient.isGzipEncoded(response)) {
        try (final var gzipInputStream = new GZIPInputStream(inputStream, JsonHttpClient.gzipBufferSize(response))) {
          return gzipInputStream.readAllBytes();
        }
      } else {
        return inputStream.readAllBytes();
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// Reads the response body, inflating a body marked with the supported gzip encoding.
  /// An empty byte-array body is returned unchanged even when marked gzip. An empty
  /// input stream marked gzip is instead parsed as gzip and fails with [UncheckedIOException].
  protected static byte[] readBody(final HttpResponse<?> response) {
    if (response instanceof ReadHttpResponse<?> readHttpResponse) {
      return readHttpResponse.readBody();
    } else {
      final var body = response.body();
      if (body instanceof byte[] bytes) {
        return JsonHttpClient.readBytes(response, bytes);
      } else if (body instanceof InputStream inputStream) {
        return JsonHttpClient.readInputStream(response, inputStream);
      } else if (body != null) {
        throw new IllegalArgumentException("Unsupported response body type: " + body.getClass());
      } else {
        return null;
      }
    }
  }

  public final URI endpoint() {
    return this.endpoint;
  }

  public final HttpClient httpClient() {
    return this.httpClient;
  }

  public final Duration defaultRequestTimeout() {
    return this.requestTimeout;
  }

  // GET methods

  protected final HttpRequest.Builder newRequest(final URI endpoint, final Duration requestTimeout) {
    return extendRequest.apply(newJsonRequest(endpoint, requestTimeout));
  }

  protected final HttpRequest.Builder newRequest(final String path, final Duration requestTimeout) {
    return newRequest(endpoint.resolve(path), requestTimeout);
  }

  protected final HttpRequest.Builder newRequest(final String path) {
    return newRequest(path, requestTimeout);
  }

  protected final HttpRequest.Builder newRequest(final URI endpoint) {
    return newRequest(endpoint, requestTimeout);
  }

  protected final HttpRequest.Builder newRequest(final Duration requestTimeout) {
    return newRequest(endpoint, requestTimeout);
  }

  protected final HttpRequest.Builder newRequest() {
    return newRequest(endpoint);
  }

  // POST/PUT methods

  private HttpRequest.Builder newRequest(final URI endpoint,
                                         final Duration requestTimeout,
                                         final String method,
                                         final HttpRequest.BodyPublisher bodyPublisher) {
    return extendRequest.apply(newJsonRequest(endpoint, requestTimeout).method(method, bodyPublisher));
  }

  protected final HttpRequest.Builder newRequest(final URI endpoint,
                                                 final String method,
                                                 final HttpRequest.BodyPublisher bodyPublisher) {
    return newRequest(endpoint, requestTimeout, method, bodyPublisher);
  }

  protected final HttpRequest.Builder newRequest(final Duration requestTimeout,
                                                 final String method,
                                                 final HttpRequest.BodyPublisher bodyPublisher) {
    return newRequest(endpoint, requestTimeout, method, bodyPublisher);
  }

  protected final HttpRequest.Builder newRequest(final String method,
                                                 final HttpRequest.BodyPublisher bodyPublisher) {
    return newRequest(endpoint, method, bodyPublisher);
  }

  protected final HttpRequest.Builder newRequest(final String path,
                                                 final Duration requestTimeout,
                                                 final String method,
                                                 final HttpRequest.BodyPublisher bodyPublisher) {
    return newRequest(endpoint.resolve(path), requestTimeout, method, bodyPublisher);
  }

  protected final HttpRequest.Builder newRequest(final String path,
                                                 final String method,
                                                 final HttpRequest.BodyPublisher bodyPublisher) {
    return newRequest(path, requestTimeout, method, bodyPublisher);
  }

  protected final HttpRequest newPostRequest(final URI endpoint, final Duration requestTimeout, final String body) {
    logger.log(DEBUG, body);
    return newRequest(endpoint, requestTimeout, "POST", ofString(body)).build();
  }

  protected <R> Function<HttpResponse<?>, R> wrapResponseParser(final Function<HttpResponse<?>, R> parser) {
    if (testResponse == null) {
      return parser;
    } else {
      return response -> {
        final byte[] body = readBody(response);
        return testResponse.test(response, body)
            ? parser.apply(new ReadHttpResponse<>(response, body))
            : null;
      };
    }
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final URI endpoint,
                                                           final Function<HttpResponse<?>, R> parser,
                                                           final Duration requestTimeout,
                                                           final String body) {
    return sendWithDeadline(newPostRequest(endpoint, requestTimeout, body), ofByteArray())
        .thenApply(wrapResponseParser(parser));
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final Function<HttpResponse<?>, R> parser,
                                                           final Duration requestTimeout,
                                                           final String body) {
    return sendPostRequest(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final Function<HttpResponse<?>, R> parser,
                                                           final String body) {
    return sendPostRequest(parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final URI endpoint,
                                                           final Function<HttpResponse<?>, R> parser,
                                                           final String body) {
    return sendPostRequest(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendGetRequest(final Function<HttpResponse<?>, R> parser,
                                                          final String path) {
    return sendWithDeadline(newRequest(path).build(), ofByteArray())
        .thenApply(wrapResponseParser(parser));
  }

  protected final <R> CompletableFuture<R> sendGetRequest(final URI endpoint,
                                                          final Function<HttpResponse<?>, R> parser) {
    return sendWithDeadline(newRequest(endpoint).build(), ofByteArray())
        .thenApply(wrapResponseParser(parser));
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                 final Function<HttpResponse<?>, R> parser,
                                                                 final Duration requestTimeout,
                                                                 final String body) {
    return sendWithDeadline(newPostRequest(endpoint, requestTimeout, body), ofByteArray())
        .thenApply(parser);
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final Function<HttpResponse<?>, R> parser,
                                                                 final Duration requestTimeout,
                                                                 final String body) {
    return sendPostRequestNoWrap(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final Function<HttpResponse<?>, R> parser,
                                                                 final String body) {
    return sendPostRequestNoWrap(parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                 final Function<HttpResponse<?>, R> parser,
                                                                 final String body) {
    return sendPostRequestNoWrap(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendGetRequestNoWrap(final Function<HttpResponse<?>, R> parser,
                                                                final String path) {
    return sendWithDeadline(newRequest(path).build(), ofByteArray())
        .thenApply(parser);
  }

  protected final <R> CompletableFuture<R> sendGetRequestNoWrap(final URI endpoint,
                                                                final Function<HttpResponse<?>, R> parser) {
    return sendWithDeadline(newRequest(endpoint).build(), ofByteArray())
        .thenApply(parser);
  }

  /// The body-handler routes carry only the JDK's own request timeout (headers on JDK 25,
  /// body consumption too on JDK 26): the caller's handler owns the body, which may
  /// legitimately stream for longer than any budget this client would pick.
  protected final <H, R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                    final HttpResponse.BodyHandler<H> bodyHandler,
                                                                    final Function<HttpResponse<H>, R> parser,
                                                                    final Duration requestTimeout,
                                                                    final String body) {
    return httpClient
        .sendAsync(newPostRequest(endpoint, requestTimeout, body), bodyHandler)
        .thenApply(parser);
  }

  protected final <H, R> CompletableFuture<R> sendPostRequestNoWrap(final HttpResponse.BodyHandler<H> bodyHandler,
                                                                    final Function<HttpResponse<H>, R> parser,
                                                                    final Duration requestTimeout,
                                                                    final String body) {
    return sendPostRequestNoWrap(endpoint, bodyHandler, parser, requestTimeout, body);
  }

  protected final <H, R> CompletableFuture<R> sendPostRequestNoWrap(final HttpResponse.BodyHandler<H> bodyHandler,
                                                                    final Function<HttpResponse<H>, R> parser,
                                                                    final String body) {
    return sendPostRequestNoWrap(bodyHandler, parser, requestTimeout, body);
  }

  protected final <H, R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                    final HttpResponse.BodyHandler<H> bodyHandler,
                                                                    final Function<HttpResponse<H>, R> parser,
                                                                    final String body) {
    return sendPostRequestNoWrap(endpoint, bodyHandler, parser, requestTimeout, body);
  }

  protected final <H, R> CompletableFuture<R> sendGetRequestNoWrap(final HttpResponse.BodyHandler<H> bodyHandler,
                                                                   final Function<HttpResponse<H>, R> parser,
                                                                   final String path) {
    return httpClient
        .sendAsync(newRequest(path).build(), bodyHandler)
        .thenApply(parser);
  }

  protected final <H, R> CompletableFuture<R> sendGetRequestNoWrap(final URI endpoint,
                                                                   final HttpResponse.BodyHandler<H> bodyHandler,
                                                                   final Function<HttpResponse<H>, R> parser) {
    return httpClient
        .sendAsync(newRequest(endpoint).build(), bodyHandler)
        .thenApply(parser);
  }
}
