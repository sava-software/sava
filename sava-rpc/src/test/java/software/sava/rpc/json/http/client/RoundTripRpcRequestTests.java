package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.BlockTxDetails;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.LargestAccountsFilter;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.Context;
import software.sava.rpc.json.http.response.IxError;
import software.sava.rpc.json.http.response.TransactionError;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.client.ParseRpcResponseTests.readFileString;

final class RoundTripRpcRequestTests extends RpcRequestTests {

  @Test
  void getHealth() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getHealth"}""", """
        {"jsonrpc":"2.0","error":{"code":-32005,"message":"Node is unhealthy","data":{"numSlotsBehind":null}},"id":1698251465713}"""
    );

    final var nodeHealth = rpcClient.getHealth().join();
    assertEquals(-32005, nodeHealth.code());
    assertEquals(0, nodeHealth.numSlotsBehind());
    assertEquals("Node is unhealthy", nodeHealth.message());

    // Skip response handling.
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getHealth"}""");
    assertNull(rpcClient.getHealth().join());
  }

  @Test
  void getAccountInfo() {
    registerRequest("""
        {"jsonrpc":"2.0","id":101,"method":"getAccountInfo","params":["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r",{"encoding":"base64","commitment":"confirmed","minContextSlot":1000,"dataSlice":{"length":32,"offset":88}}]}""", """
        {"jsonrpc":"2.0","id":1742866771486,"result":{"context":{"slot":328984397,"apiVersion":"2.1.9"},"value":{"lamports":7182720,"data":["CR5z0XpVJtRI5Ymupa/nwizWHFtmqGpCerJiMJUU5Vw=","base64"],"owner":"LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo","executable":false,"rentEpoch":18446744073709551615,"space":904}}}"""
    );

    registerRequest("""
        {"jsonrpc":"2.0","id":102,"method":"getAccountInfo","params":["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r",{"encoding":"base64","commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","id":1742867256946,"result":{"context":{"slot":328985609,"apiVersion":"2.1.9"},"value":{"lamports":7182720,"data":["IQsxYrVlsQ0QJx4AWAKIE8DUAQDgkwQAeFX+/4iqAQD0AQAAAAAAALoEAAC6BAAAIEIAAAAAAACZCuJnAAAAAAAAAAAAAAAA/QQAACBCAAAEAAABECcAAAkec9F6VSbUSOWJrqWv58Is1hxbZqhqQnqyYjCVFOVcxvp6877brTo9ZfNqq8l0MbG75MLS9uDkfKYCA0UvXWFoEybzQegBQQUKNAmPyVJWyzXz5ImKF+3P+3n3XueHs4Ivcaabwzb5Xwz716AtZNyfeliY7G4hkFwJfcvp+vHCiFs8AAAAAADRw+TPAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIN67mXD/3ERICDqyDFckHxEM9KyOJy5hDgOnlZKbrLsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPD//w8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAhqamcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC4v0fKdeC+80liZjCG34Ji0oU0a0dkfbAIYIU61nxeLgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==","base64"],"owner":"LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo","executable":false,"rentEpoch":18446744073709551615,"space":904}}}"""
    );

    var accountInfo = rpcClient.getAccountInfo(
        BigInteger.valueOf(1000),
        32, 88,
        PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r")
    ).join();

    assertEquals(new Context(328984397, "2.1.9"), accountInfo.context());
    assertEquals("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r", accountInfo.pubKey().toBase58());
    assertEquals("LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo", accountInfo.owner().toBase58());
    byte[] data = accountInfo.data();
    assertEquals(32, data.length);
    assertEquals("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", PublicKey.readPubKey(data).toString());

    accountInfo = rpcClient.getAccountInfo(
        PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r")
    ).join();

    assertEquals(new Context(328985609, "2.1.9"), accountInfo.context());
    assertEquals("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r", accountInfo.pubKey().toBase58());
    assertEquals("LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo", accountInfo.owner().toBase58());
    data = accountInfo.data();
    assertEquals(904, data.length);
    assertEquals("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", PublicKey.readPubKey(data, 88).toString());
  }

  private static void validateMultipleAccounts(final List<AccountInfo<byte[]>> accounts) {
    final var rentEpoch = new BigInteger("18446744073709551615");
    var accountInfo = accounts.getFirst();
    assertEquals("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r", accountInfo.pubKey().toBase58());
    assertEquals("LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo", accountInfo.owner().toBase58());
    assertEquals(rentEpoch, accountInfo.rentEpoch());
    assertEquals(7182720, accountInfo.lamports());

    final var context = accountInfo.context();
    assertEquals("2.1.9", context.apiVersion());
    accounts.stream().skip(1)
        .filter(Objects::nonNull)
        .map(AccountInfo::context)
        .forEach(_context -> assertEquals(context, _context));

    accountInfo = accounts.getLast();
    assertEquals("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6", accountInfo.pubKey().toBase58());
    assertEquals("LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo", accountInfo.owner().toBase58());
    assertEquals(rentEpoch, accountInfo.rentEpoch());
    assertEquals(161492326, accountInfo.lamports());

    for (final var account : accounts) {
      if (account != null) {
        assertFalse(account.executable());
      }
    }
  }

  private void validateCompleteMultipleAccounts(final List<AccountInfo<byte[]>> accounts) {
    validateMultipleAccounts(accounts);

    var accountInfo = accounts.getFirst();
    byte[] data = accountInfo.data();
    assertEquals(904, data.length);
    assertEquals(accountInfo.space(), data.length);
    assertEquals("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", PublicKey.readPubKey(data, 88).toString());

    accountInfo = accounts.getLast();
    data = accountInfo.data();
    assertEquals(904, data.length);
    assertEquals(accountInfo.space(), data.length);
    assertEquals("So11111111111111111111111111111111111111112", PublicKey.readPubKey(data, 88).toBase58());
  }

  @Test
  void getMultipleAccounts() {
    registerRequest("""
        {"jsonrpc":"2.0","id":201,"method":"getMultipleAccounts","params":[["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r","5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6"],{"encoding":"base64","commitment":"confirmed","minContextSlot":1000,"dataSlice":{"length":32,"offset":88}}]}""", """
        {"jsonrpc":"2.0","id":1742865650193,"result":{"context":{"slot":328981577,"apiVersion":"2.1.9"},"value":[{"lamports":7182720,"data":["CR5z0XpVJtRI5Ymupa/nwizWHFtmqGpCerJiMJUU5Vw=","base64"],"owner":"LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo","executable":false,"rentEpoch":18446744073709551615,"space":904},{"lamports":161492326,"data":["BpuIV/6rgYT7aH9jRhjANdrEOdwa6ztVmKDwAAAAAAE=","base64"],"owner":"LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo","executable":false,"rentEpoch":18446744073709551615,"space":904}]}}"""
    );

    var accounts = rpcClient.getAccounts(
        Commitment.CONFIRMED,
        BigInteger.valueOf(1000),
        32, 88,
        List.of(
            PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r"),
            PublicKey.fromBase58Encoded("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6")
        )
    ).join();

    assertEquals(2, accounts.size());
    validateMultipleAccounts(accounts);
    var accountInfo = accounts.getFirst();
    byte[] data = accountInfo.data();
    assertEquals(32, data.length);
    assertEquals("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", PublicKey.readPubKey(data).toString());

    accountInfo = accounts.getLast();
    data = accountInfo.data();
    assertEquals(32, data.length);
    assertEquals("So11111111111111111111111111111111111111112", PublicKey.readPubKey(data).toBase58());
    assertEquals(328981577, accountInfo.context().slot());

    registerRequest("""
            {"jsonrpc":"2.0","id":202,"method":"getMultipleAccounts","params":[["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r","5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6"],{"encoding":"base64","commitment":"confirmed"}]}""",
        readFileString("getMultipleAccounts_1.json")
    );

    accounts = rpcClient.getAccounts(
        List.of(
            PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r"),
            PublicKey.fromBase58Encoded("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6")
        )
    ).join();

    assertEquals(2, accounts.size());
    validateCompleteMultipleAccounts(accounts);
    assertEquals(328983133, accounts.getFirst().context().slot());

    registerRequest("""
            {"jsonrpc":"2.0","id":203,"method":"getMultipleAccounts","params":[["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r","11111111111111111111111111111111","5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6"],{"encoding":"base64","commitment":"finalized"}]}""",
        readFileString("getMultipleAccounts_2.json"),
        2
    );

    accounts = rpcClient.getMultipleAccounts(
        Commitment.FINALIZED,
        List.of(
            PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r"),
            PublicKey.NONE,
            PublicKey.fromBase58Encoded("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6")
        )
    ).join();

    assertEquals(2, accounts.size());
    validateCompleteMultipleAccounts(accounts);
    assertEquals(328983133, accounts.getFirst().context().slot());

    accounts = rpcClient.getAccounts(
        Commitment.FINALIZED,
        List.of(
            PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r"),
            PublicKey.NONE,
            PublicKey.fromBase58Encoded("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6")
        )
    ).join();

    assertEquals(3, accounts.size());
    assertNull(accounts.get(1));
    validateCompleteMultipleAccounts(accounts);
    assertEquals(328983133, accounts.getFirst().context().slot());
  }

  @Test
  void getInflationReward() {
    registerRequest("""
        {"jsonrpc":"2.0","id":301,"method":"getInflationReward","params":[["BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g"],{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":[{"amount":1854511658,"commission":5,"commissionBps":500,"effectiveSlot":338256000,"epoch":782,"postBalance":2178854057}],"id":1746563243745}"""
    );

    var inflationRewards = rpcClient.getInflationReward(List.of(
            PublicKey.fromBase58Encoded("BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g")
        )
    ).join();
    assertEquals(1, inflationRewards.size());
    var inflationReward = inflationRewards.getFirst();
    assertEquals(1854511658, inflationReward.amount());
    // Basis points supersede the percentage.
    assertEquals(500, inflationReward.commission());
    assertTrue(inflationReward.commissionBps());
    assertEquals(338256000, inflationReward.effectiveSlot());
    assertEquals(782, inflationReward.epoch());
    assertEquals(2178854057L, inflationReward.postBalance());

    registerRequest("""
        {"jsonrpc":"2.0","id":302,"method":"getInflationReward","params":[["BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g"],{"commitment":"confirmed","epoch":781}]}""", """
        {"jsonrpc":"2.0","result":[{"amount":1940761929,"commission":5,"effectiveSlot":337824000,"epoch":781,"postBalance":1967836329}],"id":1746563243746}"""
    );

    inflationRewards = rpcClient.getInflationReward(List.of(
            PublicKey.fromBase58Encoded("BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g")
        ), inflationRewards.getFirst().epoch() - 1
    ).join();
    assertEquals(1, inflationRewards.size());
    inflationReward = inflationRewards.getFirst();
    assertEquals(1940761929, inflationReward.amount());
    assertEquals(5, inflationReward.commission());
    assertFalse(inflationReward.commissionBps());
    assertEquals(337824000, inflationReward.effectiveSlot());
    assertEquals(781, inflationReward.epoch());
    assertEquals(1967836329L, inflationReward.postBalance());

    registerRequest("""
        {"jsonrpc":"2.0","id":303,"method":"getInflationReward","params":[["BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g"],{"commitment":"confirmed","minContextSlot":338256000}]}""", """
        {"jsonrpc":"2.0","result":[{"amount":1854511658,"commission":5,"effectiveSlot":338256000,"epoch":782,"postBalance":2178854057}],"id":1746563243747}"""
    );

    inflationRewards = rpcClient.getInflationReward(Commitment.CONFIRMED, List.of(
            PublicKey.fromBase58Encoded("BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g")
        ), BigInteger.valueOf(338256000)
    ).join();
    assertEquals(1, inflationRewards.size());
    assertEquals(782, inflationRewards.getFirst().epoch());
  }

  /// Recorded against devnet on 2026-07-13, where SIMD-0291 is active: the commission is served
  /// only in basis points.
  @Test
  void getInflationRewardWithCommissionBasisPoints() {
    registerRequest("""
        {"jsonrpc":"2.0","id":305,"method":"getInflationReward","params":[["vgcDar2pryHvMgPkKaZfh8pQy4BJxv7SpwUG7zinWjG"],{"commitment":"finalized","epoch":1100}]}""", """
        {"jsonrpc":"2.0","result":[{"amount":51726733407728,"commission":null,"commissionBps":9500,"effectiveSlot":475632000,"epoch":1100,"postBalance":23722174330619752}],"id":1}"""
    );

    final var inflationRewards = rpcClient.getInflationReward(Commitment.FINALIZED, List.of(
            PublicKey.fromBase58Encoded("vgcDar2pryHvMgPkKaZfh8pQy4BJxv7SpwUG7zinWjG")
        ), 1_100
    ).join();

    assertEquals(1, inflationRewards.size());
    final var inflationReward = inflationRewards.getFirst();
    assertEquals(1_100, inflationReward.epoch());
    assertEquals(475_632_000L, inflationReward.effectiveSlot());
    assertEquals(51_726_733_407_728L, inflationReward.amount());
    assertEquals(23_722_174_330_619_752L, inflationReward.postBalance());
    // The percentage is served as null by nodes which serve basis points.
    assertEquals(9_500, inflationReward.commission());
    assertTrue(inflationReward.commissionBps());
  }

  /// JSON object members are unordered: the basis points take precedence over the percentage
  /// whichever is served first.
  @Test
  void getInflationRewardPrefersCommissionBasisPoints() {
    final var request = """
        {"jsonrpc":"2.0","id":306,"method":"getInflationReward","params":[["CcaHc2L43ZWjwCHART3oZoJvHLAe9hzT2DJNUpBzoTN1"],{"commitment":"confirmed"}]}""";
    final var votePubKey = PublicKey.fromBase58Encoded("CcaHc2L43ZWjwCHART3oZoJvHLAe9hzT2DJNUpBzoTN1");

    registerRequest(request, """
        {"jsonrpc":"2.0","result":[{"amount":1854511658,"commission":5,"commissionBps":500,"effectiveSlot":338256000,"epoch":782,"postBalance":2178854057}],"id":1}"""
    );
    var inflationReward = rpcClient.getInflationReward(List.of(votePubKey)).join().getFirst();
    assertEquals(500, inflationReward.commission());
    assertTrue(inflationReward.commissionBps());

    registerRequest(request, """
        {"jsonrpc":"2.0","result":[{"amount":1854511658,"commissionBps":500,"commission":5,"effectiveSlot":338256000,"epoch":782,"postBalance":2178854057}],"id":1}"""
    );
    inflationReward = rpcClient.getInflationReward(List.of(votePubKey)).join().getFirst();
    assertEquals(500, inflationReward.commission());
    assertTrue(inflationReward.commissionBps());
  }

  /// Recorded against mainnet on 2026-07-13 for the largest staked validator.
  @Test
  void getInflationRewardWithEpochAndMinContextSlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":304,"method":"getInflationReward","params":[["CcaHc2L43ZWjwCHART3oZoJvHLAe9hzT2DJNUpBzoTN1"],{"commitment":"finalized","epoch":1000,"minContextSlot":1}]}""", """
        {"jsonrpc":"2.0","id":1783954356076,"result":[{"epoch":1000,"effectiveSlot":432432000,"amount":342915536456,"postBalance":5851653580287,"commission":7}]}"""
    );

    final var inflationRewards = rpcClient.getInflationReward(Commitment.FINALIZED, List.of(
            PublicKey.fromBase58Encoded("CcaHc2L43ZWjwCHART3oZoJvHLAe9hzT2DJNUpBzoTN1")
        ), 1_000, BigInteger.ONE
    ).join();

    assertEquals(1, inflationRewards.size());
    final var inflationReward = inflationRewards.getFirst();
    assertEquals(1_000, inflationReward.epoch());
    assertEquals(432_432_000L, inflationReward.effectiveSlot());
    assertEquals(342_915_536_456L, inflationReward.amount());
    assertEquals(5_851_653_580_287L, inflationReward.postBalance());
    assertEquals(7, inflationReward.commission());
    assertFalse(inflationReward.commissionBps());
  }

  @Test
  void unorderedContextAndValue() {
    final var account = PublicKey.fromBase58Encoded("BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g");
    final var request = """
        {"jsonrpc":"2.0","id":701,"method":"getBalance","params":["BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g",{"commitment":"confirmed"}]}""";

    // Solana RPC nodes serve the context first.
    registerRequest(request, """
        {"jsonrpc":"2.0","result":{"context":{"slot":432480512,"apiVersion":"2.1.9"},"value":123456},"id":1}"""
    );
    var lamports = rpcClient.getBalance(account).join();
    assertEquals(432480512L, lamports.context().slot());
    assertEquals("2.1.9", lamports.context().apiVersion());
    assertEquals(123456, lamports.lamports());

    // JSON object members are unordered, e.g. the compression indexer's getValidityProof.
    registerRequest(request, """
        {"jsonrpc":"2.0","result":{"value":123456,"context":{"slot":432480512,"apiVersion":"2.1.9"}},"id":1}"""
    );
    lamports = rpcClient.getBalance(account).join();
    assertEquals(432480512L, lamports.context().slot());
    assertEquals("2.1.9", lamports.context().apiVersion());
    assertEquals(123456, lamports.lamports());

    // Unknown fields are skipped regardless of their position.
    registerRequest(request, """
        {"jsonrpc":"2.0","result":{"unknown":{"value":1},"value":123456,"context":{"slot":432480512}},"id":1}"""
    );
    lamports = rpcClient.getBalance(account).join();
    assertEquals(432480512L, lamports.context().slot());
    assertEquals(123456, lamports.lamports());
  }

  @Test
  void getLargestAccountsWithFilter() {
    registerRequest("""
        {"jsonrpc":"2.0","id":401,"method":"getLargestAccounts","params":[{"commitment":"confirmed","filter":"circulating"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":54},"value":[{"lamports":999974,"address":"99P8ZgtJYe1buSK8JXkvpLh8xPsCFuLYhz9hQFNw93WJ"}]},"id":1}"""
    );

    final var accounts = rpcClient.getLargestAccounts(LargestAccountsFilter.CIRCULATING).join();
    assertEquals(1, accounts.size());
    final var account = accounts.getFirst();
    assertEquals("99P8ZgtJYe1buSK8JXkvpLh8xPsCFuLYhz9hQFNw93WJ", account.addressKey().toBase58());
    assertEquals(999974, account.lamports());
  }

  /// Recorded against mainnet on 2026-07-13 for the largest staked validator.
  @Test
  void getVoteAccountsWithDelinquentParams() {
    registerRequest("""
        {"jsonrpc":"2.0","id":501,"method":"getVoteAccounts","params":[{"commitment":"confirmed","votePubkey":"CcaHc2L43ZWjwCHART3oZoJvHLAe9hzT2DJNUpBzoTN1","keepUnstakedDelinquents":true,"delinquentSlotDistance":128}]}""", """
        {"jsonrpc":"2.0","result":{"current":[{"activatedStake":16395628327252359,"commission":7,"epochCredits":[[997,2287361651,2280478188],[998,2294237959,2287361651],[999,2301133228,2294237959],[1000,2308040079,2301133228],[1001,2311757453,2308040079]],"epochVoteAccount":true,"inflationRewardsCommissionBps":700,"lastVote":432664432,"nodePubkey":"Fd7btgySsrjuo25CJCj7oE7VPMyezDhnx7pZkj2v69Nk","rootSlot":432664401,"votePubkey":"CcaHc2L43ZWjwCHART3oZoJvHLAe9hzT2DJNUpBzoTN1"}],"delinquent":[]},"id":1783954356074}"""
    );

    final var voteAccounts = rpcClient.getVoteAccounts(
        Commitment.CONFIRMED,
        PublicKey.fromBase58Encoded("CcaHc2L43ZWjwCHART3oZoJvHLAe9hzT2DJNUpBzoTN1"),
        true,
        BigInteger.valueOf(128)
    ).join();

    assertTrue(voteAccounts.delinquent().isEmpty());
    assertEquals(1, voteAccounts.current().size());
    final var voteAccount = voteAccounts.current().getFirst();
    assertEquals("CcaHc2L43ZWjwCHART3oZoJvHLAe9hzT2DJNUpBzoTN1", voteAccount.voteKey().toBase58());
    assertEquals("Fd7btgySsrjuo25CJCj7oE7VPMyezDhnx7pZkj2v69Nk", voteAccount.nodeKey().toBase58());
    assertEquals(16_395_628_327_252_359L, voteAccount.activatedStake());
    assertTrue(voteAccount.epochVoteAccount());
    assertEquals(7, voteAccount.commission());
    assertEquals(OptionalInt.of(700), voteAccount.inflationRewardsCommissionBps());
    assertEquals(432_664_432L, voteAccount.lastVote());
    assertEquals(432_664_401L, voteAccount.rootSlot());
    assertEquals(5, voteAccount.epochCredits().size());
    final var epochCredits = voteAccount.epochCredits().getLast();
    assertEquals(1_001, epochCredits.epoch());
    assertEquals(2_311_757_453L, epochCredits.credits());
    assertEquals(2_308_040_079L, epochCredits.previousCredits());
  }

  @Test
  void getSignaturesForAddressWithMinContextSlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":601,"method":"getSignaturesForAddress","params":["Vote111111111111111111111111111111111111111",{"commitment":"confirmed","limit":10,"before":"5nLi8m72bU6PBcz4Xrk23P6KTGy9ufF92kZiQXjTv9ELgkUxrNaiCGhMF4vh6RAcisw9DEQWJt9ogM3G2uCuwwV7","until":"323Ag4J69gagBt3neUvajNauMydiXZTmXYSfdK5swWcK1iwCUypcXv45UFcy5PTt136G9gtQ45oyPJRs1f2zFZ3v","minContextSlot":1000}]}""", """
        {"jsonrpc":"2.0","result":[],"id":1}"""
    );

    final var signatures = rpcClient.getSignaturesForAddress(
        Commitment.CONFIRMED,
        PublicKey.fromBase58Encoded("Vote111111111111111111111111111111111111111"),
        10,
        "5nLi8m72bU6PBcz4Xrk23P6KTGy9ufF92kZiQXjTv9ELgkUxrNaiCGhMF4vh6RAcisw9DEQWJt9ogM3G2uCuwwV7",
        "323Ag4J69gagBt3neUvajNauMydiXZTmXYSfdK5swWcK1iwCUypcXv45UFcy5PTt136G9gtQ45oyPJRs1f2zFZ3v",
        BigInteger.valueOf(1000)
    ).join();
    assertTrue(signatures.isEmpty());
  }

  /// Recorded against mainnet on 2026-07-13. Optional parameters are omitted from the request,
  /// and the transactions of the response failed.
  @Test
  void getSignaturesForAddressWithoutOptionalParams() {
    registerRequest("""
        {"jsonrpc":"2.0","id":602,"method":"getSignaturesForAddress","params":["JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4",{"commitment":"confirmed","limit":2}]}""", """
        {"jsonrpc":"2.0","id":1783954356075,"result":[{"signature":"37WCFMoxTUHGp7TtRYLcZFKiv984i6gKzEWUMh7mMJM5CWTHvjmgZHycJBxQkEF8ULfnNouHy4M24XYx3beBCQXE","slot":432664433,"err":{"InstructionError":[3,{"Custom":1}]},"memo":null,"blockTime":1783954355,"confirmationStatus":"confirmed"},{"signature":"3gXKAM4fjZn9uWr2kyKtFwNYwXateenhqrPo6LczbL3iZETEiYxKaMqEKhSz4mXFT4SvTYC8FpEAmnCxNNprF8Mi","slot":432664433,"err":{"InstructionError":[3,{"Custom":1}]},"memo":null,"blockTime":1783954355,"confirmationStatus":"confirmed"}]}"""
    );

    final var signatures = rpcClient.getSignaturesForAddress(
        Commitment.CONFIRMED,
        PublicKey.fromBase58Encoded("JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"),
        2,
        null,
        null,
        null
    ).join();

    assertEquals(2, signatures.size());
    final var signature = signatures.getFirst();
    assertEquals("37WCFMoxTUHGp7TtRYLcZFKiv984i6gKzEWUMh7mMJM5CWTHvjmgZHycJBxQkEF8ULfnNouHy4M24XYx3beBCQXE", signature.signature());
    assertEquals(432_664_433L, signature.slot());
    assertEquals(1_783_954_355L, signature.blockTime().orElseThrow());
    assertEquals(Commitment.CONFIRMED, signature.confirmationStatus());
    assertNull(signature.memo());

    final var instructionError = assertInstanceOf(TransactionError.InstructionError.class, signature.transactionError());
    assertEquals(3, instructionError.ixIndex());
    final var customError = assertInstanceOf(IxError.Custom.class, instructionError.ixError());
    assertEquals(1, customError.error());

    // Not served by every node, e.g. providers which index signatures themselves.
    assertTrue(signature.transactionIndex().isEmpty());
  }

  /// Recorded against the public mainnet node on 2026-07-13, which serves the transaction index.
  @Test
  void getSignaturesForAddressWithTransactionIndex() {
    registerRequest("""
        {"jsonrpc":"2.0","id":603,"method":"getSignaturesForAddress","params":["Vote111111111111111111111111111111111111111",{"commitment":"confirmed","limit":2}]}""", """
        {"jsonrpc":"2.0","result":[{"blockTime":1783956442,"confirmationStatus":"confirmed","err":null,"memo":null,"signature":"syquLMPbA3Td2BCBxTkRgjUtwEJhpxUyBXcS6Pczf4zW1AXKWMARUDoRdGvVXGvH2rsFYqFBWgd6cjre8L4uFH5","slot":432669498,"transactionIndex":1236},{"blockTime":1783956442,"confirmationStatus":"confirmed","err":null,"memo":null,"signature":"2wGjjWacyNDcDTJoD6wvMcnEmq9Fvwx22RvnCf1AA8Uc71GTEc3FdYpndEA3BW49xa3D6zh4NtAMVZSpLJyFZxjg","slot":432669498,"transactionIndex":1235}],"id":1}"""
    );

    final var signatures = rpcClient.getSignaturesForAddress(
        Commitment.CONFIRMED,
        PublicKey.fromBase58Encoded("Vote111111111111111111111111111111111111111"),
        2
    ).join();

    assertEquals(2, signatures.size());
    final var signature = signatures.getFirst();
    assertEquals(432_669_498L, signature.slot());
    assertEquals(OptionalInt.of(1_236), signature.transactionIndex());
    assertNull(signature.transactionError());
    assertEquals(OptionalInt.of(1_235), signatures.getLast().transactionIndex());
  }

  @Test
  void getBalance() {
    registerRequest("""
        {"jsonrpc":"2.0","id":701,"method":"getBalance","params":["83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri",{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":1},"value":88849814690250},"id":1}"""
    );

    final var balance = rpcClient.getBalance(
        PublicKey.fromBase58Encoded("83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri")
    ).join();
    assertEquals(88849814690250L, balance.lamports());
    assertEquals(1L, balance.context().slot());
  }

  @Test
  void getBlockHeight() {
    registerRequest("""
        {"jsonrpc":"2.0","id":711,"method":"getBlockHeight","params":[{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":1233,"id":1}"""
    );

    assertEquals(1233L, rpcClient.getBlockHeight().join().height());
  }

  @Test
  void getBlocks() {
    registerRequest("""
        {"jsonrpc":"2.0","id":721,"method":"getBlocks","params":[5,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":[5,6,7,8,9,10],"id":1}"""
    );
    assertArrayEquals(new long[]{5, 6, 7, 8, 9, 10}, rpcClient.getBlocks(5).join());

    registerRequest("""
        {"jsonrpc":"2.0","id":722,"method":"getBlocks","params":[5,10,{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":[5,6,7,8,9,10],"id":1}"""
    );
    assertArrayEquals(new long[]{5, 6, 7, 8, 9, 10}, rpcClient.getBlocks(Commitment.FINALIZED, 5, 10).join());

    // The end slot is clamped to a range of 500,000 slots.
    registerRequest("""
        {"jsonrpc":"2.0","id":723,"method":"getBlocks","params":[5,500005,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":[],"id":1}"""
    );
    assertEquals(0, rpcClient.getBlocks(5, 600_000).join().length);
  }

  @Test
  void getBlocksWithLimit() {
    registerRequest("""
        {"jsonrpc":"2.0","id":731,"method":"getBlocksWithLimit","params":[5,3,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":[5,6,7],"id":1}"""
    );
    assertArrayEquals(new long[]{5, 6, 7}, rpcClient.getBlocksWithLimit(5, 3).join());

    // The limit is clamped to 500,000 slots.
    registerRequest("""
        {"jsonrpc":"2.0","id":732,"method":"getBlocksWithLimit","params":[5,500000,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":[],"id":1}"""
    );
    assertEquals(0, rpcClient.getBlocksWithLimit(5, 600_000).join().length);
  }

  @Test
  void getBlockTime() {
    registerRequest("""
        {"jsonrpc":"2.0","id":741,"method":"getBlockTime","params":[5]}""", """
        {"jsonrpc":"2.0","result":1574721591,"id":1}"""
    );

    assertEquals(Instant.ofEpochSecond(1574721591L), rpcClient.getBlockTime(5).join());
  }

  @Test
  void getBlock() {
    registerRequest("""
        {"jsonrpc":"2.0","id":751,"method":"getBlock","params":[16384,{"encoding":"base64","commitment":"confirmed","transactionDetails":"none","rewards":true}]}""", """
        {"jsonrpc":"2.0","result":{"blockHeight":null,"blockTime":null,"blockhash":"4w2QK5udZJKwXhNcssAEc8mATt8o1ZnFQrzGJg9NGZpz","parentSlot":16383,"previousBlockhash":"PNRrabCwVfJmYdrxtKV4euA5daxsbFtYZEvcZWWxf2e","rewards":[]},"id":1}"""
    );

    var block = rpcClient.getBlock(16384).join();
    assertEquals("4w2QK5udZJKwXhNcssAEc8mATt8o1ZnFQrzGJg9NGZpz", block.blockHash());
    assertEquals(16383L, block.parentSlot());
    assertTrue(block.rewards().isEmpty());

    registerRequest("""
        {"jsonrpc":"2.0","id":752,"method":"getBlock","params":[328284371,{"encoding":"base64","commitment":"finalized","transactionDetails":"signatures","rewards":false}]}""", """
        {"jsonrpc":"2.0","result":{"blockHeight":306538416,"blockTime":1742587108,"blockhash":"4gmejZCH4Hokk3YWmZfKyV1Y1Yj2rkChnejHmEeLSJ3e","parentSlot":328284370,"previousBlockhash":"7q6NkbrNTXnYBA9iS7kHcSDPARbwN5tbG48ZrjWk1czR","signatures":["5rxL9uYfPTYf74JQvLxKTNr2iAzz99Cbdo4tAmWj8m3N85JBMsF6hnA1nWi2f3KsjYJVqGVTx45rgZHFgwjz2mg9"]},"id":1}"""
    );

    block = rpcClient.getBlock(Commitment.FINALIZED, 328284371, BlockTxDetails.signatures, false).join();
    assertEquals(306538416L, block.blockHeight());
    assertEquals(1, block.signatures().size());
    assertEquals("5rxL9uYfPTYf74JQvLxKTNr2iAzz99Cbdo4tAmWj8m3N85JBMsF6hnA1nWi2f3KsjYJVqGVTx45rgZHFgwjz2mg9", block.signatures().getFirst());
  }

  @Test
  void getBlockProductionForIdentity() {
    registerRequest("""
        {"jsonrpc":"2.0","id":761,"method":"getBlockProduction","params":[{"commitment":"confirmed","identity":"5gBsaKoU2AD2E19SQ6HhzpyNhHZUxRadX1BLEnFXY22c","range":{"firstSlot":361584000}}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"2.3.7","slot":361663684},"value":{"byIdentity":{"5gBsaKoU2AD2E19SQ6HhzpyNhHZUxRadX1BLEnFXY22c":[8,7]},"range":{"firstSlot":361584000,"lastSlot":361663684}}},"id":1}"""
    );

    final var identity = PublicKey.fromBase58Encoded("5gBsaKoU2AD2E19SQ6HhzpyNhHZUxRadX1BLEnFXY22c");
    var production = rpcClient.getBlockProduction(identity, 361584000L).join();
    assertEquals(361584000L, production.firstSlot());
    assertEquals(361663684L, production.lastSlot());
    final var info = production.leaderInfoMap().get(identity);
    assertEquals(8, info.numSlots());
    assertEquals(7, info.blocksProduced());

    registerRequest("""
        {"jsonrpc":"2.0","id":762,"method":"getBlockProduction","params":[{"commitment":"confirmed","range":{"firstSlot":361584000}}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"2.3.7","slot":361663684},"value":{"byIdentity":{"5gBsaKoU2AD2E19SQ6HhzpyNhHZUxRadX1BLEnFXY22c":[8,7]},"range":{"firstSlot":361584000,"lastSlot":361663684}}},"id":1}"""
    );

    production = rpcClient.getBlockProduction(361584000L).join();
    assertEquals(361584000L, production.firstSlot());
    assertEquals(1, production.leaderInfoMap().size());
  }

  @Test
  void getFeeForMessage() {
    final var base64Msg = "AQABAgIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEBAQAA";
    registerRequest("""
        {"jsonrpc":"2.0","id":771,"method":"getFeeForMessage","params":["%s",{"commitment":"confirmed"}]}""".formatted(base64Msg), """
        {"jsonrpc":"2.0","result":{"context":{"slot":5068},"value":5000},"id":1}"""
    );

    final var feeForMessage = rpcClient.getFeeForMessage(base64Msg).join();
    assertEquals(5000L, feeForMessage.fee());
    assertEquals(5068L, feeForMessage.context().slot());

    // A null value indicates the blockhash from the message has expired or is invalid.
    registerRequest("""
        {"jsonrpc":"2.0","id":772,"method":"getFeeForMessage","params":["%s",{"commitment":"finalized"}]}""".formatted(base64Msg), """
        {"jsonrpc":"2.0","result":{"context":{"slot":5068},"value":null},"id":1}"""
    );
    assertNull(rpcClient.getFeeForMessage(Commitment.FINALIZED, base64Msg).join());
  }

  @Test
  void getFirstAvailableBlock() {
    registerRequest("""
        {"jsonrpc":"2.0","id":781,"method":"getFirstAvailableBlock"}""", """
        {"jsonrpc":"2.0","result":250000,"id":1}"""
    );

    assertEquals(250000L, rpcClient.getFirstAvailableBlock().join());
  }

  @Test
  void getGenesisHash() {
    registerRequest("""
        {"jsonrpc":"2.0","id":791,"method":"getGenesisHash"}""", """
        {"jsonrpc":"2.0","result":"GH7ome3EiwEr7tu9JuTh2dpYWBJK3z69Xm1ZE3MEE6JC","id":1}"""
    );

    assertEquals("GH7ome3EiwEr7tu9JuTh2dpYWBJK3z69Xm1ZE3MEE6JC", rpcClient.getGenesisHash().join());
  }

  @Test
  void getLeaderSchedule() {
    final var identity = PublicKey.fromBase58Encoded("4Qkev8aNZcqFNSRhQzwyLMFSsi94jHqE8WNVTJzTP99F");

    registerRequest("""
        {"jsonrpc":"2.0","id":801,"method":"getLeaderSchedule","params":[null,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"4Qkev8aNZcqFNSRhQzwyLMFSsi94jHqE8WNVTJzTP99F":[0,1,2,3]},"id":1}"""
    );

    var schedule = rpcClient.getLeaderSchedule().join();
    assertEquals(1, schedule.size());
    assertArrayEquals(new long[]{0, 1, 2, 3}, schedule.get(identity));

    registerRequest("""
        {"jsonrpc":"2.0","id":802,"method":"getLeaderSchedule","params":[400,{"commitment":"confirmed","identity":"4Qkev8aNZcqFNSRhQzwyLMFSsi94jHqE8WNVTJzTP99F"}]}""", """
        {"jsonrpc":"2.0","result":{"4Qkev8aNZcqFNSRhQzwyLMFSsi94jHqE8WNVTJzTP99F":[0,1,2,3]},"id":1}"""
    );

    schedule = rpcClient.getLeaderSchedule(400, identity).join();
    assertEquals(1, schedule.size());
    assertArrayEquals(new long[]{0, 1, 2, 3}, schedule.get(identity));

    // A null result for an epoch corresponding to a slot which is not present.
    registerRequest("""
        {"jsonrpc":"2.0","id":803,"method":"getLeaderSchedule","params":[999999999,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":null,"id":1}"""
    );
    assertTrue(rpcClient.getLeaderSchedule(999999999L).join().isEmpty());
  }

  @Test
  void getMaxRetransmitSlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":811,"method":"getMaxRetransmitSlot"}""", """
        {"jsonrpc":"2.0","result":1234,"id":1}"""
    );
    assertEquals(1234L, rpcClient.getMaxRetransmitSlot().join());
  }

  @Test
  void getMaxShredInsertSlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":812,"method":"getMaxShredInsertSlot"}""", """
        {"jsonrpc":"2.0","result":1234,"id":1}"""
    );
    assertEquals(1234L, rpcClient.getMaxShredInsertSlot().join());
  }

  @Test
  void getMinimumBalanceForRentExemption() {
    registerRequest("""
        {"jsonrpc":"2.0","id":821,"method":"getMinimumBalanceForRentExemption","params":[50]}""", """
        {"jsonrpc":"2.0","result":1238880,"id":1}"""
    );
    assertEquals(1238880L, rpcClient.getMinimumBalanceForRentExemption(50).join());
  }

  @Test
  void getRecentPerformanceSamples() {
    // The limit is clamped to 720 samples.
    registerRequest("""
        {"jsonrpc":"2.0","id":831,"method":"getRecentPerformanceSamples","params":[720]}""", """
        {"jsonrpc":"2.0","result":[{"numNonVoteTransactions":65191,"numSlots":153,"numTransactions":225012,"samplePeriodSecs":60,"slot":361652365}],"id":1}"""
    );

    final var samples = rpcClient.getRecentPerformanceSamples(1_000).join();
    assertEquals(1, samples.size());
    final var sample = samples.getFirst();
    assertEquals(361652365L, sample.slot());
    assertEquals(153L, sample.numSlots());
    assertEquals(225012L, sample.numTransactions());
    assertEquals(65191L, sample.numNonVoteTransaction());
    assertEquals(60, sample.samplePeriodSecs());
  }

  @Test
  void getRecentPrioritizationFeesForAccounts() {
    registerRequest("""
        {"jsonrpc":"2.0","id":841,"method":"getRecentPrioritizationFees","params":[["CxELquR1gPP8wHe33gZ4QxqGB3sZ9RSwsJ2KshVewkFY"]]}""", """
        {"jsonrpc":"2.0","result":[{"slot":348125,"prioritizationFee":1000}],"id":1}"""
    );

    final var fees = rpcClient.getRecentPrioritizationFees(List.of(
        PublicKey.fromBase58Encoded("CxELquR1gPP8wHe33gZ4QxqGB3sZ9RSwsJ2KshVewkFY")
    )).join();
    assertEquals(1, fees.size());
    assertEquals(348125L, fees.getFirst().slot());
    assertEquals(1000L, fees.getFirst().prioritizationFee());
  }

  @Test
  void getSignaturesForAddressBeforeAndUntil() {
    final var address = PublicKey.fromBase58Encoded("Vote111111111111111111111111111111111111111");
    final var sig = "5nLi8m72bU6PBcz4Xrk23P6KTGy9ufF92kZiQXjTv9ELgkUxrNaiCGhMF4vh6RAcisw9DEQWJt9ogM3G2uCuwwV7";

    registerRequest("""
        {"jsonrpc":"2.0","id":851,"method":"getSignaturesForAddress","params":["Vote111111111111111111111111111111111111111",{"commitment":"confirmed","limit":25,"before":"%s"}]}""".formatted(sig), """
        {"jsonrpc":"2.0","result":[],"id":1}"""
    );
    assertTrue(rpcClient.getSignaturesForAddressBefore(address, 25, sig).join().isEmpty());

    registerRequest("""
        {"jsonrpc":"2.0","id":852,"method":"getSignaturesForAddress","params":["Vote111111111111111111111111111111111111111",{"commitment":"confirmed","limit":25,"until":"%s"}]}""".formatted(sig), """
        {"jsonrpc":"2.0","result":[],"id":1}"""
    );
    assertTrue(rpcClient.getSignaturesForAddressUntil(address, 25, sig).join().isEmpty());

    // The limit is clamped to 1,000 signatures.
    registerRequest("""
        {"jsonrpc":"2.0","id":853,"method":"getSignaturesForAddress","params":["Vote111111111111111111111111111111111111111",{"commitment":"confirmed","limit":1000}]}""", """
        {"jsonrpc":"2.0","result":[],"id":1}"""
    );
    assertTrue(rpcClient.getSignaturesForAddress(address, 5_000).join().isEmpty());
  }

  @Test
  void getSignatureStatusesRoundTrip() {
    final var sig1 = "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW";
    final var sig2 = "5j7s6NiJS3JAkvgkoc18WVAsiSaci2pxB2A6ueCJP4tprA2TFg9wSyTLeYouxPBJEMzJinENTkpA52YStRW5Dia7";

    registerRequest("""
        {"jsonrpc":"2.0","id":861,"method":"getSignatureStatuses","params":[["%s","%s"],{"searchTransactionHistory":true}]}""".formatted(sig1, sig2), """
        {"jsonrpc":"2.0","result":{"context":{"slot":82},"value":[{"slot":48,"confirmations":null,"err":null,"status":{"Ok":null},"confirmationStatus":"finalized"},null]},"id":1}"""
    );

    final var statusMap = rpcClient.getSignatureStatuses(List.of(sig1, sig2), true).join();
    assertEquals(2, statusMap.size());

    final var status = statusMap.get(sig1);
    assertEquals(48L, status.slot());
    assertTrue(status.confirmations().isEmpty());
    assertNull(status.error());
    assertEquals(Commitment.FINALIZED, status.confirmationStatus());

    assertTrue(statusMap.get(sig2).nil());
  }

  @Test
  void getSigStatusList() {
    final var sig1 = "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW";
    final var sig2 = "5j7s6NiJS3JAkvgkoc18WVAsiSaci2pxB2A6ueCJP4tprA2TFg9wSyTLeYouxPBJEMzJinENTkpA52YStRW5Dia7";

    registerRequest("""
        {"jsonrpc":"2.0","id":862,"method":"getSignatureStatuses","params":[["%s","%s"],{"searchTransactionHistory":false}]}""".formatted(sig1, sig2), """
        {"jsonrpc":"2.0","result":{"context":{"slot":82},"value":[{"slot":48,"confirmations":null,"err":null,"status":{"Ok":null},"confirmationStatus":"finalized"},{"slot":72,"confirmations":10,"err":null,"status":{"Ok":null},"confirmationStatus":"confirmed"}]},"id":1}"""
    );

    final var statuses = rpcClient.getSigStatusList(List.of(sig1, sig2)).join();
    assertEquals(2, statuses.size());

    var status = statuses.getFirst();
    assertEquals(48L, status.slot());
    assertTrue(status.confirmations().isEmpty());
    assertEquals(Commitment.FINALIZED, status.confirmationStatus());

    status = statuses.getLast();
    assertEquals(72L, status.slot());
    assertEquals(OptionalInt.of(10), status.confirmations());
    assertEquals(Commitment.CONFIRMED, status.confirmationStatus());
  }

  @Test
  void getSlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":871,"method":"getSlot","params":[{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":1234,"id":1}"""
    );
    assertEquals(1234L, rpcClient.getSlot().join());
  }

  @Test
  void getSlotLeader() {
    registerRequest("""
        {"jsonrpc":"2.0","id":872,"method":"getSlotLeader","params":[{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":"ENvAW7JScgYq6o4zKZwewtkzzJgDzuJAFxYasvmEQdpS","id":1}"""
    );
    assertEquals(
        PublicKey.fromBase58Encoded("ENvAW7JScgYq6o4zKZwewtkzzJgDzuJAFxYasvmEQdpS"),
        rpcClient.getSlotLeader().join()
    );
  }

  @Test
  void getSlotLeaders() {
    registerRequest("""
        {"jsonrpc":"2.0","id":873,"method":"getSlotLeaders","params":[100,10]}""", """
        {"jsonrpc":"2.0","result":["ChorusmmK7i1AxXeiTtQgQZhQNiXYU84ULeaYF1EH15n","ChorusmmK7i1AxXeiTtQgQZhQNiXYU84ULeaYF1EH15n","Awes4Tr6TX8JDzEhCZY2QVNimT6iD1zWHzf1vNyGvpLM"],"id":1}"""
    );

    final var leaders = rpcClient.getSlotLeaders(100, 10).join();
    assertEquals(3, leaders.size());
    assertEquals(PublicKey.fromBase58Encoded("ChorusmmK7i1AxXeiTtQgQZhQNiXYU84ULeaYF1EH15n"), leaders.getFirst());
    assertEquals(PublicKey.fromBase58Encoded("Awes4Tr6TX8JDzEhCZY2QVNimT6iD1zWHzf1vNyGvpLM"), leaders.getLast());

    // The limit is clamped to 5,000 leaders.
    registerRequest("""
        {"jsonrpc":"2.0","id":874,"method":"getSlotLeaders","params":[100,5000]}""", """
        {"jsonrpc":"2.0","result":[],"id":1}"""
    );
    assertTrue(rpcClient.getSlotLeaders(100, 6_000).join().isEmpty());
  }

  @Test
  void getStakeMinimumDelegation() {
    registerRequest("""
        {"jsonrpc":"2.0","id":881,"method":"getStakeMinimumDelegation","params":[{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":501},"value":1000000000},"id":1}"""
    );

    final var minimumDelegation = rpcClient.getStakeMinimumDelegation().join();
    assertEquals(1000000000L, minimumDelegation.lamports());
    assertEquals(501L, minimumDelegation.context().slot());
  }

  @Test
  void getSupplyExcludingNonCirculatingAccountsList() {
    registerRequest("""
        {"jsonrpc":"2.0","id":891,"method":"getSupply","params":[{"commitment":"finalized","excludeNonCirculatingAccountsList":true}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":1114},"value":{"circulating":16000,"nonCirculating":1000000,"nonCirculatingAccounts":[],"total":1016000}},"id":1}"""
    );

    final var supply = rpcClient.getSupply(Commitment.FINALIZED, true).join();
    assertEquals(16000L, supply.circulating());
    assertEquals(1000000L, supply.nonCirculating());
    assertEquals(1016000L, supply.total());
    assertTrue(supply.nonCirculatingAccountKeys().isEmpty());
  }

  @Test
  void getTokenAccountBalance() {
    registerRequest("""
        {"jsonrpc":"2.0","id":901,"method":"getTokenAccountBalance","params":["7fUAJdStEuGbc3sM84cKRL6yYaaSstyLSU4ve5oovLS7",{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":1114},"value":{"amount":"9864","decimals":2,"uiAmount":98.64,"uiAmountString":"98.64"}},"id":1}"""
    );

    final var balance = rpcClient.getTokenAccountBalance(
        PublicKey.fromBase58Encoded("7fUAJdStEuGbc3sM84cKRL6yYaaSstyLSU4ve5oovLS7")
    ).join();
    assertEquals(new BigInteger("9864"), balance.amount());
    assertEquals(2, balance.decimals());
    assertEquals(new BigDecimal("98.64"), balance.toDecimal());
    assertEquals(1114L, balance.context().slot());
  }

  private void validateTokenAccounts(final List<AccountInfo<TokenAccount>> accounts) {
    assertEquals(32, accounts.size());
    final var first = accounts.getFirst();
    assertEquals(PublicKey.fromBase58Encoded("GN7rkD3UU3HfPDGMBizKoyGfc8m2bCiehsA4G35aYta"), first.pubKey());
    final var tokenAccount = first.data();
    assertEquals(first.pubKey(), tokenAccount.address());
    assertEquals(PublicKey.fromBase58Encoded("5q4WfFbcUggHhsvga263fvqwYhsBpAHkkfkdbY82S5J1"), tokenAccount.owner());
  }

  @Test
  void getTokenAccountsForTokenMintByDelegate() {
    registerRequest("""
            {"jsonrpc":"2.0","id":911,"method":"getTokenAccountsByDelegate","params":["4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T",{"mint":"3wyAj7Rt1TWVPZVteFJPLa26JmLvdb1CAKEFZm3NY75E"},{"commitment":"confirmed","encoding":"base64"}]}""",
        readFileString("getTokenAccountsForProgramByOwner.json")
    );

    final var accounts = rpcClient.getTokenAccountsForTokenMintByDelegate(
        PublicKey.fromBase58Encoded("4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T"),
        PublicKey.fromBase58Encoded("3wyAj7Rt1TWVPZVteFJPLa26JmLvdb1CAKEFZm3NY75E")
    ).join();
    validateTokenAccounts(accounts);
  }

  @Test
  void getTokenAccountsForProgramByDelegate() {
    registerRequest("""
            {"jsonrpc":"2.0","id":912,"method":"getTokenAccountsByDelegate","params":["4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T",{"programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},{"commitment":"confirmed","encoding":"base64"}]}""",
        readFileString("getTokenAccountsForProgramByOwner.json")
    );

    final var accounts = rpcClient.getTokenAccountsForProgramByDelegate(
        PublicKey.fromBase58Encoded("4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T"),
        PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    ).join();
    validateTokenAccounts(accounts);
  }

  @Test
  void getTokenAccountsForTokenMintByOwner() {
    registerRequest("""
            {"jsonrpc":"2.0","id":913,"method":"getTokenAccountsByOwner","params":["5q4WfFbcUggHhsvga263fvqwYhsBpAHkkfkdbY82S5J1",{"mint":"2cHr7QS3xfuSV8wdxo3ztuF4xbiarF6Nrgx3qpx3HzXR"},{"commitment":"confirmed","encoding":"base64"}]}""",
        readFileString("getTokenAccountsForProgramByOwner.json")
    );

    final var accounts = rpcClient.getTokenAccountsForTokenMintByOwner(
        PublicKey.fromBase58Encoded("5q4WfFbcUggHhsvga263fvqwYhsBpAHkkfkdbY82S5J1"),
        PublicKey.fromBase58Encoded("2cHr7QS3xfuSV8wdxo3ztuF4xbiarF6Nrgx3qpx3HzXR")
    ).join();
    validateTokenAccounts(accounts);
  }

  @Test
  void getTokenAccountsForProgramByOwner() {
    registerRequest("""
            {"jsonrpc":"2.0","id":914,"method":"getTokenAccountsByOwner","params":["5q4WfFbcUggHhsvga263fvqwYhsBpAHkkfkdbY82S5J1",{"programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},{"commitment":"confirmed","encoding":"base64"}]}""",
        readFileString("getTokenAccountsForProgramByOwner.json")
    );

    final var accounts = rpcClient.getTokenAccountsForProgramByOwner(
        PublicKey.fromBase58Encoded("5q4WfFbcUggHhsvga263fvqwYhsBpAHkkfkdbY82S5J1"),
        PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    ).join();
    validateTokenAccounts(accounts);
  }

  /// Recorded against the public mainnet node on 2026-07-13, which serves the transaction index.
  @Test
  void getTransactionWithTransactionIndex() {
    final var txSignature = "49aroTSh9ehQ4q5WXXwLWorGcJXmpUkf8ty5nWfjXc6HoB1Grq45nRNxi5ngtRhN7zb9fGnVKM8FvKF9gg5hy8ca";

    registerRequest("""
        {"jsonrpc":"2.0","id":911,"method":"getTransaction","params":["%s",{"commitment":"confirmed","maxSupportedTransactionVersion":0,"encoding":"base64"}]}""".formatted(txSignature), """
        {"jsonrpc":"2.0","result":{"blockTime":1783958450,"meta":{"computeUnitsConsumed":2100,"costUnits":3465,"err":null,"fee":5000,"innerInstructions":[],"loadedAddresses":{"readonly":[],"writable":[]},"logMessages":["Program Vote111111111111111111111111111111111111111 invoke [1]","Program Vote111111111111111111111111111111111111111 success"],"postBalances":[35696986359,370164402,1],"postTokenBalances":[],"preBalances":[35696991359,370164402,1],"preTokenBalances":[],"rewards":[],"status":{"Ok":null}},"slot":432674372,"transaction":["AZ10DEU4Cx/7Wz0hfgSBv611o/M0IbBBiHEz1+u8Def5X5olVQBPCJwAU7vAe3cHAWgJCBFZlkT5F3y6lqKfjwsBAAEDG/DgbsI0C9boBnk4XisMmoQA7OtSuLN0M3UeIQzH3GTsrOfsoxxvoBrDjsS+XKKxY6f1+u+wvdkfXobqS0TzIwdhSB01dHS7fE12JOvTvbPYNV5z0RBD/A2jU4AAAAAAuBYPDxl6rC7XQsWlhL08FOLnN+4cFCZIP9ZUVS7jJUwBAgIBAJQBDgAAACQWyhkAAAAAHwEfAR4BHQEcARsBGgEZARgBFwEWARUBFAETARIBEQEQAQ8BDgENAQwBCwEKAQkBCAEHAQYBBQEEAQMBAgEBhDS9Ak3W+EXJYB7nN8pC8/9LHbmVTRk2Zw58n7nYC78BswtVagAAAACE2G8ZeCyZEzWLLSFSoYHv4I8HDiH47L/BH7C9cJJg3g==","base64"],"transactionIndex":1981,"version":"legacy"},"id":1}"""
    );

    final var tx = rpcClient.getTransaction(txSignature).join();

    assertEquals(432_674_372L, tx.slot());
    assertEquals(1_783_958_450L, tx.blockTime().orElseThrow());
    assertEquals(OptionalInt.of(1_981), tx.transactionIndex());
    assertTrue(tx.isLegacy());
    assertEquals(5_000L, tx.meta().fee());
    assertEquals(2_100, tx.meta().computeUnitsConsumed());

    final var skeleton = tx.skeleton();
    assertEquals(txSignature, Objects.requireNonNull(skeleton).id());
    assertEquals(1, skeleton.numSignatures());
  }

  @Test
  void getTransactionCount() {
    registerRequest("""
        {"jsonrpc":"2.0","id":921,"method":"getTransactionCount","params":[{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":268,"id":1}"""
    );
    assertEquals(268L, rpcClient.getTransactionCount().join());
  }

  @Test
  void getVersion() {
    registerRequest("""
        {"jsonrpc":"2.0","id":931,"method":"getVersion"}""", """
        {"jsonrpc":"2.0","result":{"feature-set":2891131721,"solana-core":"1.16.7"},"id":1}"""
    );

    final var version = rpcClient.getVersion().join();
    assertEquals("1.16.7", version.version());
    assertEquals(2891131721L, version.featureSet());
  }

  @Test
  void getLargestAccounts() {
    registerRequest("""
        {"jsonrpc":"2.0","id":941,"method":"getLargestAccounts","params":[{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":54},"value":[{"lamports":999974,"address":"99P8ZgtJYe1buSK8JXkvpLh8xPsCFuLYhz9hQFNw93WJ"},{"lamports":42,"address":"uPwTLbNit5uHbjFdmC6Q2BW5YParQzhx76GRPjiiqHq"}]},"id":1}"""
    );

    final var accounts = rpcClient.getLargestAccounts().join();
    assertEquals(2, accounts.size());
    assertEquals(PublicKey.fromBase58Encoded("99P8ZgtJYe1buSK8JXkvpLh8xPsCFuLYhz9hQFNw93WJ"), accounts.getFirst().addressKey());
    assertEquals(999974L, accounts.getFirst().lamports());
    assertEquals(42L, accounts.getLast().lamports());
  }

  @Test
  void isBlockHashValid() {
    registerRequest("""
        {"jsonrpc":"2.0","id":951,"method":"isBlockhashValid","params":["J7rBdM6AecPDEZp8aPq5iPGNfBdrPzHUSMt4NnZtqTHc",{"commitment":"processed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":2483},"value":false},"id":45}"""
    );

    final var valid = rpcClient.isBlockHashValid(Commitment.PROCESSED, "J7rBdM6AecPDEZp8aPq5iPGNfBdrPzHUSMt4NnZtqTHc").join();
    assertFalse(valid.bool());
    assertEquals(2483L, valid.context().slot());
  }

  @Test
  void minimumLedgerSlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":961,"method":"minimumLedgerSlot"}""", """
        {"jsonrpc":"2.0","result":1234,"id":1}"""
    );
    assertEquals(1234L, rpcClient.minimumLedgerSlot().join());
  }

  @Test
  void requestAirdrop() {
    registerRequest("""
        {"jsonrpc":"2.0","id":971,"method":"requestAirdrop","params":["83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri",1000000000,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":"5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW","id":1}"""
    );

    final var signature = rpcClient.requestAirdrop(
        PublicKey.fromBase58Encoded("83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri"),
        1_000_000_000L
    ).join();
    assertEquals("5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW", signature);
  }

  @Test
  void sendTransaction() {
    final var base64SignedTx = "AVXo5X7UNzpuOmYzkZ+fqHDGiRLTSMlWlUCcZKzEV5CIKlrdvZa3/2GrJJfPrXgZqJbYDaGiOnP99tI/sRJfiwwBAAEDRQ/n5E5CLbMbHanUG3+iVvBAWZu0WFM6NoB5xfybQ7kNwwgfIhv6odn2qTUu/gOisDtaeCW1qlwW/gx3ccr/4wQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQICAAEMAgAAAADKmjsAAAAA";

    registerRequest("""
        {"jsonrpc":"2.0","id":981,"method":"sendTransaction","params":["%s",{"encoding":"base64","preflightCommitment":"confirmed","maxRetries":1}]}""".formatted(base64SignedTx), """
        {"jsonrpc":"2.0","result":"2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAojnVuao8rkxwPYPe8cSwE5GzhEgJA2y8fVjDEo6iR6ykBvDxrTQrtpb","id":1}"""
    );

    final var signature = rpcClient.sendTransaction(base64SignedTx).join();
    assertEquals("2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAojnVuao8rkxwPYPe8cSwE5GzhEgJA2y8fVjDEo6iR6ykBvDxrTQrtpb", signature);

    registerRequest("""
        {"jsonrpc":"2.0","id":982,"method":"sendTransaction","params":["%s",{"encoding":"base64","skipPreflight":true,"preflightCommitment":"processed","maxRetries":0}]}""".formatted(base64SignedTx), """
        {"jsonrpc":"2.0","result":"2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAojnVuao8rkxwPYPe8cSwE5GzhEgJA2y8fVjDEo6iR6ykBvDxrTQrtpb","id":1}"""
    );

    final var skipPreflightSignature = rpcClient.sendTransactionSkipPreflight(base64SignedTx).join();
    assertEquals("2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAojnVuao8rkxwPYPe8cSwE5GzhEgJA2y8fVjDEo6iR6ykBvDxrTQrtpb", skipPreflightSignature);
  }

  @Test
  void simulateTransaction() {
    final var base64EncodedTx = "AdYOLBh+RlElnqIB2Mvrmg67nQczOFRSw7lyLTOTHpTdLD/MEWOGE5tCBSjBg47qs+cVCv7RTBZEXckWDVMzU8sBAAEDgU5+hbBLvnDvXKYX5R8YS764AMBmAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAgIAAQwCAAAAAOH1BQAAAAA=";

    registerRequest("""
        {"jsonrpc":"2.0","id":991,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":true,"innerInstructions":false,"commitment":"confirmed"}]}""".formatted(base64EncodedTx), """
        {"jsonrpc":"2.0","result":{"context":{"slot":218},"value":{"err":null,"accounts":null,"logs":["Program 83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri invoke [1]","Program 83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri consumed 2366 of 1400000 compute units","Program return: 83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri KgAAAAAAAAA=","Program 83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri success"],"returnData":{"data":["Kg==","base64"],"programId":"83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri"},"unitsConsumed":2366}},"id":1}"""
    );

    final var simulation = rpcClient.simulateTransaction(base64EncodedTx).join();
    assertNull(simulation.error());
    assertEquals(218L, simulation.context().slot());
    assertEquals(4, simulation.logs().size());
    assertEquals("Program 83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri invoke [1]", simulation.logs().getFirst());
    assertEquals(OptionalInt.of(2366), simulation.unitsConsumed());
    assertEquals(PublicKey.fromBase58Encoded("83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri"), simulation.programId());
    assertArrayEquals(new byte[]{42}, simulation.data());
    assertTrue(simulation.accounts().isEmpty());
    assertTrue(simulation.fee().isEmpty());
  }

  @Test
  void simulateTransactionWithInnerInstructions() {
    final var base64EncodedTx = "AdYOLBh+RlElnqIB2Mvrmg67nQczOFRSw7lyLTOTHpTdLD/MEWOGE5tCBSjBg47qs+cVCv7RTBZEXckWDVMzU8sBAAEDgU5+hbBLvnDvXKYX5R8YS764AMBmAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAgIAAQwCAAAAAOH1BQAAAAA=";

    registerRequest("""
        {"jsonrpc":"2.0","id":992,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":true,"innerInstructions":true,"commitment":"confirmed"}]}""".formatted(base64EncodedTx), """
        {"jsonrpc":"2.0","result":{"context":{"slot":218},"value":{"err":null,"accounts":null,"logs":[],"innerInstructions":[],"loadedAddresses":{"readonly":["So11111111111111111111111111111111111111112"],"writable":["bHHnvxhkzBebvqxpnzVaXSdQ1GdFeZFg8yi9YKgL7zE"]},"replacementBlockhash":{"blockhash":"GH7ome3EiwEr7tu9JuTh2dpYWBJK3z69Xm1ZE3MEE6JC","lastValidBlockHeight":3090},"unitsConsumed":150,"fee":5000}},"id":1}"""
    );

    // The response's loadedAddresses is intentionally not modeled; v1 transactions do not support lookup tables.
    final var simulation = rpcClient.simulateTransactionWithInnerInstructions(base64EncodedTx).join();
    assertNull(simulation.error());
    assertTrue(simulation.innerInstructions().isEmpty());
    assertEquals(OptionalInt.of(150), simulation.unitsConsumed());
    assertEquals(OptionalLong.of(5000), simulation.fee());
    final var replacementBlockHash = simulation.replacementBlockHash();
    assertEquals("GH7ome3EiwEr7tu9JuTh2dpYWBJK3z69Xm1ZE3MEE6JC", replacementBlockHash.blockhash());
    assertEquals(3090L, replacementBlockHash.lastValidBlockHeight());
  }

  @Test
  void simulateTransactionWithAccounts() {
    final var base64EncodedTx = "AdYOLBh+RlElnqIB2Mvrmg67nQczOFRSw7lyLTOTHpTdLD/MEWOGE5tCBSjBg47qs+cVCv7RTBZEXckWDVMzU8sBAAEDgU5+hbBLvnDvXKYX5R8YS764AMBmAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAgIAAQwCAAAAAOH1BQAAAAA=";
    final var account = PublicKey.fromBase58Encoded("83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri");

    registerRequest("""
        {"jsonrpc":"2.0","id":993,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":false,"innerInstructions":true,"commitment":"confirmed","accounts":{"addresses":["83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri"],"encoding":"base64"}}]}""".formatted(base64EncodedTx), """
        {"jsonrpc":"2.0","result":{"context":{"slot":218},"value":{"err":null,"accounts":[{"lamports":88849814690250,"data":["","base64"],"owner":"11111111111111111111111111111111","executable":false,"rentEpoch":18446744073709551615,"space":0}],"logs":[],"unitsConsumed":150}},"id":1}"""
    );

    final var simulation = rpcClient.simulateTransaction(
        Commitment.CONFIRMED,
        base64EncodedTx,
        false,
        true,
        List.of(account)
    ).join();
    assertNull(simulation.error());

    final var accounts = simulation.accounts();
    assertEquals(1, accounts.size());
    final var accountInfo = accounts.getFirst();
    assertEquals(account, accountInfo.pubKey());
    assertEquals(88849814690250L, accountInfo.lamports());
    assertEquals(PublicKey.fromBase58Encoded("11111111111111111111111111111111"), accountInfo.owner());
    assertEquals(0, accountInfo.data().length);
  }

  /// Recorded against mainnet on 2026-07-13 for a transfer from an account which does not exist,
  /// so that the simulation fails after the fee has been calculated.
  @Test
  void simulateTransactionFee() {
    final var base64EncodedTx = "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQABAmoQJvXCV0OtQtVcSFYzSXjxKrsq5sRZWAyjJvJLYbCUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHRRSqRZDlaKAY9RCS5PmuqXWTvzSw+3fRICsCPPPnjkBAQIBAAwCAAAAAAAAAAAAAAA=";

    registerRequest("""
        {"jsonrpc":"2.0","id":994,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":true,"innerInstructions":false,"commitment":"confirmed"}]}""".formatted(base64EncodedTx), """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.1.0","slot":432669465},"value":{"accounts":null,"err":{"InstructionError":[0,"MissingAccount"]},"fee":5000,"innerInstructions":null,"loadedAccountsDataSize":149,"loadedAddresses":{"readonly":[],"writable":[]},"logs":["Program 11111111111111111111111111111111 invoke [1]","Program 11111111111111111111111111111111 failed: An account required by the instruction is missing"],"postBalances":[670405298219,1],"postTokenBalances":[],"preBalances":[670405303219,1],"preTokenBalances":[],"replacementBlockhash":{"blockhash":"37QF9uWpdH6H7PnqC5UxELtbHov7TNyaywgWjni7iKqH","lastValidBlockHeight":410737748},"returnData":null,"unitsConsumed":150}},"id":1}"""
    );

    final var simulation = rpcClient.simulateTransaction(Commitment.CONFIRMED, base64EncodedTx, true, false).join();

    // The fee is calculated even though the simulation failed.
    assertEquals(OptionalLong.of(5_000), simulation.fee());
    assertEquals(OptionalInt.of(150), simulation.unitsConsumed());
    assertEquals(149, simulation.loadedAccountsDataSize());
    assertEquals(432_669_465L, simulation.context().slot());
    assertEquals("4.1.0", simulation.context().apiVersion());

    final var instructionError = assertInstanceOf(TransactionError.InstructionError.class, simulation.error());
    assertEquals(0, instructionError.ixIndex());
    assertInstanceOf(IxError.MissingAccount.class, instructionError.ixError());

    assertEquals(List.of(670_405_303_219L, 1L), simulation.preBalances());
    assertEquals(List.of(670_405_298_219L, 1L), simulation.postBalances());
    assertTrue(simulation.preTokenBalances().isEmpty());
    assertTrue(simulation.postTokenBalances().isEmpty());
    assertEquals(2, simulation.logs().size());

    final var replacementBlockHash = simulation.replacementBlockHash();
    assertEquals("37QF9uWpdH6H7PnqC5UxELtbHov7TNyaywgWjni7iKqH", replacementBlockHash.blockhash());
    assertEquals(410_737_748L, replacementBlockHash.lastValidBlockHeight());
  }

  @Test
  void getProgramAccounts() {
    final var programId = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
    final var filters = List.of(
        Filter.createMemCompFilter(8, PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112")),
        Filter.createDataSizeFilter(8200)
    );

    registerRequest("""
        {"jsonrpc":"2.0","id":1001,"method":"getProgramAccounts","params":["GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc",{"withContext":true,"encoding":"base64","commitment":"finalized","minContextSlot":1000,"dataSlice":{"length":32,"offset":88},"filters":[{"memcmp":{"offset":8,"bytes":"So11111111111111111111111111111111111111112"}},{"dataSize":8200}]}]}""", """
        {"jsonrpc":"2.0","id":1755900035918,"result":{"context":{"slot":361843021,"apiVersion":"2.2.7"},"value":[{"pubkey":"5EhqyiKivRyyhK4wHALghpXUEPSAbV6DRFuNzfpTHbv","account":{"lamports":57962880,"data":["CR5z0XpVJtRI5Ymupa/nwizWHFtmqGpCerJiMJUU5Vw=","base64"],"owner":"GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc","executable":false,"rentEpoch":18446744073709551615,"space":8200}}]}}"""
    );

    var accounts = rpcClient.getProgramAccounts(
        programId,
        Commitment.FINALIZED,
        1000,
        filters,
        32, 88
    ).join();

    assertEquals(1, accounts.size());
    var account = accounts.getFirst();
    assertEquals(new Context(361843021L, "2.2.7"), account.context());
    assertEquals("5EhqyiKivRyyhK4wHALghpXUEPSAbV6DRFuNzfpTHbv", account.pubKey().toBase58());
    assertEquals(programId, account.owner());
    final byte[] data = account.data();
    assertEquals(32, data.length);
    assertEquals("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", PublicKey.readPubKey(data).toBase58());

    registerRequest("""
            {"jsonrpc":"2.0","id":1002,"method":"getProgramAccounts","params":["GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc",{"withContext":true,"encoding":"base64","commitment":"confirmed","filters":[{"memcmp":{"offset":8,"bytes":"So11111111111111111111111111111111111111112"}},{"dataSize":8200}]}]}""",
        readFileString("getProgramAccounts.json")
    );

    accounts = rpcClient.getProgramAccounts(programId, filters).join();

    assertEquals(35, accounts.size());
    account = accounts.getFirst();
    assertEquals("5EhqyiKivRyyhK4wHALghpXUEPSAbV6DRFuNzfpTHbv", account.pubKey().toBase58());
    assertEquals(8200, account.space());
    assertEquals(account.space(), account.data().length);
    assertEquals("GpTDuQvx5XjEELJvJi3sdgz9XEQoWpF3tQpwYBGjmUDx", accounts.getLast().pubKey().toBase58());

    // No filters, no minContextSlot and no data slice.
    registerRequest("""
        {"jsonrpc":"2.0","id":1003,"method":"getProgramAccounts","params":["GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc",{"withContext":true,"encoding":"base64","commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","id":1755900035919,"result":{"context":{"slot":361843021,"apiVersion":"2.2.7"},"value":[]}}"""
    );

    accounts = rpcClient.getProgramAccounts(programId).join();
    assertTrue(accounts.isEmpty());
  }
}
