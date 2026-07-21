package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.request.Commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Submission and simulation. These carry a signed transaction, so a wrong
/// preflight commitment or a dropped `skipPreflight` changes whether a
/// transaction is validated before it is broadcast — the request body is the
/// contract.
final class SendTransactionRpcRequestTests extends RpcRequestTests {

  private static final String TX = "AVXo5X7UNzpuOmYzkZ%2Bfqx==";
  private static final String SIGNATURE =
      "5VERd3ZfLb2qBqZzbGjWEnwqZQFbHnLWuGh4H1oGrzXmMPHkOfkq8kSjMWZ7Vw6qLkyoBSc3mCQNMcxbNxJgpp1U";

  private static final String SEND_RESPONSE = """
      {"jsonrpc":"2.0","result":"%s","id":1}""".formatted(SIGNATURE);

  private static final String SIMULATE_RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":{\
      "err":null,"logs":["Program 11111111111111111111111111111111 invoke [1]"],\
      "accounts":null,"unitsConsumed":150,"returnData":null}},"id":1}""";

  private void expectSend(final String options) {
    registerRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sendTransaction\",\"params\":[\""
        + TX + "\",{\"encoding\":\"base64\"," + options + "}]}", SEND_RESPONSE);
  }

  private void expectSimulate(final String options) {
    registerRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"simulateTransaction\",\"params\":[\""
        + TX + "\",{\"encoding\":\"base64\"," + options + "}]}", SIMULATE_RESPONSE);
  }

  /// The preflighting family defaults `maxRetries` to **1**; the skip-preflight
  /// family deliberately defaults it to **0**, because skipping preflight implies
  /// the caller is driving its own rebroadcast loop and node-side retries would
  /// duplicate it. `0` means send once — it is not "unset", and this client always
  /// emits the field, so the node's retry-until-expiry behaviour is never used.
  @Test
  void sendTransactionDefaults() {
    expectSend("\"preflightCommitment\":\"confirmed\",\"maxRetries\":1");
    assertEquals(SIGNATURE, rpcClient.sendTransaction(TX).join());
  }

  @Test
  void sendTransactionWithMaxRetries() {
    expectSend("\"preflightCommitment\":\"confirmed\",\"maxRetries\":3");
    assertEquals(SIGNATURE, rpcClient.sendTransaction(TX, 3).join());
  }

  @Test
  void sendTransactionWithPreflightCommitment() {
    expectSend("\"preflightCommitment\":\"finalized\",\"maxRetries\":1");
    assertEquals(SIGNATURE, rpcClient.sendTransaction(Commitment.FINALIZED, TX).join());
  }

  @Test
  void sendTransactionWithPreflightCommitmentAndRetries() {
    expectSend("\"preflightCommitment\":\"processed\",\"maxRetries\":5");
    assertEquals(SIGNATURE, rpcClient.sendTransaction(Commitment.PROCESSED, TX, 5).join());
  }

  /// Skipping preflight adds the flag and defaults the commitment to processed —
  /// there is nothing to validate against, so the strongest commitment is moot.
  @Test
  void skipPreflightDefaultsToProcessed() {
    expectSend("\"skipPreflight\":true,\"preflightCommitment\":\"processed\",\"maxRetries\":0");
    assertEquals(SIGNATURE, rpcClient.sendTransactionSkipPreflight(TX).join());
  }

  @Test
  void skipPreflightWithMaxRetries() {
    expectSend("\"skipPreflight\":true,\"preflightCommitment\":\"processed\",\"maxRetries\":4");
    assertEquals(SIGNATURE, rpcClient.sendTransactionSkipPreflight(TX, 4).join());
  }

  @Test
  void skipPreflightWithCommitment() {
    expectSend("\"skipPreflight\":true,\"preflightCommitment\":\"finalized\",\"maxRetries\":0");
    assertEquals(SIGNATURE, rpcClient.sendTransactionSkipPreflight(Commitment.FINALIZED, TX).join());
  }

  @Test
  void skipPreflightWithCommitmentAndRetries() {
    expectSend("\"skipPreflight\":true,\"preflightCommitment\":\"finalized\",\"maxRetries\":2");
    assertEquals(SIGNATURE, rpcClient.sendTransactionSkipPreflight(Commitment.FINALIZED, TX, 2).join());
  }

  /// The boolean overload picks a whole family, not just a flag: `true` also
  /// brings that family's `maxRetries` default of 0 and a processed preflight
  /// commitment. The three argument overload pins maxRetries across both branches.
  @Test
  void booleanFlagRoutesToTheSkipPreflightFamily() {
    expectSend("\"skipPreflight\":true,\"preflightCommitment\":\"processed\",\"maxRetries\":0");
    assertEquals(SIGNATURE, rpcClient.sendTransaction(TX, true).join());

    // and inherits the preflighting family's maxRetries default of 1
    expectSend("\"preflightCommitment\":\"confirmed\",\"maxRetries\":1");
    assertEquals(SIGNATURE, rpcClient.sendTransaction(TX, false).join());
  }

  @Test
  void booleanFlagWithMaxRetries() {
    expectSend("\"skipPreflight\":true,\"preflightCommitment\":\"processed\",\"maxRetries\":6");
    assertEquals(SIGNATURE, rpcClient.sendTransaction(TX, true, 6).join());

    expectSend("\"preflightCommitment\":\"confirmed\",\"maxRetries\":6");
    assertEquals(SIGNATURE, rpcClient.sendTransaction(TX, false, 6).join());
  }

  /// Both simulate families default `replaceRecentBlockhash` to **true**, so a
  /// simulation does not fail on an expired blockhash by default — worth knowing
  /// when a simulation passes and the real submission then does not.
  @Test
  void simulateTransactionDefaults() {
    expectSimulate("\"sigVerify\":false,\"replaceRecentBlockhash\":true,"
        + "\"innerInstructions\":false,\"commitment\":\"confirmed\"");
    final var simulation = rpcClient.simulateTransaction(TX).join();
    assertEquals(150, simulation.unitsConsumed().orElseThrow());
  }

  /// The explicit flag is honoured rather than being fixed at the default.
  @Test
  void simulateTransactionReplacingTheBlockhash() {
    expectSimulate("\"sigVerify\":false,\"replaceRecentBlockhash\":true,"
        + "\"innerInstructions\":false,\"commitment\":\"confirmed\"");
    assertEquals(150, rpcClient.simulateTransaction(TX, true).join().unitsConsumed().orElseThrow());
  }

  @Test
  void simulateTransactionWithInnerInstructions() {
    expectSimulate("\"sigVerify\":false,\"replaceRecentBlockhash\":true,"
        + "\"innerInstructions\":true,\"commitment\":\"confirmed\"");
    assertEquals(150,
        rpcClient.simulateTransactionWithInnerInstructions(TX).join().unitsConsumed().orElseThrow());
  }

  @Test
  void simulateTransactionWithInnerInstructionsHonoursCommitment() {
    expectSimulate("\"sigVerify\":false,\"replaceRecentBlockhash\":true,"
        + "\"innerInstructions\":true,\"commitment\":\"finalized\"");
    assertEquals(150, rpcClient.simulateTransactionWithInnerInstructions(Commitment.FINALIZED, TX)
        .join().unitsConsumed().orElseThrow());
  }

  @Test
  void simulateWithBothFlags() {
    expectSimulate("\"sigVerify\":false,\"replaceRecentBlockhash\":false,"
        + "\"innerInstructions\":true,\"commitment\":\"confirmed\"");
    assertEquals(150, rpcClient.simulateTransaction(TX, false, true).join().unitsConsumed().orElseThrow());
  }

  @Test
  void simulateWithBothFlagsAndCommitment() {
    expectSimulate("\"sigVerify\":false,\"replaceRecentBlockhash\":true,"
        + "\"innerInstructions\":true,\"commitment\":\"finalized\"");
    assertEquals(150, rpcClient.simulateTransaction(Commitment.FINALIZED, TX, true, true)
        .join().unitsConsumed().orElseThrow());
  }

  /// Requesting post-simulation account state appends an `accounts` object. The
  /// addresses are sent in the order given, because the returned values are
  /// positional.
  @Test
  void simulateReturningAccountStates() {
    expectSimulate("\"sigVerify\":false,\"replaceRecentBlockhash\":false,"
        + "\"innerInstructions\":false,\"commitment\":\"confirmed\","
        + "\"accounts\":{\"addresses\":[\"So11111111111111111111111111111111111111112\","
        + "\"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA\"],\"encoding\":\"base64\"}");

    final var accounts = java.util.List.of(
        software.sava.core.accounts.PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112"),
        software.sava.core.accounts.PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"));
    assertEquals(150, rpcClient.simulateTransaction(
        Commitment.CONFIRMED, TX, false, false, accounts).join().unitsConsumed().orElseThrow());
  }

  /// An empty account collection falls back to the overload without the section,
  /// rather than emitting an empty addresses array.
  @Test
  void simulateWithNoAccountsOmitsTheSection() {
    expectSimulate("\"sigVerify\":false,\"replaceRecentBlockhash\":false,"
        + "\"innerInstructions\":false,\"commitment\":\"confirmed\"");
    assertEquals(150, rpcClient.simulateTransaction(
        Commitment.CONFIRMED, TX, false, false, java.util.List.of())
        .join().unitsConsumed().orElseThrow());
  }
}
