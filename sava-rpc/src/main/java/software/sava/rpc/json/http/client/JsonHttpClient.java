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
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPInputStream;

import static java.lang.System.Logger.Level.DEBUG;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.net.http.HttpResponse.BodyHandlers.ofInputStream;

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

  protected final URI endpoint;
  protected final HttpClient httpClient;
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

  private static boolean isGzipEncoded(final HttpResponse<?> response) {
    for (final var header : response.headers().allValues("content-encoding")) {
      if (header.equalsIgnoreCase("gzip")) {
        return true;
      }
    }
    return false;
  }

  private static byte[] readBytes(final HttpResponse<?> response, final byte[] body) {
    if (body == null || body.length == 0) {
      return body;
    }
    if (JsonHttpClient.isGzipEncoded(response)) {
      try (final var gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(body), body.length)) {
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
    return httpClient
        .sendAsync(newPostRequest(endpoint, requestTimeout, body), ofInputStream())
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
    return httpClient
        .sendAsync(newRequest(path).build(), ofInputStream())
        .thenApply(wrapResponseParser(parser));
  }

  protected final <R> CompletableFuture<R> sendGetRequest(final URI endpoint,
                                                          final Function<HttpResponse<?>, R> parser) {
    return httpClient
        .sendAsync(newRequest(endpoint).build(), ofInputStream())
        .thenApply(wrapResponseParser(parser));
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                 final Function<HttpResponse<?>, R> parser,
                                                                 final Duration requestTimeout,
                                                                 final String body) {
    return httpClient
        .sendAsync(newPostRequest(endpoint, requestTimeout, body), ofInputStream())
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
    return httpClient
        .sendAsync(newRequest(path).build(), ofInputStream())
        .thenApply(parser);
  }

  protected final <R> CompletableFuture<R> sendGetRequestNoWrap(final URI endpoint,
                                                                final Function<HttpResponse<?>, R> parser) {
    return httpClient
        .sendAsync(newRequest(endpoint).build(), ofInputStream())
        .thenApply(parser);
  }

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
