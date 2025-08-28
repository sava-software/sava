package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.Context;
import systems.comodal.jsoniter.JsonIterator;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.*;
import java.util.zip.GZIPInputStream;

public abstract class JsonRpcHttpClient extends JsonHttpClient {

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

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponseValue(final BiFunction<JsonIterator, Context, R> adapter) {
    return new JsonRpcValueResponseParser<>(adapter);
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponseResult(final Function<JsonIterator, R> adapter) {
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
