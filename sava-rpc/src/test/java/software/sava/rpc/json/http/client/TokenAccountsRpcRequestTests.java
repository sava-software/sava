package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.rpc.DataSlice;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.response.RpcCustomError;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/// The token-account queries with a data window and a minimum context slot, whose new overloads
/// hand the caller the bytes to decode. The owner and mint come from a devnet block's token
/// balances. The `getTokenAccountsByOwner` exchanges with the recent minimum, the literal zero, the
/// unsigned maximum, the mint query's eight-byte slice and the program query's empty slice are
/// verbatim captures from `https://api.devnet.solana.com` on 2026-11-01 (Agave 4.4.0-beta.1); every
/// other owner registration sends its own body and replays one of those answers. The public nodes
/// answered every `getTokenAccountsByDelegate` with their proxy's `429 Too many requests for a
/// specific RPC call`, on all three clusters, so the delegate tests send the same options to the
/// same owner and replay the owner answers, with a synthetic `-32016` slot: agave serves both
/// methods from one `RpcAccountInfoConfig` and one `RpcKeyedAccount` list.
final class TokenAccountsRpcRequestTests extends RpcRequestTests {

  private static final BigInteger U64_MAX = new BigInteger("18446744073709551615");
  private static final BigInteger RECENT = BigInteger.valueOf(517_570_444L);
  private static final PublicKey OWNER = PublicKey.fromBase58Encoded("H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA");
  private static final PublicKey MINT = PublicKey.fromBase58Encoded("73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3");
  private static final PublicKey TOKEN_PROGRAM = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey FIRST = PublicKey.fromBase58Encoded("bZbWBPtFmyXQAMCYr4iUbwM8dYgnB1LRsTDPRaVpR2b");
  private static final PublicKey FUNDED = PublicKey.fromBase58Encoded("3nad9dtuCMncMFyt1uxyM8A2YMY92StEU6z5Ty8E5bux");
  private static final PublicKey LAST = PublicKey.fromBase58Encoded("J2KdqpV1tJTAgH4DwXsrKYs1RZvkTyUSLBC2z6GfvKry");

  private static final String EMPTY_DATA =
      "WcrnjejJ1nGe8aw3r2POhgByq+C+ixm/hlEQmxAsVszvtjc3xVUStkgCW9CXnwkTdWMCN+NzAxPHYeOyfJtuRwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private static final String ACCOUNT_TAIL =
      "\",\"base64\"],\"executable\":false,\"lamports\":1488440,\"owner\":\"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA\",\"rentEpoch\":18446744073709551615,\"space\":165},\"pubkey\":\"";

  private static String account(final String data, final PublicKey pubKey) {
    return "{\"account\":{\"data\":[\"" + data + ACCOUNT_TAIL + pubKey + "\"}";
  }

  private static String response(final long slot, final String fundedData, final String emptyData) {
    return "{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"apiVersion\":\"4.4.0-beta.1\",\"slot\":" + slot + "},\"value\":["
        + account(emptyData, FIRST) + ','
        + account(fundedData, FUNDED) + ','
        + account(emptyData, PublicKey.fromBase58Encoded("AN4fGsemRAFtLLfZEZFMwS9gAiNBxCgDjV63EawejefY")) + ','
        + account(emptyData, LAST)
        + "]},\"id\":1}";
  }

