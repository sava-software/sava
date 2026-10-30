package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.DataSlice;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.RpcEncoding;
import software.sava.rpc.json.http.response.ZstdDecompressor;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/// getProgramAccounts is the one request this client builds by hand rather than
/// from a format string, and it is the one where a malformed body is expensive: a
/// dropped filter turns a targeted query into a full program scan, and a wrong
/// dataSlice silently returns the wrong bytes. The deprecated `dataSliceLength` builder method
/// and accessors stay pinned here until their removal.
@SuppressWarnings("removal")
final class ProgramAccountsRequestTests {

  private static final PublicKey PROGRAM_ID =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");

  private static ProgramAccountsRequest.Builder builder() {
    return ProgramAccountsRequest.build().programId(PROGRAM_ID);
  }

  private static String json(final ProgramAccountsRequest<?> request) {
    return request.toJson(7, Commitment.CONFIRMED);
  }

  @Test
  void minimalRequestCarriesTheMandatoryFields() {
    final var body = json(builder().encoding(RpcEncoding.base64).createRequest());

    assertTrue(body.startsWith("{\"jsonrpc\":\"2.0\",\"id\":7,"), body);
    assertTrue(body.contains("\"method\":\"getProgramAccounts\""), body);
    assertTrue(body.contains('"' + PROGRAM_ID.toBase58() + '"'), body);
    assertTrue(body.contains("\"withContext\":true"), body);
    assertTrue(body.contains("\"encoding\":\"base64\""), body);
    assertTrue(body.endsWith("}]}"), body);
  }

  /// Omitted optionals must be absent from the body, not sent as nulls or zeros —
  /// a `"dataSlice":{"length":0}` would mean "return nothing", not "return all".
  @Test
  void omittedOptionalsAreAbsentEntirely() {
    final var body = json(builder().encoding(RpcEncoding.base64).createRequest());
    assertFalse(body.contains("minContextSlot"), body);
    assertFalse(body.contains("dataSlice"), body);
    assertFalse(body.contains("filters"), body);
    assertFalse(body.contains("null"), body);
  }

  @Test
  void requestCommitmentOverridesTheClientDefault() {
    final var explicit = json(builder().encoding(RpcEncoding.base64).commitment(Commitment.FINALIZED).createRequest());
    assertTrue(explicit.contains("\"commitment\":\"finalized\""), explicit);

    // unset falls back to the default handed in by the client
    final var fallback = json(builder().encoding(RpcEncoding.base64).createRequest());
    assertTrue(fallback.contains("\"commitment\":\"confirmed\""), fallback);
  }

  @Test
  void minContextSlotIsIncludedWhenSet() {
    final var body = json(builder()
        .encoding(RpcEncoding.base64)
        .minContextSlot(BigInteger.valueOf(123_456_789L))
        .createRequest());
    assertTrue(body.contains("\"minContextSlot\":123456789"), body);
  }

  /// NOTE the parameter order: `dataSliceLength(offset, length)` is named for the
  /// length but takes the offset first. Pinned because it is easy to call backwards
  /// and the result is a silently wrong window rather than an error.
  @Test
  void dataSliceLengthTakesOffsetFirst() {
    final var body = json(builder().encoding(RpcEncoding.base64).dataSliceLength(8, 32).createRequest());
    assertTrue(body.contains("\"dataSlice\":{\"offset\":8,\"length\":32}"), body);
  }

  /// A zero length means "whole account", so the slice must be dropped rather
  /// than serialized as a zero-length window.
  @Test
  void dataSliceIsOmittedWhenLengthIsZero() {
    final var whole = json(builder().encoding(RpcEncoding.base64).dataSliceLength(64, 0).createRequest());
    assertFalse(whole.contains("dataSlice"), whole);

    final var sliced = json(builder().encoding(RpcEncoding.base64).dataSliceLength(8, 32).createRequest());
    assertTrue(sliced.contains("\"dataSlice\":{\"offset\":8,\"length\":32}"), sliced);
  }

  @Test
  void singleFilterIsSerialized() {
    final var body = json(builder()
        .encoding(RpcEncoding.base64)
        .filters(List.of(Filter.createDataSizeFilter(165)))
        .createRequest());
    assertTrue(body.contains("\"filters\":["), body);
    assertTrue(body.contains("\"dataSize\":165"), body);
  }

