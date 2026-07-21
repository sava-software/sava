package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.request.Commitment;

import static org.junit.jupiter.api.Assertions.*;

/// Round trips for the cluster and epoch information methods. Each asserts the
/// outgoing request body as well as the parse, because a wrong method name or a
/// missing commitment is invisible until it reaches a node.
final class ClusterInfoRpcRequestTests extends RpcRequestTests {

  @Test
  void getClusterNodes() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getClusterNodes"}""", """
        {"jsonrpc":"2.0","result":[{"featureSet":3271415109,"gossip":"1.2.3.4:8001","pubkey":\
        "9QU2QSxhb24FUX3Tu2FpczXjpK3VYrvRudywSZaM29mF","rpc":"1.2.3.4:8899","shredVersion":50093,\
        "tpu":"1.2.3.4:8003","version":"2.1.9"}],"id":1}""");

    final var nodes = rpcClient.getClusterNodes().join();
    assertEquals(1, nodes.size());
    final var node = nodes.getFirst();
    assertEquals("9QU2QSxhb24FUX3Tu2FpczXjpK3VYrvRudywSZaM29mF", node.publicKey().toBase58());
    assertEquals("2.1.9", node.version());
  }

  @Test
  void getEpochSchedule() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getEpochSchedule"}""", """
        {"jsonrpc":"2.0","result":{"firstNormalEpoch":8,"firstNormalSlot":8160,\
        "leaderScheduleSlotOffset":8192,"slotsPerEpoch":8192,"warmup":true},"id":1}""");

    final var schedule = rpcClient.getEpochSchedule().join();
    assertEquals(8, schedule.firstNormalEpoch());
    assertEquals(8160, schedule.firstNormalSlot());
    assertEquals(8192, schedule.slotsPerEpoch());
    assertTrue(schedule.warmup());
  }

  @Test
  void getIdentity() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getIdentity"}""", """
        {"jsonrpc":"2.0","result":{"identity":"2r1F4iWqVcb8M1DbAjQuFpebkQHY9hcVU4WuW2DJBppN"},"id":1}""");

    assertEquals("2r1F4iWqVcb8M1DbAjQuFpebkQHY9hcVU4WuW2DJBppN",
        rpcClient.getIdentity().join().identityKey().toBase58());
  }

  @Test
  void getInflationRate() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getInflationRate"}""", """
        {"jsonrpc":"2.0","result":{"epoch":100,"foundation":0.001,"total":0.149,\
        "validator":0.148},"id":1}""");

    final var rate = rpcClient.getInflationRate().join();
    assertEquals(100, rate.epoch());
    assertEquals(0.149, rate.total());
    assertEquals(0.148, rate.validator());
  }

  @Test
  void getHighestSnapshotSlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getHighestSnapshotSlot"}""", """
        {"jsonrpc":"2.0","result":{"full":100,"incremental":110},"id":1}""");

    final var snapshot = rpcClient.getHighestSnapshotSlot().join();
    assertEquals(100, snapshot.full());
    assertEquals(110, snapshot.incremental());
  }

  /// The commitment-carrying methods have a no-arg overload that fills in the
  /// client default, so both are driven.
  @Test
  void getInflationGovernorUsesTheDefaultCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getInflationGovernor","params":[{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"foundation":0.05,"foundationTerm":7.0,"initial":0.15,\
        "taper":0.15,"terminal":0.015},"id":1}""");

    final var governor = rpcClient.getInflationGovernor().join();
    assertEquals(0.15, governor.initial());
    assertEquals(0.015, governor.terminal());
  }

  @Test
  void getInflationGovernorHonoursAnExplicitCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getInflationGovernor","params":[{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":{"foundation":0.05,"foundationTerm":7.0,"initial":0.15,\
        "taper":0.15,"terminal":0.015},"id":1}""");

    assertNotNull(rpcClient.getInflationGovernor(Commitment.FINALIZED).join());
  }

  @Test
  void getEpochInfoUsesTheDefaultCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getEpochInfo","params":[{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"absoluteSlot":166598,"blockHeight":166500,"epoch":27,\
        "slotIndex":2790,"slotsInEpoch":8192,"transactionCount":22661093},"id":1}""");

    final var epochInfo = rpcClient.getEpochInfo().join();
    assertEquals(27, epochInfo.epoch());
    assertEquals(166598, epochInfo.absoluteSlot());
    assertEquals(8192, epochInfo.slotsInEpoch());
  }

  @Test
  void getEpochInfoHonoursAnExplicitCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getEpochInfo","params":[{"commitment":"processed"}]}""", """
        {"jsonrpc":"2.0","result":{"absoluteSlot":1,"blockHeight":1,"epoch":0,"slotIndex":1,\
        "slotsInEpoch":8192,"transactionCount":1},"id":1}""");

    assertEquals(0, rpcClient.getEpochInfo(Commitment.PROCESSED).join().epoch());
  }

  @Test
  void getLatestBlockHashUsesTheDefaultCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":2792},"value":{\
        "blockhash":"EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N","lastValidBlockHeight":3090}},"id":1}""");

    final var blockHash = rpcClient.getLatestBlockHash().join();
    assertEquals("EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N", blockHash.blockHash());
    assertEquals(3090, blockHash.lastValidBlockHeight());
  }

  @Test
  void getLatestBlockHashHonoursAnExplicitCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":1},"value":{\
        "blockhash":"EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N","lastValidBlockHeight":1}},"id":1}""");

    assertNotNull(rpcClient.getLatestBlockHash(Commitment.FINALIZED).join());
  }

  @Test
  void getBlockCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockCommitment","params":[5]}""", """
        {"jsonrpc":"2.0","result":{"commitment":[0,0,0,0,0,0,0,0,10,32],"totalStake":42},"id":1}""");

    final var commitment = rpcClient.getBlockCommitment(5).join();
    assertEquals(42, commitment.totalStake());
    assertEquals(10, commitment.commitment()[8]);
  }

  @Test
  void isBlockHashValid() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"isBlockhashValid","params":["J7rBdM6AecPDEZp8aPq5iPSNKVkU5Q76F3oAV4eW5wsW",\
        {"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":2483},"value":false},"id":1}""");

    assertFalse(rpcClient.isBlockHashValid("J7rBdM6AecPDEZp8aPq5iPSNKVkU5Q76F3oAV4eW5wsW").join().bool());
  }
}
