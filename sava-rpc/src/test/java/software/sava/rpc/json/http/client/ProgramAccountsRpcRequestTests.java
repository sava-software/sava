package software.sava.rpc.json.http.client;

import io.airlift.compress.v3.zstd.ZstdInputStream;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.RpcEncoding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// The `getProgramAccounts` overload tree. Every one funnels into
/// `ProgramAccountsRequestRecord.toJson` — whose output is asserted in detail by
/// `ProgramAccountsRequestTests` — so what these cover is the delegation itself:
/// which arguments each overload defaults and which it forwards. Getting that
/// wrong sends a valid request that asks the wrong question.
final class ProgramAccountsRpcRequestTests extends RpcRequestTests {

  private static final PublicKey PROGRAM_ID =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey SYSVAR_PROGRAM_ID =
      PublicKey.fromBase58Encoded("Sysvar1111111111111111111111111111111111111");
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final List<Filter> FILTERS = List.of(Filter.createDataSizeFilter(165));
  private static final String BASE64_ZSTD_RESPONSE = readResponse("getProgramAccountsBase64Zstd.json");

  private static final String RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":[\
      {"pubkey":"So11111111111111111111111111111111111111112","account":{"lamports":7,\
      "data":["","base64"],"owner":"11111111111111111111111111111111","executable":false,\
      "rentEpoch":0,"space":0}}]},"id":1}""";