  /// Multiple filters are comma separated with no trailing comma — the loop that
  /// does this is hand written.
  @Test
  void multipleFiltersAreCommaSeparated() {
    final var body = json(builder()
        .encoding(RpcEncoding.base64)
        .filters(List.of(
            Filter.createDataSizeFilter(165),
            Filter.createMemCompFilter(32, PROGRAM_ID),
            Filter.createDataSizeFilter(200)))
        .createRequest());

    final int start = body.indexOf("\"filters\":[");
    final int end = body.indexOf(']', start);
    final var filters = body.substring(start, end + 1);

    assertFalse(filters.contains(",]"), "trailing comma: " + filters);
    assertFalse(filters.contains("[,"), "leading comma: " + filters);
    assertEquals(2, filters.chars().filter(c -> c == '}').count() - filters.chars().filter(c -> c == '{').count() + 2,
        "expected three filter objects: " + filters);
    assertTrue(filters.contains("\"dataSize\":165"), filters);
    assertTrue(filters.contains("\"dataSize\":200"), filters);
    assertTrue(filters.contains("memcmp"), filters);
  }

  @Test
  void emptyFilterCollectionIsOmitted() {
    final var body = json(builder().encoding(RpcEncoding.base64).filters(List.of()).createRequest());
    assertFalse(body.contains("filters"), body);
  }

  /// Everything at once, to catch a separator or brace that only breaks when the
  /// optional sections are combined.
  @Test
  void allOptionalsTogetherProduceWellFormedJson() {
    final var body = json(builder()
        .encoding(RpcEncoding.base64_zstd)
        .zstdDecompressor(in -> in)
        .commitment(Commitment.PROCESSED)
        .minContextSlot(BigInteger.ONE)
        .dataSliceLength(4, 16)
        .filters(List.of(Filter.createDataSizeFilter(165)))
        .createRequest());

    assertEquals(countChar(body, '{'), countChar(body, '}'), "unbalanced braces: " + body);
    assertEquals(countChar(body, '['), countChar(body, ']'), "unbalanced brackets: " + body);
    assertFalse(body.contains(",,"), body);
    assertFalse(body.contains(",}"), body);
    assertFalse(body.contains(",]"), body);
    // base64_zstd serializes as its overridden wire value, not its java name
    assertTrue(body.contains("\"encoding\":\"base64+zstd\""), body);
    assertTrue(body.contains("\"commitment\":\"processed\""), body);
    assertTrue(body.contains("\"minContextSlot\":1"), body);
    assertTrue(body.contains("\"dataSlice\":{\"offset\":4,\"length\":16}"), body);
    assertTrue(body.contains("\"dataSize\":165"), body);
  }

  private static long countChar(final String s, final char c) {
    return s.chars().filter(ch -> ch == c).count();
  }

  @Test
  void requestIdIsCarriedThrough() {
    final var request = builder().encoding(RpcEncoding.base64).createRequest();
    assertTrue(request.toJson(0, Commitment.CONFIRMED).contains("\"id\":0"));
    assertTrue(request.toJson(Long.MAX_VALUE, Commitment.CONFIRMED)
        .contains("\"id\":" + Long.MAX_VALUE));
  }

  @Test
  void builderRoundTripsItsAccessors() {
    final ZstdDecompressor zstdDecompressor = in -> in;
    final var request = builder()
        .encoding(RpcEncoding.base64)
        .zstdDecompressor(zstdDecompressor)
        .commitment(Commitment.FINALIZED)
        .minContextSlot(BigInteger.TEN)
        .dataSliceLength(2, 8)
        .requestTimeout(Duration.ofSeconds(45))
        .createRequest();

    assertEquals(PROGRAM_ID, request.programId());
    assertEquals(Commitment.FINALIZED, request.commitment());
    assertEquals(BigInteger.TEN, request.minContextSlot());
    assertEquals(8, request.dataSliceLength());
    assertEquals(2, request.dataSliceOffset());
    assertEquals(RpcEncoding.base64, request.encoding());
    assertSame(zstdDecompressor, request.zstdDecompressor());
    assertEquals(Duration.ofSeconds(45), request.requestTimeout());
  }

