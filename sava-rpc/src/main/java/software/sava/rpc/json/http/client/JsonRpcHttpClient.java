package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.Context;
import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.function.*;
import java.util.zip.GZIPInputStream;

import static java.lang.System.Logger.Level.ERROR;
import static software.sava.rpc.json.http.client.JsonResponseController.throwUncheckedIOException;

public abstract class JsonRpcHttpClient extends JsonHttpClient {

  private static final System.Logger logger = System.getLogger(JsonRpcHttpClient.class.getName());

  public JsonRpcHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout,
                           final UnaryOperator<HttpRequest.Builder> extendRequest,
                           @Deprecated final Predicate<HttpResponse<byte[]>> applyResponse,
                           final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
    super(endpoint, httpClient, requestTimeout, extendRequest, applyResponse, testResponse);
  }

  public JsonRpcHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout) {
    super(endpoint, httpClient, requestTimeout);
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
    if (isGzipEncoded(response)) {
      try (final var gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(body), body.length);
           final var byteArrayOutputStream = new ByteArrayOutputStream(body.length << 2)) {
        gzipInputStream.transferTo(byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    } else {
      return body;
    }
  }

  private static byte[] readInputStream(final HttpResponse<?> response, final InputStream inputStream) {
    if (inputStream == null) {
      return null;
    }
    try {
      if (isGzipEncoded(response)) {
        final int bufferSize = (int) response.headers()
            .firstValueAsLong("Content-Length")
            .orElse(4_096); // TODO: raise issue that the Solana RPC server does not provide this response header.
        try (final var gzipInputStream = new GZIPInputStream(inputStream, bufferSize);
             final var byteArrayOutputStream = new ByteArrayOutputStream(bufferSize << 2)) {
          gzipInputStream.transferTo(byteArrayOutputStream);
          return byteArrayOutputStream.toByteArray();
        }
      } else {
        return inputStream.readAllBytes();
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] readBody(final HttpResponse<?> response) {
    if (response instanceof ReadHttpResponse<?> readHttpResponse) {
      return readHttpResponse.readBody();
    } else {
      final var body = response.body();
      if (body instanceof byte[] bytes) {
        return readBytes(response, bytes);
      } else if (body instanceof InputStream inputStream) {
        return readInputStream(response, inputStream);
      } else if (body != null) {
        throw new IllegalArgumentException("Unsupported response body type: " + body.getClass());
      } else {
        return null;
      }
    }
  }

  private static RuntimeException parseExceptionChecked(final byte[] body,
                                                        final JsonIterator ji,
                                                        final OptionalLong retryAfterSeconds) {
    try {
      return JsonRpcException.parseException(ji, retryAfterSeconds);
    } catch (final RuntimeException ex) {
      logger.log(ERROR, "Failed to parse JSON-RPC exception: " + new String(body), ex);
      throw ex;
    }
  }

  static JsonIterator checkResponse(final HttpResponse<?> httpResponse, final byte[] body) {
    final var ji = JsonIterator.parse(body);
    final int responseCode = httpResponse.statusCode();
    final boolean isJsonObject = ji.whatIsNext() == ValueType.OBJECT;
    if (responseCode < 200 || responseCode >= 300 || !isJsonObject || ji.skipUntil("result") == null) {
      if (!isJsonObject) {
        throw throwUncheckedIOException(httpResponse, new String(body));
      } else if (ji.reset(0).skipUntil("error") == null) {
        throw throwUncheckedIOException(httpResponse, new String(body));
      } else {
        final var retryAfter = httpResponse.headers().firstValueAsLong("retry-after");
        throw parseExceptionChecked(body, ji, retryAfter);
      }
    } else {
      return ji;
    }
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponseValue(
      final BiFunction<JsonIterator, Context, R> adapter) {
    return new JsonRpcValueResponseParser<>(adapter);
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponseResult(
      final Function<JsonIterator, R> adapter) {
    return new JsonRpcResultResponseParser<>(adapter);
  }

  @Deprecated
  protected static <R> Function<HttpResponse<byte[]>, R> applyResponseValue(final BiFunction<JsonIterator, Context, R> adapter) {
    return new JsonRpcBytesValueParseController<>(adapter);
  }

  @Deprecated
  protected static <R> Function<HttpResponse<byte[]>, R> applyResponseResult(final Function<JsonIterator, R> adapter) {
    return new JsonRpcBytesResultParseController<>(adapter);
  }

  @Deprecated
  protected static <R> Function<HttpResponse<byte[]>, R> applyResponseResult(final BiFunction<HttpResponse<byte[]>, JsonIterator, R> adapter) {
    return new JsonRpcBytesResponseController<>(adapter);
  }
}
