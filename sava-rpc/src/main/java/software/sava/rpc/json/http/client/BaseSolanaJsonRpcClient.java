package software.sava.rpc.json.http.client;

import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.Context;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.SequencedCollection;
import java.util.function.*;
import java.util.stream.Collectors;

public abstract class BaseSolanaJsonRpcClient extends JsonRpcHttpClient {

  protected final Commitment defaultCommitment;

  protected BaseSolanaJsonRpcClient(final URI endpoint,
                                    final HttpClient httpClient,
                                    final Duration requestTimeout,
                                    final UnaryOperator<HttpRequest.Builder> extendRequest,
                                    final BiPredicate<HttpResponse<?>, byte[]> testResponse,
                                    final Commitment defaultCommitment) {
    super(endpoint, httpClient, requestTimeout, extendRequest, testResponse);
    this.defaultCommitment = defaultCommitment;
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponseValue(final BiFunction<JsonIterator, Context, R> parser) {
    return new JsonRpcValueResponseParser<>(parser);
  }

  /// Appends nothing if minContextSlot is null.
  protected static void appendMinContextSlot(final StringBuilder builder, final BigInteger minContextSlot) {
    if (minContextSlot != null) {
      builder.append("""
          ,"minContextSlot":""");
      builder.append(minContextSlot);
    }
  }

  /// @param length data slice length, 0 to fetch complete account data.
  protected static void appendDataSlice(final StringBuilder builder, final int length, final int offset) {
    if (length != 0) {
      builder.append("""
          ,"dataSlice":{"length":""");
      builder.append(length);
      builder.append("""
          ,"offset":""");
      builder.append(offset);
      builder.append('}');
    }
  }

  /// Appends nothing if filters is null or empty.
  protected static void appendFilters(final StringBuilder builder, final Collection<Filter> filters) {
    if (filters != null && !filters.isEmpty()) {
      builder.append("""
          ,"filters":[""");
      final var iterator = filters.iterator();
      for (Filter filter; ; ) {
        filter = iterator.next();
        builder.append(filter.toJson());
        if (iterator.hasNext()) {
          builder.append(',');
        } else {
          break;
        }
      }
      builder.append(']');
    }
  }

  /// @return a JSON array of base58 encoded keys, an empty array if keys is null or empty.
  protected static String joinKeys(final SequencedCollection<PublicKey> keys) {
    return keys == null || keys.isEmpty() ? "[]" : keys.stream()
        .map(PublicKey::toBase58)
        .collect(Collectors.joining("\",\"", "[\"", "\"]"));
  }

  public final Commitment defaultCommitment() {
    return defaultCommitment;
  }
}
