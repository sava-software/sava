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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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

  /// Bounds of the gzip inflate buffer, which [GZIPInputStream] allocates eagerly and whose size
  /// may come from a server-controlled Content-Length, hence the clamp.
  /// [InputStream#readAllBytes()] owns the output growth, so this is only the inflate chunk size.
  /// Measured on a 5.7 MB mainnet `getBlock` response: time is flat from 4 KiB up, 512 B costs
  /// 12-20% more, and a buffer above 64 KiB only adds per-request allocation. Raising either bound
  /// has no measured upside.
  private static final int MIN_GZIP_BUFFER = 4_096;
  private static final int MAX_GZIP_BUFFER = 1 << 20;
  /// The largest request timeout the exchange deadline can double without overflowing a long.
  private static final Duration MAX_DOUBLED_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE >> 1);

  protected final URI endpoint;
  protected final HttpClient httpClient;
  /// The default JDK request timeout ([HttpRequest.Builder#timeout]) for calls that pass none: on
  /// JDK 25 it bounds only the wait for the response headers, on JDK 26 body consumption too.
  /// Routes that read the body themselves also bound the whole exchange at twice the built
  /// request's timeout; see [#withResponseDeadline].
  protected final Duration requestTimeout;
  protected final UnaryOperator<HttpRequest.Builder> extendRequest;
  protected final BiPredicate<HttpResponse<?>, byte[]> testResponse;
  /// Runs the exchange deadline's cancellation (see [#withResponseDeadline]). The default is
  /// `ForkJoinPool.commonPool()`, which on JDK 25 is also where the JDK HTTP client completes
  /// its `sendAsync` futures, so the deadline's timeliness is bounded by that pool's
  /// availability: a common pool saturated with blocking work delays the cancellation it
  /// schedules, and the exchange runs past its deadline by that much. A caller who needs the
  /// deadline to fire on time whatever the common pool is doing supplies a dedicated scheduler;
  /// the client never shuts it down. The cancellation completes the response future on the
  /// scheduler's thread, so a caller's non-async continuations on a timed-out exchange run
  /// there: give it more than one thread, or chain with the `*Async` forms. A
  /// `ScheduledThreadPoolExecutor` wants `setRemoveOnCancelPolicy(true)`, or a completed
  /// response's cancelled timer sits in its queue until the deadline would have fired (240 s on
  /// the getProgramAccounts route). A scheduler that rejects the deadline by throwing (one
  /// already shut down) fails the exchange rather than leaving it unbounded; one that silently
  /// discards the task — a discarding rejection handler — leaves it unbounded, so do not
  /// configure one.
  protected final ScheduledExecutorService deadlineScheduler;

  protected JsonHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout,
                           final UnaryOperator<HttpRequest.Builder> extendRequest,
                           final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
    this(endpoint, httpClient, requestTimeout, extendRequest, testResponse, null);
  }

  /// `deadlineScheduler` null selects the common pool: see [#deadlineScheduler].
  protected JsonHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout,
                           final UnaryOperator<HttpRequest.Builder> extendRequest,
                           final BiPredicate<HttpResponse<?>, byte[]> testResponse,
                           final ScheduledExecutorService deadlineScheduler) {
    this.endpoint = endpoint;
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
    this.extendRequest = extendRequest == null ? UnaryOperator.identity() : extendRequest;
    this.testResponse = testResponse;
    this.deadlineScheduler = deadlineScheduler == null ? ForkJoinPool.commonPool() : deadlineScheduler;
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

  /// The exchange deadline: twice the built request's timeout, which [#extendRequest] may have
  /// replaced, or twice `defaultTimeout` when an extender left none. On JDK 25 the JDK timer
  /// covers only the headers, and the body gets the same budget again.
  // package-private for tests
  static long responseDeadlineNanos(final HttpRequest request, final Duration defaultTimeout) {
    return responseDeadlineNanos(request.timeout().orElse(defaultTimeout));
  }

  /// Twice the timeout in nanoseconds, saturating at [Long#MAX_VALUE] where [Duration#toNanos()]
  /// would throw or the doubling would wrap negative; a negative deadline would cancel every
  /// request on the spot.
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
  /// finished response is not retained until the deadline would have fired (the common pool's
  /// delay scheduler unlinks at once; a `ScheduledThreadPoolExecutor` only with
  /// `removeOnCancelPolicy` set).
  ///
  /// On JDK 26 the request timeout covers the body too, so a stalled body fails with an
  /// `HttpTimeoutException` first and this never fires, unless an extender left the request
  /// without a timeout, where this is the only bound.
  // package-private for tests
  static <T> CompletableFuture<HttpResponse<T>> withResponseDeadline(final CompletableFuture<HttpResponse<T>> response,
                                                                      final ScheduledExecutorService scheduler,
                                                                      final long deadlineNanos) {
    // a block body: cancel(boolean) returns a value, and an expression lambda would bind the
    // Callable overload of schedule instead of the Runnable one
    final ScheduledFuture<?> cancellation;
    try {
      cancellation = scheduler.schedule(() -> {
        response.cancel(true);
      }, deadlineNanos, TimeUnit.NANOSECONDS);
    } catch (final RejectedExecutionException rejected) {
      // The exchange is already in flight and this deadline was its only bound (the JDK 25
      // timer stops at the headers): an exchange whose deadline cannot be armed is cancelled
      // rather than left to park on a stalled body for as long as the peer likes.
      response.cancel(true);
      throw rejected;
    }
    response.whenComplete((_, _) -> cancellation.cancel(false));
    return response;
  }

  private <T> CompletableFuture<HttpResponse<T>> sendWithDeadline(final HttpRequest request,
                                                                  final HttpResponse.BodyHandler<T> bodyHandler) {
    return sendWithDeadline(request, bodyHandler, deadlineScheduler);
  }

  // Package-private scheduler seam: lets a test observe the one timer this route arms, and
  // that a submission the executor rejects arms none.
  final <T> CompletableFuture<HttpResponse<T>> sendWithDeadline(final HttpRequest request,
                                                                final HttpResponse.BodyHandler<T> bodyHandler,
                                                                final ScheduledExecutorService scheduler) {
    // Submit first. sendAsync throws synchronously when the client's executor rejects the
    // request, and a cancellation scheduled before that would sit on the scheduler until the
    // deadline with nothing to release it: one leaked timer per rejected attempt.
    final var response = httpClient.sendAsync(request, bodyHandler);
    return withResponseDeadline(response, scheduler, responseDeadlineNanos(request, requestTimeout));
  }

  /// Whether any Content-Encoding element, across repeated and comma-folded field lines
  /// (RFC 9110 §8.4), is `gzip` or its alias `x-gzip` (§8.4.1.3). Position is ignored: a no-op
  /// `identity` beside it is skipped, and a non-outermost gzip (`gzip, br`) still reaches the
  /// inflater, which rejects the outer coding's bytes. No other coding is decoded.
  private static boolean isGzipEncoded(final HttpResponse<?> response) {
    for (final var header : response.headers().allValues("content-encoding")) {
      if (listsGzip(header)) {
        return true;
      }
    }
    return false;
  }

  /// Whether `gzip` or `x-gzip` is an element of the comma-separated `codings`, ignoring case and
  /// surrounding whitespace. Scanned in place rather than split so a provider-sized header sizes
  /// no allocation.
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

  /// The inflate buffer for a body already in memory: its exact compressed length, still clamped
  /// (see [#MIN_GZIP_BUFFER]) so an 8 MiB body does not add an 8 MiB buffer.
  // package-private for tests
  static int gzipBufferSize(final int compressedLength) {
    return Math.clamp(compressedLength, MIN_GZIP_BUFFER, MAX_GZIP_BUFFER);
  }

  /// Clamps the declared Content-Length, as a `long`, into an inflate buffer size. The header is
  /// untrusted: server controlled, unrelated to the bytes sent, and possibly not a number.
  /// Solana RPC servers compress on the fly and omit it when gzipping, so this normally falls
  /// back to [#MIN_GZIP_BUFFER]; it is still read because a buffering proxy or CDN may legally
  /// send both, and that value is what the clamp bounds.
  ///
  /// @return a size between [#MIN_GZIP_BUFFER] and [#MAX_GZIP_BUFFER], the minimum when the
  /// header is absent or unparseable.
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

  /// Reads the response body, inflating it when its Content-Encoding lists gzip; a null body
  /// returns null. An empty byte-array body is returned unchanged even when marked gzip, while
  /// an empty input stream marked gzip fails. Bytes after a complete gzip member that do not
  /// start another member are dropped silently, as [GZIPInputStream] drops them; a tail that
  /// begins a member but ends early is rejected or dropped depending on the JDK's read-ahead rule.
  ///
  /// @throws UncheckedIOException if reading an input-stream body fails or a gzip-marked body
  /// cannot be inflated
  /// @throws IllegalArgumentException if a non-null body is neither a `byte[]` nor an
  /// [InputStream]
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

  /// The body-handler routes have no exchange deadline, only the JDK request timeout (headers on
  /// JDK 25, body too on JDK 26): the caller's handler owns the body, which may legitimately
  /// stream for longer than any budget this client would pick.
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
