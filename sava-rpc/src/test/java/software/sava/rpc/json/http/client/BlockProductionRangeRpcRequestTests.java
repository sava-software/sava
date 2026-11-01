package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.ValidatorLeaderInfo;

import static org.junit.jupiter.api.Assertions.*;

/// `getBlockProduction` over a range bounded on both ends. The three exchanges of
/// [#aTwoSidedRangeCountsEveryLeader()] and [#aTwoSidedRangeForOneIdentity()] are verbatim captures
/// from `https://api.devnet.solana.com` (Agave 4.4.0-beta.1) and `https://api.mainnet-beta.solana.com`
/// (Agave 4.3.2) on 2026-11-01; every other registration here is synthetic, including the released
/// one-sided bodies, pinned so the new range member cannot leak into them.
final class BlockProductionRangeRpcRequestTests extends RpcRequestTests {

  private static final PublicKey DEVNET_IDENTITY = PublicKey.fromBase58Encoded("BALUa17Zy2vegZ7xaTSTVciW11oLYpXzyT6FYwCVSnsK");
  private static final PublicKey MAINNET_IDENTITY = PublicKey.fromBase58Encoded("22rU5yUmdVThrkoPieVNphqEyAtMQKmZxjwcD8v4bJDU");

  @Test
  void aTwoSidedRangeCountsEveryLeader() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"confirmed","range":{"firstSlot":517570344,"lastSlot":517570444}}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517570637},"value":{"byIdentity":{"BALUa17Zy2vegZ7xaTSTVciW11oLYpXzyT6FYwCVSnsK":[4,4],"dv1ZAGvdsz5hHLwWXsVnM94hWf1pjbKVau1QVkaMJ92":[29,29],"dv2eQHeP4RFrJZ6UeiZWoc3XTtmtZCUKxxCApCDcRNV":[36,36],"dv3qDFk1DTF36Z62bNvrCXe9sKATA6xvVy6A798xxAS":[12,12],"dv4ACNkpYPcE3aKmYDqZm9G5EB3J4MRoeE7WNDRBVJB":[20,16]},"range":{"firstSlot":517570344,"lastSlot":517570444}}},"id":1}""");
    final var production = rpcClient.getBlockProduction(517_570_344L, 517_570_444L).join();
    assertEquals(517_570_344L, production.firstSlot());
    assertEquals(517_570_444L, production.lastSlot());
    assertEquals(517_570_637L, production.context().slot());
    final var leaders = production.leaderInfoMap();
    assertEquals(5, leaders.size());
    assertEquals(new ValidatorLeaderInfo(4, 4), leaders.get(DEVNET_IDENTITY));
    assertEquals(new ValidatorLeaderInfo(36, 36), leaders.get(PublicKey.fromBase58Encoded("dv2eQHeP4RFrJZ6UeiZWoc3XTtmtZCUKxxCApCDcRNV")));
    // four of twenty leader slots went unproduced
    assertEquals(new ValidatorLeaderInfo(20, 16), leaders.get(PublicKey.fromBase58Encoded("dv4ACNkpYPcE3aKmYDqZm9G5EB3J4MRoeE7WNDRBVJB")));
  }

  @Test
  void aTwoSidedRangeForOneIdentity() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"confirmed","identity":"BALUa17Zy2vegZ7xaTSTVciW11oLYpXzyT6FYwCVSnsK","range":{"firstSlot":517570344,"lastSlot":517570444}}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517570645},"value":{"byIdentity":{"BALUa17Zy2vegZ7xaTSTVciW11oLYpXzyT6FYwCVSnsK":[4,4]},"range":{"firstSlot":517570344,"lastSlot":517570444}}},"id":1}""");
    var production = rpcClient.getBlockProduction(Commitment.CONFIRMED, DEVNET_IDENTITY, 517_570_344L, 517_570_444L).join();
    assertEquals(1, production.leaderInfoMap().size());
    assertEquals(new ValidatorLeaderInfo(4, 4), production.leaderInfoMap().get(DEVNET_IDENTITY));
    assertEquals(517_570_645L, production.context().slot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"confirmed","identity":"22rU5yUmdVThrkoPieVNphqEyAtMQKmZxjwcD8v4bJDU","range":{"firstSlot":464177277,"lastSlot":464177377}}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.3.2","slot":464177555},"value":{"byIdentity":{"22rU5yUmdVThrkoPieVNphqEyAtMQKmZxjwcD8v4bJDU":[4,4]},"range":{"firstSlot":464177277,"lastSlot":464177377}}},"id":1}""");
    production = rpcClient.getBlockProduction(MAINNET_IDENTITY, 464_177_277L, 464_177_377L).join();
    assertEquals(464_177_277L, production.firstSlot());
    assertEquals(464_177_377L, production.lastSlot());
    assertEquals("4.3.2", production.context().apiVersion());
    assertEquals(new ValidatorLeaderInfo(4, 4), production.leaderInfoMap().get(MAINNET_IDENTITY));
  }

  @Test
  void anExplicitCommitmentWithoutAnIdentitySendsNoIdentity() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"finalized","range":{"firstSlot":10,"lastSlot":12}}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":15},"value":{"byIdentity":{"BALUa17Zy2vegZ7xaTSTVciW11oLYpXzyT6FYwCVSnsK":[3,2]},"range":{"firstSlot":10,"lastSlot":12}}},"id":1}""");
    final var production = rpcClient.getBlockProduction(Commitment.FINALIZED, 10, 12).join();
    assertEquals(new ValidatorLeaderInfo(3, 2), production.leaderInfoMap().get(DEVNET_IDENTITY));
    assertEquals(12L, production.lastSlot());

    // slots are unsigned: a negative long is the upper half of the range, rendered as agave's u64
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"processed","identity":"22rU5yUmdVThrkoPieVNphqEyAtMQKmZxjwcD8v4bJDU","range":{"firstSlot":9223372036854775808,"lastSlot":18446744073709551615}}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":16},"value":{"byIdentity":{},"range":{"firstSlot":0,"lastSlot":16}}},"id":1}""");
    assertTrue(rpcClient.getBlockProduction(Commitment.PROCESSED, MAINNET_IDENTITY, Long.MIN_VALUE, -1).join().leaderInfoMap().isEmpty());
  }

  @Test
  void theOneSidedBodiesAreUnchanged() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"confirmed","range":{"firstSlot":517570344}}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":17},"value":{"byIdentity":{},"range":{"firstSlot":517570344,"lastSlot":17}}},"id":1}""");
    assertEquals(17L, rpcClient.getBlockProduction(517_570_344L).join().lastSlot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"finalized","identity":"BALUa17Zy2vegZ7xaTSTVciW11oLYpXzyT6FYwCVSnsK","range":{"firstSlot":5}}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":18},"value":{"byIdentity":{},"range":{"firstSlot":5,"lastSlot":18}}},"id":1}""");
    assertEquals(5L, rpcClient.getBlockProduction(Commitment.FINALIZED, DEVNET_IDENTITY, 5).join().firstSlot());
  }
}
