package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.Context;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

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
        {"jsonrpc":"2.0","result":[{"amount":1854511658,"commission":5,"effectiveSlot":338256000,"epoch":782,"postBalance":2178854057}],"id":1746563243745}"""
    );

    var inflationRewards = rpcClient.getInflationReward(List.of(
            PublicKey.fromBase58Encoded("BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g")
        )
    ).join();
    assertEquals(1, inflationRewards.size());
    var inflationReward = inflationRewards.getFirst();
    assertEquals(1854511658, inflationReward.amount());
    assertEquals(5, inflationReward.commission());
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
    assertEquals(337824000, inflationReward.effectiveSlot());
    assertEquals(781, inflationReward.epoch());
    assertEquals(1967836329L, inflationReward.postBalance());
  }
}
