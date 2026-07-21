package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/// The `getAccountInfo` convenience overloads. Each is a one-line delegation into
/// the same request builder, so the outgoing JSON — which optional sections appear
/// and in what order — is the assertion that distinguishes them.
final class AccountInfoRpcRequestTests extends RpcRequestTests {

  private static final PublicKey ACCOUNT =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");

  private static final String RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":{\
      "lamports":42,"data":["","base64"],"owner":"11111111111111111111111111111111",\
      "executable":false,"rentEpoch":0,"space":0}},"id":1}""";

  private void expect(final String options) {
    registerRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getAccountInfo\",\"params\":[\""
        + ACCOUNT.toBase58() + "\",{\"encoding\":\"base64\"," + options + "}]}", RESPONSE);
  }

  @Test
  void withMinContextSlot() {
    expect("\"commitment\":\"confirmed\",\"minContextSlot\":5");
    assertEquals(42, rpcClient.getAccountInfo(BigInteger.valueOf(5), ACCOUNT).join().lamports());
  }

  @Test
  void withDataSlice() {
    expect("\"commitment\":\"confirmed\",\"dataSlice\":{\"length\":32,\"offset\":8}");
    assertEquals(42, rpcClient.getAccountInfo(32, 8, ACCOUNT).join().lamports());
  }

  @Test
  void withCommitmentAndMinContextSlot() {
    expect("\"commitment\":\"finalized\",\"minContextSlot\":5");
    assertEquals(42, rpcClient.getAccountInfo(
        Commitment.FINALIZED, BigInteger.valueOf(5), ACCOUNT).join().lamports());
  }

  @Test
  void withCommitmentAndDataSlice() {
    expect("\"commitment\":\"processed\",\"dataSlice\":{\"length\":16,\"offset\":4}");
    assertEquals(42, rpcClient.getAccountInfo(
        Commitment.PROCESSED, 16, 4, ACCOUNT).join().lamports());
  }

  @Test
  void withMinContextSlotAndDataSlice() {
    expect("\"commitment\":\"confirmed\",\"minContextSlot\":5,\"dataSlice\":{\"length\":16,\"offset\":4}");
    assertEquals(42, rpcClient.getAccountInfo(
        BigInteger.valueOf(5), 16, 4, ACCOUNT).join().lamports());
  }

  @Test
  void withEverything() {
    expect("\"commitment\":\"finalized\",\"minContextSlot\":9,\"dataSlice\":{\"length\":16,\"offset\":4}");
    assertEquals(42, rpcClient.getAccountInfo(
        Commitment.FINALIZED, BigInteger.valueOf(9), 16, 4, ACCOUNT).join().lamports());
  }

  /// A zero length is "whole account", so no slice is sent.
  @Test
  void zeroLengthOmitsTheDataSlice() {
    expect("\"commitment\":\"confirmed\"");
    assertEquals(42, rpcClient.getAccountInfo(0, 64, ACCOUNT).join().lamports());
  }

  /// SHARP EDGE, pinned. The node signals a missing account with `"value":null`,
  /// but `AccountInfo.parse` has no null guard — `testObject` on a null token
  /// simply matches no fields — so the caller gets a *non-null* AccountInfo whose
  /// fields are empty. The natural `if (info == null)` check for "account does not
  /// exist" is therefore always false; absence has to be read off `owner()` or
  /// `data()` being null.
  ///
  /// Note this differs from the getAccounts family, which does model absence as a
  /// null entry.
  @Test
  void absentAccountReturnsAHollowRecordNotNull() {
    registerRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getAccountInfo\",\"params\":[\""
            + ACCOUNT.toBase58() + "\",{\"encoding\":\"base64\",\"commitment\":\"confirmed\"}]}",
        """
            {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":null},"id":1}""");

    final var accountInfo = rpcClient.getAccountInfo(ACCOUNT).join();
    assertNotNull(accountInfo, "an absent account does not come back as null");
    assertEquals(ACCOUNT, accountInfo.pubKey());
    assertEquals(0, accountInfo.lamports());
    assertNull(accountInfo.owner(), "owner is how absence is actually detectable");
    assertNull(accountInfo.data());
  }

  @Test
  void minContextSlotWithFactory() {
    expect("\"commitment\":\"confirmed\",\"minContextSlot\":5");
    assertEquals(42, rpcClient.getAccountInfo(
        BigInteger.valueOf(5), ACCOUNT, (_, data) -> data).join().lamports());
  }

  @Test
  void dataSliceWithFactory() {
    expect("\"commitment\":\"confirmed\",\"dataSlice\":{\"length\":32,\"offset\":8}");
    assertEquals(42, rpcClient.getAccountInfo(32, 8, ACCOUNT, (_, data) -> data).join().lamports());
  }
}
