package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The `getMultipleAccounts` and `getAccounts` convenience overloads. Each is a
/// one-line delegation and they all issue the same `getMultipleAccounts` RPC, so
/// the outgoing JSON is most of the assertion — which optional sections appear and
/// with what values.
///
/// The two families are not interchangeable, which is easy to miss because the
/// request they send is identical. `getMultipleAccounts` parses with
/// `parseAccountsFromKeys`, which drops accounts the node returned as null;
/// `getAccounts` parses with `parseAccountsFromKeysWithNulls`, which keeps the
/// slot so results stay positionally aligned with the keys passed in. A caller
/// zipping results back to keys must use `getAccounts`.
final class MultipleAccountsRpcRequestTests extends RpcRequestTests {

  private static final PublicKey KEY_A =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey KEY_B =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final List<PublicKey> KEYS = List.of(KEY_A, KEY_B);

  /// One account, empty data — enough to parse, since these tests are about the
  /// request rather than the response.
  private static final String RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":[\
      {"lamports":1,"data":["","base64"],"owner":"11111111111111111111111111111111",\
      "executable":false,"rentEpoch":0,"space":0},null]},"id":1}""";

  private static String request(final String method, final String options) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":[["
        + '"' + KEY_A.toBase58() + "\",\"" + KEY_B.toBase58() + "\"],{\"encoding\":\"base64\","
        + options + "}]}";
  }

  private void expect(final String method, final String options) {
    registerRequest(request(method, options), RESPONSE);
  }

  /// getMultipleAccounts drops absent accounts, so a two-key request with one
  /// missing account comes back with one entry.
  private static void assertCompacted(final List<?> accounts) {
    assertEquals(1, accounts.size(), "getMultipleAccounts should drop the null");
  }

  /// getAccounts keeps them, so results stay positionally aligned with the keys.
  private static void assertPositional(final List<?> accounts) {
    assertEquals(2, accounts.size(), "getAccounts should keep the null slot");
  }

  @Test
  void multipleAccountsWithDataSlice() {
    expect("getMultipleAccounts", "\"commitment\":\"confirmed\",\"dataSlice\":{\"length\":32,\"offset\":8}");
    assertCompacted(rpcClient.getMultipleAccounts(32, 8, KEYS).join());
  }

  @Test
  void multipleAccountsWithMinContextSlot() {
    expect("getMultipleAccounts", "\"commitment\":\"confirmed\",\"minContextSlot\":99");
    assertCompacted(rpcClient.getMultipleAccounts(BigInteger.valueOf(99), KEYS).join());
  }

  @Test
  void multipleAccountsWithMinContextSlotAndDataSlice() {
    expect("getMultipleAccounts",
        "\"commitment\":\"confirmed\",\"minContextSlot\":99,\"dataSlice\":{\"length\":32,\"offset\":8}");
    assertCompacted(rpcClient.getMultipleAccounts(BigInteger.valueOf(99), 32, 8, KEYS).join());
  }

  @Test
  void multipleAccountsWithCommitmentAndMinContextSlot() {
    expect("getMultipleAccounts", "\"commitment\":\"finalized\",\"minContextSlot\":99");
    assertCompacted(rpcClient.getMultipleAccounts(Commitment.FINALIZED, BigInteger.valueOf(99), KEYS).join());
  }

  @Test
  void multipleAccountsWithCommitmentAndDataSlice() {
    expect("getMultipleAccounts", "\"commitment\":\"processed\",\"dataSlice\":{\"length\":16,\"offset\":4}");
    assertCompacted(rpcClient.getMultipleAccounts(Commitment.PROCESSED, 16, 4, KEYS).join());
  }

  @Test
  void multipleAccountsWithEverything() {
    expect("getMultipleAccounts",
        "\"commitment\":\"finalized\",\"minContextSlot\":7,\"dataSlice\":{\"length\":16,\"offset\":4}");
    assertCompacted(rpcClient.getMultipleAccounts(
        Commitment.FINALIZED, BigInteger.valueOf(7), 16, 4, KEYS).join());
  }

  @Test
  void accountsWithDataSlice() {
    expect("getMultipleAccounts", "\"commitment\":\"confirmed\",\"dataSlice\":{\"length\":32,\"offset\":8}");
    assertPositional(rpcClient.getAccounts(32, 8, KEYS).join());
  }

  @Test
  void accountsWithMinContextSlot() {
    expect("getMultipleAccounts", "\"commitment\":\"confirmed\",\"minContextSlot\":99");
    assertPositional(rpcClient.getAccounts(BigInteger.valueOf(99), KEYS).join());
  }

  @Test
  void accountsWithMinContextSlotAndDataSlice() {
    expect("getMultipleAccounts",
        "\"commitment\":\"confirmed\",\"minContextSlot\":99,\"dataSlice\":{\"length\":32,\"offset\":8}");
    assertPositional(rpcClient.getAccounts(BigInteger.valueOf(99), 32, 8, KEYS).join());
  }

  @Test
  void accountsWithCommitmentAndMinContextSlot() {
    expect("getMultipleAccounts", "\"commitment\":\"finalized\",\"minContextSlot\":99");
    assertPositional(rpcClient.getAccounts(Commitment.FINALIZED, BigInteger.valueOf(99), KEYS).join());
  }

  @Test
  void accountsWithCommitmentAndDataSlice() {
    expect("getMultipleAccounts", "\"commitment\":\"processed\",\"dataSlice\":{\"length\":16,\"offset\":4}");
    assertPositional(rpcClient.getAccounts(Commitment.PROCESSED, 16, 4, KEYS).join());
  }

  @Test
  void accountsWithEverything() {
    expect("getMultipleAccounts",
        "\"commitment\":\"finalized\",\"minContextSlot\":7,\"dataSlice\":{\"length\":16,\"offset\":4}");
    assertPositional(rpcClient.getAccounts(
        Commitment.FINALIZED, BigInteger.valueOf(7), 16, 4, KEYS).join());
  }

  /// A zero length means "whole account", so the slice is omitted rather than
  /// sent as a zero-length window.
  @Test
  void zeroLengthOmitsTheDataSlice() {
    expect("getMultipleAccounts", "\"commitment\":\"confirmed\"");
    assertPositional(rpcClient.getAccounts(0, 64, KEYS).join());
  }

  // The factory-taking twins of the overloads above. Same request, but the caller
  // supplies its own decoder rather than taking raw bytes.

  @Test
  void multipleAccountsWithFactory() {
    expect("getMultipleAccounts", "\"commitment\":\"confirmed\"");
    assertCompacted(rpcClient.getMultipleAccounts(KEYS, (_, data) -> data).join());
  }

  @Test
  void multipleAccountsWithDataSliceAndFactory() {
    expect("getMultipleAccounts", "\"commitment\":\"confirmed\",\"dataSlice\":{\"length\":32,\"offset\":8}");
    assertCompacted(rpcClient.getMultipleAccounts(32, 8, KEYS, (_, data) -> data).join());
  }

  @Test
  void multipleAccountsWithMinContextSlotAndFactory() {
    expect("getMultipleAccounts", "\"commitment\":\"confirmed\",\"minContextSlot\":99");
    assertCompacted(rpcClient.getMultipleAccounts(
        BigInteger.valueOf(99), KEYS, (_, data) -> data).join());
  }

  @Test
  void multipleAccountsWithMinContextSlotDataSliceAndFactory() {
    expect("getMultipleAccounts",
        "\"commitment\":\"confirmed\",\"minContextSlot\":99,\"dataSlice\":{\"length\":32,\"offset\":8}");
    assertCompacted(rpcClient.getMultipleAccounts(
        BigInteger.valueOf(99), 32, 8, KEYS, (_, data) -> data).join());
  }

  @Test
  void accountsWithDataSliceAndFactory() {
    expect("getMultipleAccounts", "\"commitment\":\"confirmed\",\"dataSlice\":{\"length\":32,\"offset\":8}");
    assertPositional(rpcClient.getAccounts(32, 8, KEYS, (_, data) -> data).join());
  }

  @Test
  void accountsWithMinContextSlotAndFactory() {
    expect("getMultipleAccounts", "\"commitment\":\"confirmed\",\"minContextSlot\":99");
    assertPositional(rpcClient.getAccounts(BigInteger.valueOf(99), KEYS, (_, data) -> data).join());
  }

  @Test
  void accountsWithMinContextSlotDataSliceAndFactory() {
    expect("getMultipleAccounts",
        "\"commitment\":\"confirmed\",\"minContextSlot\":99,\"dataSlice\":{\"length\":32,\"offset\":8}");
    assertPositional(rpcClient.getAccounts(
        BigInteger.valueOf(99), 32, 8, KEYS, (_, data) -> data).join());
  }
}
