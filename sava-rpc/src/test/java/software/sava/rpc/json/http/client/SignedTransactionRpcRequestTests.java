package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The overloads that take an unsigned [Transaction] plus signers, rather than a
/// base64 string. These sign on the caller's behalf and then hand off to the
/// string overloads, so what they add is the signing step and the choice of
/// which string overload to delegate to.
///
/// The expected base64 is produced by calling the same `signAndBase64Encode` the
/// client uses. That is deliberate — signing is `sava-core`'s contract and is
/// tested there; what is asserted here is that the client sends *that* payload,
/// with the right commitment and to the right method.
final class SignedTransactionRpcRequestTests extends RpcRequestTests {

  private static final byte[] BLOCK_HASH = new byte[32];
  private static final String SIGNATURE =
      "5VERd3ZfLb2qBqZzbGjWEnwqZQFbHnLWuGh4H1oGrzXmMPHkOfkq8kSjMWZ7Vw6qLkyoBSc3mCQNMcxbNxJgpp1U";
  private static final String SEND_RESPONSE = """
      {"jsonrpc":"2.0","result":"%s","id":1}""".formatted(SIGNATURE);
  private static final String SIMULATE_RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":{\
      "err":null,"logs":[],"accounts":null,"unitsConsumed":150,"returnData":null}},"id":1}""";

  static {
    BLOCK_HASH[0] = 7;
  }

  private static PublicKey key(final int fill) {
    final byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, (byte) fill);
    return PublicKey.createPubKey(bytes);
  }

  /// A fixed keypair so the signed payload is identical on every run.
  private static Signer signer() {
    final byte[] seed = new byte[32];
    java.util.Arrays.fill(seed, (byte) 3);
    return Signer.createFromPrivateKey(seed);
  }

  private static Transaction transaction(final Signer signer) {
    return Transaction.createTx(
        AccountMeta.createFeePayer(signer.publicKey()),
        List.of(Instruction.createInstruction(
            key(11),
            List.of(AccountMeta.createWrite(key(12)), AccountMeta.createRead(key(13))),
            new byte[]{1, 2, 3}))
    );
  }

  private void expectSend(final String base64Tx, final String options) {
    registerRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sendTransaction\",\"params\":[\""
        + base64Tx + "\",{\"encoding\":\"base64\"," + options + "}]}", SEND_RESPONSE);
  }

  private void expectSimulate(final String base64Tx, final String options) {
    registerRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"simulateTransaction\",\"params\":[\""
        + base64Tx + "\",{\"encoding\":\"base64\"," + options + "}]}", SIMULATE_RESPONSE);
  }

  @Test
  void signsWithASingleSignerAndUsesTheDefaultCommitment() {
    final var signer = signer();
    final var expected = transaction(signer).signAndBase64Encode(BLOCK_HASH, signer);
    expectSend(expected, "\"preflightCommitment\":\"confirmed\",\"maxRetries\":1");

    assertEquals(SIGNATURE,
        rpcClient.sendTransaction(transaction(signer), signer, BLOCK_HASH).join());
  }

  @Test
  void signsWithASingleSignerAtAnExplicitCommitment() {
    final var signer = signer();
    final var expected = transaction(signer).signAndBase64Encode(BLOCK_HASH, signer);
    expectSend(expected, "\"preflightCommitment\":\"finalized\",\"maxRetries\":1");

    assertEquals(SIGNATURE, rpcClient.sendTransaction(
        Commitment.FINALIZED, transaction(signer), signer, BLOCK_HASH).join());
  }

  /// The collection overload signs with every signer; a single-element collection
  /// must produce the same payload as the single-signer overload.
  @Test
  void signsWithACollectionOfSigners() {
    final var signer = signer();
    final var expected = transaction(signer).signAndBase64Encode(BLOCK_HASH, List.of(signer));
    expectSend(expected, "\"preflightCommitment\":\"confirmed\",\"maxRetries\":1");

    assertEquals(SIGNATURE,
        rpcClient.sendTransaction(transaction(signer), List.of(signer), BLOCK_HASH).join());

    assertEquals(expected, transaction(signer).signAndBase64Encode(BLOCK_HASH, signer),
        "one signer via either overload should sign identically");
  }

  @Test
  void signsWithACollectionAtAnExplicitCommitment() {
    final var signer = signer();
    final var expected = transaction(signer).signAndBase64Encode(BLOCK_HASH, List.of(signer));
    expectSend(expected, "\"preflightCommitment\":\"processed\",\"maxRetries\":1");

    assertEquals(SIGNATURE, rpcClient.sendTransaction(
        Commitment.PROCESSED, transaction(signer), List.of(signer), BLOCK_HASH).join());
  }

  /// Simulation takes no signer — `sigVerify` is always false — so the transaction
  /// is encoded as-is rather than signed first. An unsigned transaction is a valid
  /// thing to simulate.
  @Test
  void simulateEncodesTheTransactionWithoutSigning() {
    final var tx = transaction(signer());
    tx.setRecentBlockHash(BLOCK_HASH);
    expectSimulate(tx.base64EncodeToString(),
        "\"sigVerify\":false,\"replaceRecentBlockhash\":true,"
            + "\"innerInstructions\":false,\"commitment\":\"confirmed\"");

    assertEquals(150, rpcClient.simulateTransaction(tx).join().unitsConsumed().orElseThrow());
  }

  @Test
  void simulateHonoursTheBlockhashFlagAndCommitment() {
    final var tx = transaction(signer());
    tx.setRecentBlockHash(BLOCK_HASH);
    expectSimulate(tx.base64EncodeToString(),
        "\"sigVerify\":false,\"replaceRecentBlockhash\":false,"
            + "\"innerInstructions\":false,\"commitment\":\"finalized\"");

    assertEquals(150, rpcClient.simulateTransaction(Commitment.FINALIZED, tx, false)
        .join().unitsConsumed().orElseThrow());
  }
}
