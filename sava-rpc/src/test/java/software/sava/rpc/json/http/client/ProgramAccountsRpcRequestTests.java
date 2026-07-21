package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The `getProgramAccounts` overload tree. Every one funnels into
/// `ProgramAccountsRequestRecord.toJson` — whose output is asserted in detail by
/// `ProgramAccountsRequestTests` — so what these cover is the delegation itself:
/// which arguments each overload defaults and which it forwards. Getting that
/// wrong sends a valid request that asks the wrong question.
final class ProgramAccountsRpcRequestTests extends RpcRequestTests {

  private static final PublicKey PROGRAM_ID =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final List<Filter> FILTERS = List.of(Filter.createDataSizeFilter(165));

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

  /// A prebuilt request goes through untouched apart from the id and the client's
  /// default commitment.
  @Test
  void prebuiltRequestIsUsedAsIs() {
    expect("\"commitment\":\"finalized\",\"filters\":[{\"dataSize\":165}]");
    final var request = ProgramAccountsRequest.build()
        .programId(PROGRAM_ID)
        .commitment(Commitment.FINALIZED)
        .filters(FILTERS)
        .createRequest();
    assertParsed(rpcClient.getProgramAccounts(request).join());
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
}