  /// Captured with the minimum at the recent slot.
  private static final String RECENT_RESPONSE = response(517_570_691L,
      "WcrnjejJ1nGe8aw3r2POhgByq+C+ixm/hlEQmxAsVszvtjc3xVUStkgCW9CXnwkTdWMCN+NzAxPHYeOyfJtuR8nzXMcGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      EMPTY_DATA);
  /// Captured with the literal zero minimum, a few slots earlier, when the funded account held less.
  private static final String ZERO_RESPONSE = response(517_570_684L,
      "WcrnjejJ1nGe8aw3r2POhgByq+C+ixm/hlEQmxAsVszvtjc3xVUStkgCW9CXnwkTdWMCN+NzAxPHYeOyfJtuRyrecboGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      EMPTY_DATA);
  /// Captured with `dataSlice` `{"offset":64,"length":8}`: the amount field alone.
  private static final String SLICE_RESPONSE = response(517_570_703L, "oEzD4QYAAAA=", "AAAAAAAAAAA=");
  /// Captured with [DataSlice#EMPTY]: metadata only.
  private static final String EMPTY_SLICE_RESPONSE = response(517_570_709L, "", "");

  private static final String RECENT_MIN = "\"minContextSlot\":517570444";
  private static final String WINDOW_JSON = "\"dataSlice\":{\"offset\":64,\"length\":8}";
  private static final DataSlice WINDOW = new DataSlice(64, 8);

  private static String body(final String method, final String filterKey, final PublicKey filterValue, final String options) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":[\"" + OWNER + "\",{\"" + filterKey + "\":\"" + filterValue
        + "\"},{" + options + "}]}";
  }

  private static String options(final Commitment commitment, final String... rest) {
    final var builder = new StringBuilder("\"commitment\":\"").append(commitment.getValue()).append("\",\"encoding\":\"base64\"");
    for (final var option : rest) {
      builder.append(',').append(option);
    }
    return builder.toString();
  }

  private static JsonRpcException joinExpectingRpcException(final Runnable join) {
    final var exception = assertThrows(CompletionException.class, join::run);
    return assertInstanceOf(JsonRpcException.class, exception.getCause());
  }

  /// The caller's decoder for a whole account: the base layout.
  private static TokenAccount decode(final AccountInfo<byte[]> account) {
    return TokenAccount.read(account.pubKey(), account.data());
  }

  /// The caller's decoder for an eight-byte amount window.
  private static long amount(final byte[] window) {
    return ByteUtil.getInt64LE(window, 0);
  }

  @Test
  void ownerAccountsByMintWithAMinimumReturnTheBytesTheCallerDecodes() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"mint":"73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3"},{"commitment":"confirmed","encoding":"base64","minContextSlot":517570444}]}""",
        RECENT_RESPONSE);
    final var accounts = rpcClient.getTokenAccountsForTokenMintByOwner(Commitment.CONFIRMED, RECENT, OWNER, MINT).join();
    assertEquals(4, accounts.size());
    final var funded = accounts.get(1);
    assertEquals(FUNDED, funded.pubKey());
    assertEquals(517_570_691L, funded.context().slot());
    assertEquals(1_488_440L, funded.lamports());
    assertEquals(165, funded.space());
    assertEquals(165, funded.data().length);
    final var tokenAccount = decode(funded);
    assertEquals(MINT, tokenAccount.mint());
    assertEquals(OWNER, tokenAccount.owner());
    assertEquals(29_114_561_481L, tokenAccount.amount());
    assertEquals(0L, decode(accounts.getFirst()).amount());
    assertEquals(LAST, accounts.getLast().pubKey());
  }

  @Test
  void ownerAccountsWithALiteralZeroMinimumUseTheClientCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"mint":"73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3"},{"commitment":"confirmed","encoding":"base64","minContextSlot":0}]}""",
        ZERO_RESPONSE);
    final var accounts = rpcClient.getTokenAccountsForTokenMintByOwner(BigInteger.ZERO, OWNER, MINT).join();
    assertEquals(517_570_684L, accounts.getFirst().context().slot());
    assertEquals(28_897_828_394L, decode(accounts.get(1)).amount());
  }

  @Test
  void anUnreachableMinimumFailsBeforeAnyAccountIsRead() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"mint":"73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3"},{"commitment":"confirmed","encoding":"base64","minContextSlot":18446744073709551615}]}""", """
        {"jsonrpc":"2.0","error":{"code":-32016,"message":"Minimum context slot has not been reached","data":{"contextSlot":517570696}},"id":1}""");
    final var exception = joinExpectingRpcException(() ->
        rpcClient.getTokenAccountsForTokenMintByOwner(Commitment.CONFIRMED, U64_MAX, OWNER, MINT).join());
    assertEquals(-32016, exception.code());
    assertEquals(517_570_696L, assertInstanceOf(RpcCustomError.MinContextSlotNotReached.class, exception.customError()).contextSlot());
  }

  @Test
  void aDataSliceReturnsTheWindowWithTheFullSpace() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"mint":"73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3"},{"commitment":"confirmed","encoding":"base64","minContextSlot":517570444,"dataSlice":{"offset":64,"length":8}}]}""",
        SLICE_RESPONSE);
    final var accounts = rpcClient.getTokenAccountsForTokenMintByOwner(Commitment.CONFIRMED, RECENT, WINDOW, OWNER, MINT).join();
    assertEquals(4, accounts.size());
    for (final var account : accounts) {
      assertEquals(8, account.data().length);
      assertEquals(165, account.space());
    }
    assertEquals(0L, amount(accounts.getFirst().data()));
    assertEquals(29_557_476_512L, amount(accounts.get(1).data()));
    assertEquals(517_570_703L, accounts.getLast().context().slot());
  }

  @Test
  void anEmptySliceReturnsMetadataOnly() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},{"commitment":"confirmed","encoding":"base64","dataSlice":{"offset":0,"length":0}}]}""",
        EMPTY_SLICE_RESPONSE);
    final var accounts = rpcClient.getTokenAccountsForProgramByOwner(DataSlice.EMPTY, OWNER, TOKEN_PROGRAM).join();
    assertEquals(4, accounts.size());
    for (final var account : accounts) {
      assertEquals(0, account.data().length);
      assertEquals(165, account.space());
      assertEquals(TOKEN_PROGRAM, account.owner());
      assertEquals(1_488_440L, account.lamports());
    }
    assertEquals(517_570_709L, accounts.getFirst().context().slot());
  }

  /// A null minimum and a null slice send the released body and still hand back the bytes.
  @Test
  void nullOptionsSendTheReleasedBodyAndReturnTheBytes() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},{"commitment":"finalized","encoding":"base64"}]}""",
        RECENT_RESPONSE);
    final var accounts = rpcClient.getTokenAccountsForProgramByOwner(Commitment.FINALIZED, (BigInteger) null, OWNER, TOKEN_PROGRAM).join();
    assertEquals(List.of(0L, 29_114_561_481L, 0L, 0L), accounts.stream().map(account -> decode(account).amount()).toList());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"mint":"73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3"},{"commitment":"processed","encoding":"base64"}]}""",
        ZERO_RESPONSE);
    final var whole = rpcClient.getTokenAccountsForTokenMintByOwner(Commitment.PROCESSED, (DataSlice) null, OWNER, MINT).join();
    assertEquals(List.of(165, 165, 165, 165), whole.stream().map(account -> account.data().length).toList());
    assertEquals(28_897_828_394L, decode(whole.get(1)).amount());
  }

  @Test
  void theMinimumPrecedesTheSlice() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},{"commitment":"processed","encoding":"base64","minContextSlot":517570444,"dataSlice":{"offset":64,"length":8}}]}""",
        SLICE_RESPONSE);
    final var windows = rpcClient.getTokenAccountsForProgramByOwner(Commitment.PROCESSED, RECENT, WINDOW, OWNER, TOKEN_PROGRAM).join();
    assertEquals(List.of(0L, 29_557_476_512L, 0L, 0L), windows.stream().map(account -> amount(account.data())).toList());
  }

  @Test
  void delegateQueriesCarryTheSameOptionsOnTheDelegateMethod() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByDelegate","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"mint":"73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3"},{"commitment":"finalized","encoding":"base64","minContextSlot":517570444,"dataSlice":{"offset":64,"length":8}}]}""",
        SLICE_RESPONSE);
    final var windows = rpcClient.getTokenAccountsForTokenMintByDelegate(Commitment.FINALIZED, RECENT, WINDOW, OWNER, MINT).join();
    assertEquals(4, windows.size());
    assertEquals(29_557_476_512L, amount(windows.get(1).data()));

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByDelegate","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},{"commitment":"confirmed","encoding":"base64","minContextSlot":0}]}""",
        ZERO_RESPONSE);
    final var accounts = rpcClient.getTokenAccountsForProgramByDelegate(BigInteger.ZERO, OWNER, TOKEN_PROGRAM).join();
    assertEquals(28_897_828_394L, decode(accounts.get(1)).amount());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByDelegate","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"mint":"73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3"},{"commitment":"confirmed","encoding":"base64","dataSlice":{"offset":0,"length":0}}]}""",
        EMPTY_SLICE_RESPONSE);
    final var metadata = rpcClient.getTokenAccountsForTokenMintByDelegate(DataSlice.EMPTY, OWNER, MINT).join();
    assertEquals(0, metadata.getFirst().data().length);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByDelegate","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},{"commitment":"confirmed","encoding":"base64"}]}""",
        RECENT_RESPONSE);
    final var whole = rpcClient.getTokenAccountsForProgramByDelegate(Commitment.CONFIRMED, (DataSlice) null, OWNER, TOKEN_PROGRAM).join();
    assertEquals(List.of(165, 165, 165, 165), whole.stream().map(account -> account.data().length).toList());

    // synthetic slot: the public nodes never answered a delegate query
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByDelegate","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"mint":"73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3"},{"commitment":"processed","encoding":"base64","minContextSlot":18446744073709551615}]}""", """
        {"jsonrpc":"2.0","error":{"code":-32016,"message":"Minimum context slot has not been reached","data":{"contextSlot":517570697}},"id":1}""");
    final var exception = joinExpectingRpcException(() ->
        rpcClient.getTokenAccountsForTokenMintByDelegate(Commitment.PROCESSED, U64_MAX, OWNER, MINT).join());
    assertEquals(517_570_697L, assertInstanceOf(RpcCustomError.MinContextSlotNotReached.class, exception.customError()).contextSlot());
  }

  /// The released overloads keep their bodies, byte for byte, and their decoder.
  @Test
  void theReleasedOverloadsAreUnchanged() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"mint":"73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3"},{"commitment":"confirmed","encoding":"base64"}]}""",
        RECENT_RESPONSE);
    assertEquals(29_114_561_481L, rpcClient.getTokenAccountsForTokenMintByOwner(OWNER, MINT).join().get(1).data().amount());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByDelegate","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},{"commitment":"finalized","encoding":"base64"}]}""",
        ZERO_RESPONSE);
    assertEquals(28_897_828_394L, rpcClient.getTokenAccountsForProgramByDelegate(Commitment.FINALIZED, OWNER, TOKEN_PROGRAM).join().get(1).data().amount());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},{"commitment":"confirmed","encoding":"base64"}]}""",
        ZERO_RESPONSE);
    assertEquals(FIRST, rpcClient.getTokenAccountsForProgramByOwner(OWNER, TOKEN_PROGRAM).join().getFirst().pubKey());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByDelegate","params":["H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA",{"mint":"73Wjd8N2ku7EhbgRT6oLvA5PXgZ79q1kTTapN9qxuUc3"},{"commitment":"confirmed","encoding":"base64"}]}""",
        RECENT_RESPONSE);
    assertEquals(LAST, rpcClient.getTokenAccountsForTokenMintByDelegate(OWNER, MINT).join().getLast().pubKey());
  }

  /// Every convenience overload of `getTokenAccountsForTokenMintByOwner` delegates to the
  /// five-argument one: each must render exactly the options it names and the client's commitment
  /// where it takes none, and hand back the bytes.
  @Test
  void everyOwnerByMintConvenienceRendersItsOptions() {
    everyConvenience("getTokenAccountsByOwner", "mint", MINT, new Family() {
      public List<AccountInfo<byte[]>> minimum(final BigInteger min, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForTokenMintByOwner(min, a, b).join(); }
      public List<AccountInfo<byte[]>> minimum(final Commitment c, final BigInteger min, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForTokenMintByOwner(c, min, a, b).join(); }
      public List<AccountInfo<byte[]>> slice(final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForTokenMintByOwner(s, a, b).join(); }
      public List<AccountInfo<byte[]>> slice(final Commitment c, final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForTokenMintByOwner(c, s, a, b).join(); }
      public List<AccountInfo<byte[]>> both(final Commitment c, final BigInteger min, final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForTokenMintByOwner(c, min, s, a, b).join(); }
    });
  }

  @Test
  void everyOwnerByProgramConvenienceRendersItsOptions() {
    everyConvenience("getTokenAccountsByOwner", "programId", TOKEN_PROGRAM, new Family() {
      public List<AccountInfo<byte[]>> minimum(final BigInteger min, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForProgramByOwner(min, a, b).join(); }
      public List<AccountInfo<byte[]>> minimum(final Commitment c, final BigInteger min, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForProgramByOwner(c, min, a, b).join(); }
      public List<AccountInfo<byte[]>> slice(final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForProgramByOwner(s, a, b).join(); }
      public List<AccountInfo<byte[]>> slice(final Commitment c, final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForProgramByOwner(c, s, a, b).join(); }
      public List<AccountInfo<byte[]>> both(final Commitment c, final BigInteger min, final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForProgramByOwner(c, min, s, a, b).join(); }
    });
  }

  @Test
  void everyDelegateByMintConvenienceRendersItsOptions() {
    everyConvenience("getTokenAccountsByDelegate", "mint", MINT, new Family() {
      public List<AccountInfo<byte[]>> minimum(final BigInteger min, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForTokenMintByDelegate(min, a, b).join(); }
      public List<AccountInfo<byte[]>> minimum(final Commitment c, final BigInteger min, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForTokenMintByDelegate(c, min, a, b).join(); }
      public List<AccountInfo<byte[]>> slice(final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForTokenMintByDelegate(s, a, b).join(); }
      public List<AccountInfo<byte[]>> slice(final Commitment c, final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForTokenMintByDelegate(c, s, a, b).join(); }
      public List<AccountInfo<byte[]>> both(final Commitment c, final BigInteger min, final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForTokenMintByDelegate(c, min, s, a, b).join(); }
    });
  }

  @Test
  void everyDelegateByProgramConvenienceRendersItsOptions() {
    everyConvenience("getTokenAccountsByDelegate", "programId", TOKEN_PROGRAM, new Family() {
      public List<AccountInfo<byte[]>> minimum(final BigInteger min, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForProgramByDelegate(min, a, b).join(); }
      public List<AccountInfo<byte[]>> minimum(final Commitment c, final BigInteger min, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForProgramByDelegate(c, min, a, b).join(); }
      public List<AccountInfo<byte[]>> slice(final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForProgramByDelegate(s, a, b).join(); }
      public List<AccountInfo<byte[]>> slice(final Commitment c, final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForProgramByDelegate(c, s, a, b).join(); }
      public List<AccountInfo<byte[]>> both(final Commitment c, final BigInteger min, final DataSlice s, final PublicKey a, final PublicKey b) { return rpcClient.getTokenAccountsForProgramByDelegate(c, min, s, a, b).join(); }
    });
  }

  /// One family's five overloads, as the test calls them.
  private interface Family {
    List<AccountInfo<byte[]>> minimum(BigInteger min, PublicKey a, PublicKey b);

    List<AccountInfo<byte[]>> minimum(Commitment c, BigInteger min, PublicKey a, PublicKey b);

    List<AccountInfo<byte[]>> slice(DataSlice s, PublicKey a, PublicKey b);

    List<AccountInfo<byte[]>> slice(Commitment c, DataSlice s, PublicKey a, PublicKey b);

    List<AccountInfo<byte[]>> both(Commitment c, BigInteger min, DataSlice s, PublicKey a, PublicKey b);
  }

  private void everyConvenience(final String method, final String key, final PublicKey value, final Family family) {
    registerRequest(body(method, key, value, options(Commitment.CONFIRMED, RECENT_MIN)), RECENT_RESPONSE);
    assertEquals(29_114_561_481L, decode(family.minimum(RECENT, OWNER, value).get(1)).amount());

    registerRequest(body(method, key, value, options(Commitment.FINALIZED, RECENT_MIN)), ZERO_RESPONSE);
    assertEquals(28_897_828_394L, decode(family.minimum(Commitment.FINALIZED, RECENT, OWNER, value).get(1)).amount());

    registerRequest(body(method, key, value, options(Commitment.CONFIRMED, WINDOW_JSON)), SLICE_RESPONSE);
    assertEquals(29_557_476_512L, amount(family.slice(WINDOW, OWNER, value).get(1).data()));

    registerRequest(body(method, key, value, options(Commitment.PROCESSED, WINDOW_JSON)), SLICE_RESPONSE);
    assertEquals(8, family.slice(Commitment.PROCESSED, WINDOW, OWNER, value).getFirst().data().length);

    registerRequest(body(method, key, value, options(Commitment.FINALIZED, RECENT_MIN, WINDOW_JSON)), SLICE_RESPONSE);
    final var windows = family.both(Commitment.FINALIZED, RECENT, WINDOW, OWNER, value);
    assertEquals(29_557_476_512L, amount(windows.get(1).data()));
    assertEquals(0L, amount(windows.getLast().data()));
  }
}
