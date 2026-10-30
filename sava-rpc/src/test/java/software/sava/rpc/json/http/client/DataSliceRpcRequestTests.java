package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.DataSlice;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// Every account-fetching overload that takes a `DataSlice`, and every deprecated
/// `length, offset` overload it replaces. A `DataSlice` is literal: it is sent offset first
/// as Agave's `dataSlice` object, the empty slice is a zero-length window and `null` is the
/// whole account. The legacy pair reads a zero length as the whole account and now rejects
/// negative values before building a request.
final class DataSliceRpcRequestTests extends RpcRequestTests {

  private static final PublicKey ACCOUNT =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey KEY_B =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final List<PublicKey> KEYS = List.of(ACCOUNT, KEY_B);
  private static final PublicKey PROGRAM_ID = KEY_B;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final List<Filter> FILTERS = List.of(Filter.createDataSizeFilter(165));
  private static final BiFunction<PublicKey, byte[], byte[]> BYTES = (_, data) -> data;
  private static final BigInteger MAX_SLOT = new BigInteger("18446744073709551615");

  private static final DataSlice SLICE = new DataSlice(8, 32);
  private static final String SLICE_JSON = "\"dataSlice\":{\"offset\":8,\"length\":32}";
  private static final String EMPTY_JSON = "\"dataSlice\":{\"offset\":0,\"length\":0}";

  private static final String CONFIRMED = "\"commitment\":\"confirmed\"";
  private static final String PROCESSED = "\"commitment\":\"processed\"";
  private static final String FINALIZED = "\"commitment\":\"finalized\"";
  private static final String FILTERS_JSON = ",\"filters\":[{\"dataSize\":165}]";

  /// What each family's response must parse to: the account's lamports, the compacted list that
  /// getMultipleAccounts returns for the two-key response with one null, the positional list that
  /// getAccounts keeps aligned with its keys, and the single program account.
  private static final Consumer<Object> ACCOUNT_INFO = result -> assertEquals(42, ((AccountInfo<?>) result).lamports());
  private static final Consumer<Object> COMPACTED = result -> assertEquals(1, ((List<?>) result).size(), "getMultipleAccounts drops the null");
  private static final Consumer<Object> POSITIONAL = result -> assertEquals(2, ((List<?>) result).size(), "getAccounts keeps the null slot");
  private static final Consumer<Object> PROGRAM = result -> assertEquals(1, ((List<?>) result).size());

  private static final String ACCOUNT_RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":{\
      "lamports":42,"data":["","base64"],"owner":"11111111111111111111111111111111",\
      "executable":false,"rentEpoch":0,"space":0}},"id":1}""";
  private static final String ACCOUNTS_RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":[\
      {"lamports":1,"data":["","base64"],"owner":"11111111111111111111111111111111",\
      "executable":false,"rentEpoch":0,"space":0},null]},"id":1}""";
  private static final String PROGRAM_RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":[\
      {"pubkey":"So11111111111111111111111111111111111111112","account":{"lamports":7,\
      "data":["","base64"],"owner":"11111111111111111111111111111111","executable":false,\
      "rentEpoch":0,"space":0}}]},"id":1}""";

  /// One overload: the options it always sends before and after the slice, and the call.
  private record Twin(String prefix, String suffix, Function<DataSlice, CompletableFuture<?>> call) {

    Twin(final String prefix, final Function<DataSlice, CompletableFuture<?>> call) {
      this(prefix, "", call);
    }
  }

  private record Legacy(String prefix, String suffix, BiFunction<Integer, Integer, CompletableFuture<?>> call) {

    Legacy(final String prefix, final BiFunction<Integer, Integer, CompletableFuture<?>> call) {
      this(prefix, "", call);
    }
  }