  private void expect(final String options) {
    registerRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getProgramAccounts\",\"params\":[\""
        + PROGRAM_ID.toBase58() + "\",{\"withContext\":true,\"encoding\":\"base64\"," + options + "}]}", RESPONSE);
  }

  private static void assertParsed(final List<?> accounts) {
    assertEquals(1, accounts.size());
  }

  @Test
  void programIdOnlyUsesTheDefaultCommitment() {
    expect("\"commitment\":\"confirmed\"");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID).join());
  }

  @Test
  void base64DoesNotInvokeAnIrrelevantZstdDecompressor() {
    expect("\"commitment\":\"confirmed\"");
    final var request = ProgramAccountsRequest.build()
        .programId(PROGRAM_ID)
        .encoding(RpcEncoding.base64)
        .zstdDecompressor(_ -> {
          throw new AssertionError("base64 must not invoke the zstd decompressor");
        })
        .createRequest();
    assertParsed(rpcClient.getProgramAccounts(request).join());
  }

  @Test
  void programIdWithFilters() {
    expect("\"commitment\":\"confirmed\",\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, FILTERS).join());
  }

  @Test
  void programIdWithCommitmentAndFilters() {
    expect("\"commitment\":\"finalized\",\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.FINALIZED, FILTERS).join());
  }

  @Test
  void programIdWithFactory() {
    expect("\"commitment\":\"confirmed\"");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, (_, data) -> data).join());
  }

  @Test
  void programIdWithFiltersAndFactory() {
    expect("\"commitment\":\"confirmed\",\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, FILTERS, (_, data) -> data).join());
  }

  @Test
  void programIdWithCommitmentFiltersAndFactory() {
    expect("\"commitment\":\"processed\",\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(
        PROGRAM_ID, Commitment.PROCESSED, FILTERS, (_, data) -> data).join());
  }

  /// The Duration-carrying overloads exist because a program scan can outrun the
  /// client default; the timeout must not leak into the request body.
  @Test
  void requestTimeoutWithProgramId() {
    expect("\"commitment\":\"confirmed\"");
    assertParsed(rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, (_, data) -> data).join());
  }

  @Test
  void requestTimeoutWithFilters() {
    expect("\"commitment\":\"confirmed\",\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, FILTERS, (_, data) -> data).join());
  }

  @Test
  void requestTimeoutWithCommitmentAndFilters() {
    expect("\"commitment\":\"finalized\",\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(
        TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, FILTERS, (_, data) -> data).join());
  }

  @Test
  void requestTimeoutWithMinContextSlot() {
    expect("\"commitment\":\"finalized\",\"minContextSlot\":77,\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(
        TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, 77L, FILTERS, (_, data) -> data).join());
  }

  @Test
  void requestTimeoutWithDataSlice() {
    expect("\"commitment\":\"finalized\",\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(
        TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, FILTERS, 32, 8, (_, data) -> data).join());
  }

  @Test
  void requestTimeoutWithEverything() {
    expect("\"commitment\":\"processed\",\"minContextSlot\":77,"
        + "\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(
        TIMEOUT, PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS, 32, 8, (_, data) -> data).join());
  }

  /// Offline replay of a live mainnet response captured on 2026-08-13. The request
  /// selected the 40-byte Clock sysvar from the Sysvar owner program at finalized slot
  /// 439044078 using `base64+zstd`; the byte digest was independently verified with
  /// zstd 1.5.7.
  @Test
  void prebuiltBase64ZstdRequestDecodesTheLiveProgramAccountResponse() throws Exception {
    registerRequest(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getProgramAccounts\",\"params\":[\""
            + SYSVAR_PROGRAM_ID.toBase58()
            + "\",{\"withContext\":true,\"encoding\":\"base64+zstd\",\"commitment\":\"finalized\","
            + "\"filters\":[{\"dataSize\":40}]}]}",
        BASE64_ZSTD_RESPONSE
    );
    final var decompressions = new AtomicInteger();
    final var request = ProgramAccountsRequest.build()
        .programId(SYSVAR_PROGRAM_ID)
        .commitment(Commitment.FINALIZED)
        .encoding(RpcEncoding.base64_zstd)
        .zstdDecompressor(in -> {
          decompressions.incrementAndGet();
          return new ZstdInputStream(in);
        })
        .filters(List.of(Filter.createDataSizeFilter(40)))
        .createRequest();
    final var accounts = rpcClient.getProgramAccounts(request).join();
    assertEquals(1, decompressions.get());
    assertEquals(1, accounts.size());

    final var account = accounts.getFirst();
    assertEquals(PublicKey.fromBase58Encoded("SysvarC1ock11111111111111111111111111111111"), account.pubKey());
    assertEquals(439044078, account.context().slot());
    assertEquals("3.1.12", account.context().apiVersion());
    assertFalse(account.executable());
    assertEquals(1_169_280, account.lamports());
    assertEquals(SYSVAR_PROGRAM_ID, account.owner());
    assertEquals(new BigInteger("18446744073709551615"), account.rentEpoch());
    assertEquals(40, account.space());
    assertEquals(40, account.data().length);
    assertEquals(
        "acb2eaa05dbc6ca5aac26583b513c7e0f8af6b9910e51551080b333a23a16a65",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(account.data()))
    );
  }

  @Test
  void base64ZstdRequiresADecompressorBeforeBuildingTheRequest() {
    final var exception = assertThrows(IllegalStateException.class, () ->
        ProgramAccountsRequest.build()
            .programId(SYSVAR_PROGRAM_ID)
            .encoding(RpcEncoding.base64_zstd)
            .createRequest()
    );
    assertEquals(
        "base64+zstd requires ProgramAccountsRequest.Builder.zstdDecompressor(...)",
        exception.getMessage()
    );
  }

  @Test
  void customBase64ZstdRequestAlsoRequiresADecompressorBeforeSending() {
    final ProgramAccountsRequest<byte[]> request = new ProgramAccountsRequestRecord<>(
        null,
        SYSVAR_PROGRAM_ID,
        Commitment.FINALIZED,
        null,
        List.of(),
        0,
        0,
        RpcEncoding.base64_zstd,
        (_, data) -> data,
        null
    );
    final var exception = assertThrows(IllegalStateException.class, () ->
        rpcClient.getProgramAccounts(request)
    );
    assertEquals(
        "base64+zstd requires ProgramAccountsRequest.Builder.zstdDecompressor(...)",
        exception.getMessage()
    );
  }

  private static String readResponse(final String fileName) {
    final var path = "/rpc_response_data/" + fileName;
    try (var in = ProgramAccountsRpcRequestTests.class.getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(in, "Missing test resource " + path).readAllBytes(), UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // The remaining default overloads on the interface. Each fixes a different
  // subset of (requestTimeout, commitment, minContextSlot, dataSlice, factory);
  // the request body is what distinguishes them.

  @Test
  void timeoutCommitmentMinContextSlotFilters() {
    expect("\"commitment\":\"finalized\",\"minContextSlot\":77,\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, 77L, FILTERS).join());
  }

  @Test
  void timeoutCommitmentFiltersDataSlice() {
    expect("\"commitment\":\"finalized\",\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, FILTERS, 32, 8).join());
  }

  @Test
  void timeoutCommitmentMinContextSlotFiltersDataSlice() {
    expect("\"commitment\":\"finalized\",\"minContextSlot\":77,"
        + "\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, 77L, FILTERS, 32, 8).join());
  }

  @Test
  void programIdMinContextSlotFiltersFactory() {
    expect("\"commitment\":\"confirmed\",\"minContextSlot\":77,\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, 77L, FILTERS, (_, data) -> data).join());
  }

  @Test
  void programIdFiltersDataSliceFactory() {
    expect("\"commitment\":\"confirmed\",\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, FILTERS, 32, 8, (_, data) -> data).join());
  }

  @Test
  void programIdMinContextSlotFiltersDataSliceFactory() {
    expect("\"commitment\":\"confirmed\",\"minContextSlot\":77,"
        + "\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, 77L, FILTERS, 32, 8, (_, data) -> data).join());
  }

  @Test
  void programIdMinContextSlotFilters() {
    expect("\"commitment\":\"confirmed\",\"minContextSlot\":77,\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, 77L, FILTERS).join());
  }

  @Test
  void programIdFiltersDataSlice() {
    expect("\"commitment\":\"confirmed\",\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, FILTERS, 32, 8).join());
  }

  @Test
  void programIdMinContextSlotFiltersDataSlice() {
    expect("\"commitment\":\"confirmed\",\"minContextSlot\":77,"
        + "\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, 77L, FILTERS, 32, 8).join());
  }

  @Test
  void programIdCommitmentMinContextSlotFiltersFactory() {
    expect("\"commitment\":\"processed\",\"minContextSlot\":77,\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(
        PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS, (_, data) -> data).join());
  }

  @Test
  void programIdCommitmentFiltersDataSliceFactory() {
    expect("\"commitment\":\"processed\",\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(
        PROGRAM_ID, Commitment.PROCESSED, FILTERS, 32, 8, (_, data) -> data).join());
  }

  @Test
  void programIdCommitmentMinContextSlotFiltersDataSliceFactory() {
    expect("\"commitment\":\"processed\",\"minContextSlot\":77,"
        + "\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(
        PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS, 32, 8, (_, data) -> data).join());
  }

  @Test
  void programIdCommitmentMinContextSlotFilters() {
    expect("\"commitment\":\"processed\",\"minContextSlot\":77,\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS).join());
  }

  @Test
  void programIdCommitmentFiltersDataSlice() {
    expect("\"commitment\":\"processed\",\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, FILTERS, 32, 8).join());
  }

  @Test
  void programIdCommitmentMinContextSlotFiltersDataSlice() {
    expect("\"commitment\":\"processed\",\"minContextSlot\":77,"
        + "\"dataSlice\":{\"length\":32,\"offset\":8},\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS, 32, 8).join());
  }

  /// The `long` overloads use 0 as a sentinel for "no minimum slot" rather than
  /// sending `"minContextSlot":0`, which the node would read as a real constraint.
  @Test
  void zeroMinContextSlotIsOmitted() {
    expect("\"commitment\":\"confirmed\",\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, 0L, FILTERS).join());
  }

  /// Slots are u64, so a negative long is the top half of the range rather than an
  /// error — it is read unsigned.
  @Test
  void negativeMinContextSlotIsUnsigned() {
    expect("\"commitment\":\"confirmed\",\"minContextSlot\":18446744073709551615,\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(PROGRAM_ID, -1L, FILTERS).join());
  }

  /// The BigInteger overload carries a full u64 without the long sentinel dance.
  @Test
  void timeoutCommitmentBigIntegerMinContextSlotFiltersFactory() {
    expect("\"commitment\":\"finalized\",\"minContextSlot\":18446744073709551615,\"filters\":[{\"dataSize\":165}]");
    assertParsed(rpcClient.getProgramAccounts(
        TIMEOUT, PROGRAM_ID, Commitment.FINALIZED,
        new java.math.BigInteger("18446744073709551615"), FILTERS, (_, data) -> data).join());
  }
}