  @Test
  void dataSliceIsSerializedOffsetFirstAndReadBack() {
    final var request = builder().encoding(RpcEncoding.base64).dataSlice(new DataSlice(8, 32)).createRequest();
    assertTrue(json(request).contains("\"dataSlice\":{\"offset\":8,\"length\":32}"), json(request));
    assertEquals(new DataSlice(8, 32), request.dataSlice());
    assertEquals(32, request.dataSliceLength());
    assertEquals(8, request.dataSliceOffset());
  }

  /// A `DataSlice` is literal: the empty slice is sent as a zero-length window, and only a
  /// null slice omits the member.
  @Test
  void emptyDataSliceIsSentLiterallyAndNullIsOmitted() {
    final var empty = builder().encoding(RpcEncoding.base64).dataSlice(DataSlice.EMPTY).createRequest();
    assertTrue(json(empty).contains("\"dataSlice\":{\"offset\":0,\"length\":0}"), json(empty));
    assertEquals(DataSlice.EMPTY, empty.dataSlice());
    // the legacy int view cannot tell a zero-length window from no slice
    assertEquals(0, empty.dataSliceLength());
    assertEquals(0, empty.dataSliceOffset());

    final var whole = builder().encoding(RpcEncoding.base64).dataSlice(new DataSlice(8, 32)).dataSlice(null).createRequest();
    assertFalse(json(whole).contains("dataSlice"), json(whole));
    assertNull(whole.dataSlice());
    assertEquals(0, whole.dataSliceLength());
    assertEquals(0, whole.dataSliceOffset());
  }

  @Test
  void legacyDataSliceLengthMapsOntoTheSlice() {
    assertEquals(new DataSlice(8, 32),
        builder().encoding(RpcEncoding.base64).dataSliceLength(8, 32).createRequest().dataSlice());
    final var whole = builder().encoding(RpcEncoding.base64).dataSliceLength(64, 0).createRequest();
    assertNull(whole.dataSlice(), "a zero legacy length is the whole account");
    assertFalse(json(whole).contains("dataSlice"), json(whole));
    // 25.13.0 echoed 64 here: the offset of a zero-length legacy slice never reached the node and is no longer stored
    assertEquals(0, whole.dataSliceOffset());
    assertEquals(0, whole.dataSliceLength());
    assertThrows(IllegalArgumentException.class, () -> builder().dataSliceLength(-1, 4));
    assertThrows(IllegalArgumentException.class, () -> builder().dataSliceLength(0, -4));
  }

  /// An implementation written against 25.13.0 knows only the int accessors; the interface
  /// derives its slice from them with the legacy zero sentinel.
  private record LegacyImplementer(int dataSliceLength, int dataSliceOffset) implements ProgramAccountsRequest<byte[]> {

    @Override
    public String toJson(final long requestId, final Commitment commitment) {
      return "";
    }

    @Override
    public Duration requestTimeout() {
      return null;
    }

    @Override
    public PublicKey programId() {
      return PROGRAM_ID;
    }

    @Override
    public Commitment commitment() {
      return null;
    }

    @Override
    public BigInteger minContextSlot() {
      return null;
    }

    @Override
    public Collection<Filter> filters() {
      return null;
    }

    @Override
    public RpcEncoding encoding() {
      return RpcEncoding.base64;
    }

    @Override
    public BiFunction<PublicKey, byte[], byte[]> factory() {
      return (_, data) -> data;
    }
  }

  @Test
  void dataSliceDerivesFromTheLegacyAccessorsForOlderImplementations() {
    assertNull(new LegacyImplementer(0, 0).dataSlice());
    assertNull(new LegacyImplementer(0, 64).dataSlice(), "a zero legacy length is the whole account whatever the offset");
    assertEquals(new DataSlice(8, 32), new LegacyImplementer(32, 8).dataSlice());
    assertEquals(new DataSlice(0, 1), new LegacyImplementer(1, 0).dataSlice());
    assertThrows(IllegalArgumentException.class, () -> new LegacyImplementer(32, -1).dataSlice());
  }

  @Test
  void base64ZstdCannotBeBuiltWithoutADecompressor() {
    final var exception = assertThrows(IllegalStateException.class, () ->
        builder().encoding(RpcEncoding.base64_zstd).createRequest()
    );
    assertEquals(
        "base64+zstd requires ProgramAccountsRequest.Builder.zstdDecompressor(...)",
        exception.getMessage()
    );
  }
}