  private void expectAccountInfo(final String options) {
    registerRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getAccountInfo\",\"params\":[\""
        + ACCOUNT.toBase58() + "\",{\"encoding\":\"base64\"," + options + "}]}", ACCOUNT_RESPONSE);
  }

  private void expectAccounts(final String options) {
    registerRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getMultipleAccounts\",\"params\":[[\""
        + ACCOUNT.toBase58() + "\",\"" + KEY_B.toBase58() + "\"],{\"encoding\":\"base64\"," + options + "}]}", ACCOUNTS_RESPONSE);
  }

  private void expectProgramAccounts(final String options) {
    registerRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getProgramAccounts\",\"params\":[\""
        + PROGRAM_ID.toBase58() + "\",{\"withContext\":true,\"encoding\":\"base64\"," + options + "}]}", PROGRAM_RESPONSE);
  }

  /// A slice is sent as given, the empty slice as a zero-length window, and null not at all.
  private static void twins(final Consumer<String> expect, final Consumer<Object> verify, final List<Twin> twins) {
    for (final var twin : twins) {
      expect.accept(twin.prefix() + ',' + SLICE_JSON + twin.suffix());
      verify.accept(twin.call().apply(SLICE).join());
      expect.accept(twin.prefix() + ',' + EMPTY_JSON + twin.suffix());
      verify.accept(twin.call().apply(DataSlice.EMPTY).join());
      expect.accept(twin.prefix() + twin.suffix());
      verify.accept(twin.call().apply(null).join());
    }
  }

  /// `length, offset` becomes `new DataSlice(offset, length)`, a zero length the whole account
  /// whatever the offset, and a negative value with a non-zero length an exception before any
  /// request is built.
  private static void legacy(final Consumer<String> expect, final Consumer<Object> verify, final List<Legacy> forms) {
    for (final var form : forms) {
      expect.accept(form.prefix() + ',' + SLICE_JSON + form.suffix());
      verify.accept(form.call().apply(32, 8).join());
      expect.accept(form.prefix() + form.suffix());
      verify.accept(form.call().apply(0, 64).join());
      expect.accept(form.prefix() + form.suffix());
      verify.accept(form.call().apply(0, -1).join());
      assertThrows(IllegalArgumentException.class, () -> form.call().apply(-1, 0));
      assertThrows(IllegalArgumentException.class, () -> form.call().apply(4, -1));
    }
  }

  /// The protected helper subclasses of BaseSolanaJsonRpcClient call keeps its signature and its
  /// zero sentinel, and now writes the slice offset first.
  @Test
  @SuppressWarnings("removal")
  void legacyAppendDataSliceHelperKeepsItsContract() {
    final var whole = new StringBuilder();
    BaseSolanaJsonRpcClient.appendDataSlice(whole, 0, 64);
    assertEquals("", whole.toString(), "a zero length appends nothing");
    final var sliced = new StringBuilder();
    BaseSolanaJsonRpcClient.appendDataSlice(sliced, 32, 8);
    assertEquals(",\"dataSlice\":{\"offset\":8,\"length\":32}", sliced.toString());
    assertThrows(IllegalArgumentException.class, () -> BaseSolanaJsonRpcClient.appendDataSlice(new StringBuilder(), -1, 0));
    assertThrows(IllegalArgumentException.class, () -> BaseSolanaJsonRpcClient.appendDataSlice(new StringBuilder(), 4, -1));
  }

  @Test
  void accountInfoSlices() {
    twins(this::expectAccountInfo, ACCOUNT_INFO, List.of(
        new Twin(CONFIRMED, s -> rpcClient.getAccountInfo(s, ACCOUNT)),
        new Twin(PROCESSED, s -> rpcClient.getAccountInfo(Commitment.PROCESSED, s, ACCOUNT)),
        new Twin(CONFIRMED + ",\"minContextSlot\":5", s -> rpcClient.getAccountInfo(BigInteger.valueOf(5), s, ACCOUNT)),
        new Twin(FINALIZED + ",\"minContextSlot\":9",
            s -> rpcClient.getAccountInfo(Commitment.FINALIZED, BigInteger.valueOf(9), s, ACCOUNT)),
        new Twin(CONFIRMED, s -> rpcClient.getAccountInfo(s, ACCOUNT, BYTES)),
        new Twin(PROCESSED, s -> rpcClient.getAccountInfo(Commitment.PROCESSED, s, ACCOUNT, BYTES)),
        new Twin(FINALIZED + ",\"minContextSlot\":9",
            s -> rpcClient.getAccountInfo(Commitment.FINALIZED, BigInteger.valueOf(9), s, ACCOUNT, BYTES))
    ));
  }

  @Test
  @SuppressWarnings("removal")
  void accountInfoLegacyPairs() {
    legacy(this::expectAccountInfo, ACCOUNT_INFO, List.of(
        new Legacy(CONFIRMED, (l, o) -> rpcClient.getAccountInfo(l, o, ACCOUNT)),
        new Legacy(PROCESSED, (l, o) -> rpcClient.getAccountInfo(Commitment.PROCESSED, l, o, ACCOUNT)),
        new Legacy(CONFIRMED + ",\"minContextSlot\":5", (l, o) -> rpcClient.getAccountInfo(BigInteger.valueOf(5), l, o, ACCOUNT)),
        new Legacy(FINALIZED + ",\"minContextSlot\":9",
            (l, o) -> rpcClient.getAccountInfo(Commitment.FINALIZED, BigInteger.valueOf(9), l, o, ACCOUNT)),
        new Legacy(CONFIRMED, (l, o) -> rpcClient.getAccountInfo(l, o, ACCOUNT, BYTES)),
        new Legacy(PROCESSED, (l, o) -> rpcClient.getAccountInfo(Commitment.PROCESSED, l, o, ACCOUNT, BYTES)),
        new Legacy(FINALIZED + ",\"minContextSlot\":9",
            (l, o) -> rpcClient.getAccountInfo(Commitment.FINALIZED, BigInteger.valueOf(9), l, o, ACCOUNT, BYTES))
    ));
  }

  @Test
  void multipleAccountsSlices() {
    twins(this::expectAccounts, COMPACTED, List.of(
        new Twin(CONFIRMED, s -> rpcClient.getMultipleAccounts(s, KEYS)),
        new Twin(CONFIRMED + ",\"minContextSlot\":99", s -> rpcClient.getMultipleAccounts(BigInteger.valueOf(99), s, KEYS)),
        new Twin(PROCESSED, s -> rpcClient.getMultipleAccounts(Commitment.PROCESSED, s, KEYS)),
        new Twin(FINALIZED + ",\"minContextSlot\":7",
            s -> rpcClient.getMultipleAccounts(Commitment.FINALIZED, BigInteger.valueOf(7), s, KEYS)),
        new Twin(CONFIRMED, s -> rpcClient.getMultipleAccounts(s, KEYS, BYTES)),
        new Twin(CONFIRMED + ",\"minContextSlot\":99", s -> rpcClient.getMultipleAccounts(BigInteger.valueOf(99), s, KEYS, BYTES)),
        new Twin(PROCESSED, s -> rpcClient.getMultipleAccounts(Commitment.PROCESSED, s, KEYS, BYTES)),
        new Twin(FINALIZED + ",\"minContextSlot\":7",
            s -> rpcClient.getMultipleAccounts(Commitment.FINALIZED, BigInteger.valueOf(7), s, KEYS, BYTES))
    ));
    twins(this::expectAccounts, POSITIONAL, List.of(
        new Twin(CONFIRMED, s -> rpcClient.getAccounts(s, KEYS)),
        new Twin(CONFIRMED + ",\"minContextSlot\":99", s -> rpcClient.getAccounts(BigInteger.valueOf(99), s, KEYS)),
        new Twin(PROCESSED, s -> rpcClient.getAccounts(Commitment.PROCESSED, s, KEYS)),
        new Twin(FINALIZED + ",\"minContextSlot\":7",
            s -> rpcClient.getAccounts(Commitment.FINALIZED, BigInteger.valueOf(7), s, KEYS)),
        new Twin(CONFIRMED, s -> rpcClient.getAccounts(s, KEYS, BYTES)),
        new Twin(CONFIRMED + ",\"minContextSlot\":99", s -> rpcClient.getAccounts(BigInteger.valueOf(99), s, KEYS, BYTES)),
        new Twin(PROCESSED, s -> rpcClient.getAccounts(Commitment.PROCESSED, s, KEYS, BYTES)),
        new Twin(FINALIZED + ",\"minContextSlot\":7",
            s -> rpcClient.getAccounts(Commitment.FINALIZED, BigInteger.valueOf(7), s, KEYS, BYTES))
    ));
  }

  @Test
  @SuppressWarnings("removal")
  void multipleAccountsLegacyPairs() {
    legacy(this::expectAccounts, COMPACTED, List.of(
        new Legacy(CONFIRMED, (l, o) -> rpcClient.getMultipleAccounts(l, o, KEYS)),
        new Legacy(CONFIRMED + ",\"minContextSlot\":99", (l, o) -> rpcClient.getMultipleAccounts(BigInteger.valueOf(99), l, o, KEYS)),
        new Legacy(PROCESSED, (l, o) -> rpcClient.getMultipleAccounts(Commitment.PROCESSED, l, o, KEYS)),
        new Legacy(FINALIZED + ",\"minContextSlot\":7",
            (l, o) -> rpcClient.getMultipleAccounts(Commitment.FINALIZED, BigInteger.valueOf(7), l, o, KEYS)),
        new Legacy(CONFIRMED, (l, o) -> rpcClient.getMultipleAccounts(l, o, KEYS, BYTES)),
        new Legacy(CONFIRMED + ",\"minContextSlot\":99", (l, o) -> rpcClient.getMultipleAccounts(BigInteger.valueOf(99), l, o, KEYS, BYTES)),
        new Legacy(PROCESSED, (l, o) -> rpcClient.getMultipleAccounts(Commitment.PROCESSED, l, o, KEYS, BYTES)),
        new Legacy(FINALIZED + ",\"minContextSlot\":7",
            (l, o) -> rpcClient.getMultipleAccounts(Commitment.FINALIZED, BigInteger.valueOf(7), l, o, KEYS, BYTES))
    ));
    legacy(this::expectAccounts, POSITIONAL, List.of(
        new Legacy(CONFIRMED, (l, o) -> rpcClient.getAccounts(l, o, KEYS)),
        new Legacy(CONFIRMED + ",\"minContextSlot\":99", (l, o) -> rpcClient.getAccounts(BigInteger.valueOf(99), l, o, KEYS)),
        new Legacy(PROCESSED, (l, o) -> rpcClient.getAccounts(Commitment.PROCESSED, l, o, KEYS)),
        new Legacy(FINALIZED + ",\"minContextSlot\":7",
            (l, o) -> rpcClient.getAccounts(Commitment.FINALIZED, BigInteger.valueOf(7), l, o, KEYS)),
        new Legacy(CONFIRMED, (l, o) -> rpcClient.getAccounts(l, o, KEYS, BYTES)),
        new Legacy(CONFIRMED + ",\"minContextSlot\":99", (l, o) -> rpcClient.getAccounts(BigInteger.valueOf(99), l, o, KEYS, BYTES)),
        new Legacy(PROCESSED, (l, o) -> rpcClient.getAccounts(Commitment.PROCESSED, l, o, KEYS, BYTES)),
        new Legacy(FINALIZED + ",\"minContextSlot\":7",
            (l, o) -> rpcClient.getAccounts(Commitment.FINALIZED, BigInteger.valueOf(7), l, o, KEYS, BYTES))
    ));
  }

  @Test
  void programAccountsSlices() {
    twins(this::expectProgramAccounts, PROGRAM, List.of(
        new Twin(FINALIZED, FILTERS_JSON,
            s -> rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, FILTERS, s, BYTES)),
        new Twin(PROCESSED + ",\"minContextSlot\":77", FILTERS_JSON,
            s -> rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS, s, BYTES)),
        new Twin(FINALIZED + ",\"minContextSlot\":18446744073709551615", FILTERS_JSON,
            s -> rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, MAX_SLOT, FILTERS, s, BYTES)),
        new Twin(FINALIZED, FILTERS_JSON,
            s -> rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, FILTERS, s)),
        new Twin(FINALIZED + ",\"minContextSlot\":77", FILTERS_JSON,
            s -> rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, 77L, FILTERS, s)),
        new Twin(CONFIRMED, FILTERS_JSON, s -> rpcClient.getProgramAccounts(PROGRAM_ID, FILTERS, s, BYTES)),
        new Twin(CONFIRMED + ",\"minContextSlot\":77", FILTERS_JSON,
            s -> rpcClient.getProgramAccounts(PROGRAM_ID, 77L, FILTERS, s, BYTES)),
        new Twin(CONFIRMED, FILTERS_JSON, s -> rpcClient.getProgramAccounts(PROGRAM_ID, FILTERS, s)),
        new Twin(CONFIRMED + ",\"minContextSlot\":77", FILTERS_JSON,
            s -> rpcClient.getProgramAccounts(PROGRAM_ID, 77L, FILTERS, s)),
        new Twin(PROCESSED, FILTERS_JSON,
            s -> rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, FILTERS, s, BYTES)),
        new Twin(PROCESSED + ",\"minContextSlot\":77", FILTERS_JSON,
            s -> rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS, s, BYTES)),
        new Twin(PROCESSED, FILTERS_JSON, s -> rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, FILTERS, s)),
        new Twin(PROCESSED + ",\"minContextSlot\":77", FILTERS_JSON,
            s -> rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS, s))
    ));
  }

  @Test
  @SuppressWarnings("removal")
  void programAccountsLegacyPairs() {
    legacy(this::expectProgramAccounts, PROGRAM, List.of(
        new Legacy(FINALIZED, FILTERS_JSON,
            (l, o) -> rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, FILTERS, l, o, BYTES)),
        new Legacy(PROCESSED + ",\"minContextSlot\":77", FILTERS_JSON,
            (l, o) -> rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS, l, o, BYTES)),
        new Legacy(FINALIZED + ",\"minContextSlot\":18446744073709551615", FILTERS_JSON,
            (l, o) -> rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, MAX_SLOT, FILTERS, l, o, BYTES)),
        new Legacy(FINALIZED, FILTERS_JSON,
            (l, o) -> rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, FILTERS, l, o)),
        new Legacy(FINALIZED + ",\"minContextSlot\":77", FILTERS_JSON,
            (l, o) -> rpcClient.getProgramAccounts(TIMEOUT, PROGRAM_ID, Commitment.FINALIZED, 77L, FILTERS, l, o)),
        new Legacy(CONFIRMED, FILTERS_JSON, (l, o) -> rpcClient.getProgramAccounts(PROGRAM_ID, FILTERS, l, o, BYTES)),
        new Legacy(CONFIRMED + ",\"minContextSlot\":77", FILTERS_JSON,
            (l, o) -> rpcClient.getProgramAccounts(PROGRAM_ID, 77L, FILTERS, l, o, BYTES)),
        new Legacy(CONFIRMED, FILTERS_JSON, (l, o) -> rpcClient.getProgramAccounts(PROGRAM_ID, FILTERS, l, o)),
        new Legacy(CONFIRMED + ",\"minContextSlot\":77", FILTERS_JSON,
            (l, o) -> rpcClient.getProgramAccounts(PROGRAM_ID, 77L, FILTERS, l, o)),
        new Legacy(PROCESSED, FILTERS_JSON,
            (l, o) -> rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, FILTERS, l, o, BYTES)),
        new Legacy(PROCESSED + ",\"minContextSlot\":77", FILTERS_JSON,
            (l, o) -> rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS, l, o, BYTES)),
        new Legacy(PROCESSED, FILTERS_JSON, (l, o) -> rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, FILTERS, l, o)),
        new Legacy(PROCESSED + ",\"minContextSlot\":77", FILTERS_JSON,
            (l, o) -> rpcClient.getProgramAccounts(PROGRAM_ID, Commitment.PROCESSED, 77L, FILTERS, l, o))
    ));
  }
}
