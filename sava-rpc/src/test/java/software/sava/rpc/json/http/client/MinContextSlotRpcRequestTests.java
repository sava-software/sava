package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.response.RpcCustomError;
import software.sava.rpc.json.http.response.TransactionError;

import java.math.BigInteger;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/// The minimum context slot on every method agave reads through `RpcContextConfig` and on the
/// transaction submission paths. In every test but [#simulationOfATransactionObject()], whose
/// transaction and answers are synthetic, the registrations with a recent minimum, a literal zero
/// and the unsigned maximum replay verbatim exchanges with `https://api.devnet.solana.com` on
/// 2026-11-01 (Agave 4.4.0-beta.1, feature set 3993424257): a satisfied minimum, zero or a slot
/// about a hundred below the node's, returns the ordinary result, and the unsigned maximum returns
/// `-32016` carrying the node's own context slot. The null-minimum registrations pin the released
/// bodies with synthetic answers, as do the skip-preflight submissions, which were never sent
/// live. Mainnet (4.3.2) and testnet (4.4.0) answered the read methods and
/// `simulateTransaction` the same way; `sendTransaction` was sent to devnet and testnet only; see
/// AGAVE_SYNC.md. The harness ignores the request id.
///
/// The sent transaction carries an all-zero signature for a payer that does not exist, so the node
/// rejects it in preflight and never forwards it; the recorded `-32002` is that rejection.
final class MinContextSlotRpcRequestTests extends RpcRequestTests {

  private static final BigInteger U64_MAX = new BigInteger("18446744073709551615");
  /// The first capture's current slot less one hundred.
  private static final BigInteger RECENT = BigInteger.valueOf(517_556_081L);
  /// The later capture's current slot less one hundred.
  private static final BigInteger LATER = BigInteger.valueOf(517_570_444L);
  private static final PublicKey TOKEN_PROGRAM =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey PAYER =
      PublicKey.fromBase58Encoded("2zprvnmJJ4szdmTHGqEpbqn29TRXezEtuCMZ9anGZd5y");
  private static final String BLOCKHASH = "2M3LYsyXnBEpj1zsCGPfnAUoe4qCUQHbLY7fEm9uvAMe";
  private static final String FEE_MESSAGE =
      "AQABAh2r6NEzcpGTccojCKCGt52dGHD/TQNaguHAWIRSNdaAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABuWu+TiNiW2t/WNQYThoJFQcxTQDH4YoYP0Yce5HhK+gEBAQAMAgAAAAAAAAAAAAAA";
  private static final String TX =
      "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAECHavo0TNykZNxyiMIoIa3nZ0YcP9NA1qC4cBYhFI11oAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAG5a75OI2Jba39Y1BhOGgkVBzFNAMfhihg/Rhx7keEr6AQEBAAwCAAAAAAAAAAAAAAA=";

  private static String notReached(final long contextSlot) {
    return """
        {"jsonrpc":"2.0","error":{"code":-32016,"message":"Minimum context slot has not been reached","data":{"contextSlot":%d}},"id":1}"""
        .formatted(contextSlot);
  }

  private static JsonRpcException joinExpectingRpcException(final Runnable join) {
    final var exception = assertThrows(CompletionException.class, join::run);
    return assertInstanceOf(JsonRpcException.class, exception.getCause());
  }

  private static void assertNotReached(final JsonRpcException exception, final long contextSlot) {
    assertEquals(-32016, exception.code());
    final var error = assertInstanceOf(RpcCustomError.MinContextSlotNotReached.class, exception.customError());
    assertEquals(contextSlot, error.contextSlot());
  }

  @Test
  void balance() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBalance","params":["TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517556265},"value":20423277856},"id":1}""");
    var balance = rpcClient.getBalance(Commitment.CONFIRMED, TOKEN_PROGRAM, RECENT).join();
    assertEquals(20_423_277_856L, balance.lamports());
    assertEquals(517_556_265L, balance.context().slot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBalance","params":["TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",{"commitment":"confirmed","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517556199},"value":20423277856},"id":1}""");
    balance = rpcClient.getBalance(TOKEN_PROGRAM, BigInteger.ZERO).join();
    assertEquals(517_556_199L, balance.context().slot());
    assertEquals("4.4.0-beta.1", balance.context().apiVersion());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBalance","params":["TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_329L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getBalance(TOKEN_PROGRAM, U64_MAX).join()), 517_556_329L);

    // null omits the option and leaves the released body as it was
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBalance","params":["TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":31},"value":7},"id":1}""");
    balance = rpcClient.getBalance(Commitment.FINALIZED, TOKEN_PROGRAM, null).join();
    assertEquals(7L, balance.lamports());
    assertEquals(31L, balance.context().slot());
  }

  @Test
  void blockHeight() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockHeight","params":[{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":504726079,"id":1}""");
    assertEquals(504_726_079L, rpcClient.getBlockHeight(Commitment.CONFIRMED, RECENT).join().height());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockHeight","params":[{"commitment":"confirmed","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":504726011,"id":1}""");
    assertEquals(504_726_011L, rpcClient.getBlockHeight(BigInteger.ZERO).join().height());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockHeight","params":[{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_332L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getBlockHeight(U64_MAX).join()), 517_556_332L);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockHeight","params":[{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":77,"id":1}""");
    assertEquals(77L, rpcClient.getBlockHeight(Commitment.FINALIZED, null).join().height());
  }

  @Test
  void epochInfo() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getEpochInfo","params":[{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":{"absoluteSlot":517556273,"blockHeight":504726083,"epoch":1198,"slotIndex":20273,"slotsInEpoch":432000,"transactionCount":19799683868},"id":1}""");
    var epochInfo = rpcClient.getEpochInfo(Commitment.CONFIRMED, RECENT).join();
    assertEquals(517_556_273L, epochInfo.absoluteSlot());
    assertEquals(504_726_083L, epochInfo.blockHeight());
    assertEquals(1198L, epochInfo.epoch());
    assertEquals(20_273, epochInfo.slotIndex());
    assertEquals(432_000, epochInfo.slotsInEpoch());
    assertEquals(19_799_683_868L, epochInfo.transactionCount());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getEpochInfo","params":[{"commitment":"confirmed","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":{"absoluteSlot":517556205,"blockHeight":504726015,"epoch":1198,"slotIndex":20205,"slotsInEpoch":432000,"transactionCount":19799682778},"id":1}""");
    epochInfo = rpcClient.getEpochInfo(BigInteger.ZERO).join();
    assertEquals(517_556_205L, epochInfo.absoluteSlot());
    assertEquals(19_799_682_778L, epochInfo.transactionCount());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getEpochInfo","params":[{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_336L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getEpochInfo(U64_MAX).join()), 517_556_336L);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getEpochInfo","params":[{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":{"absoluteSlot":9,"blockHeight":8,"epoch":2,"slotIndex":1,"slotsInEpoch":4,"transactionCount":3},"id":1}""");
    assertEquals(2L, rpcClient.getEpochInfo(Commitment.FINALIZED, null).join().epoch());
  }

  @Test
  void slot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSlot","params":[{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":517556277,"id":1}""");
    assertEquals(517_556_277L, rpcClient.getSlot(Commitment.CONFIRMED, RECENT).join());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSlot","params":[{"commitment":"confirmed","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":517556209,"id":1}""");
    assertEquals(517_556_209L, rpcClient.getSlot(BigInteger.ZERO).join());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSlot","params":[{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_339L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getSlot(U64_MAX).join()), 517_556_339L);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSlot","params":[{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":41,"id":1}""");
    assertEquals(41L, rpcClient.getSlot(Commitment.FINALIZED, null).join());
  }

  @Test
  void slotLeader() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSlotLeader","params":[{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":"dv2eQHeP4RFrJZ6UeiZWoc3XTtmtZCUKxxCApCDcRNV","id":1}""");
    assertEquals(
        PublicKey.fromBase58Encoded("dv2eQHeP4RFrJZ6UeiZWoc3XTtmtZCUKxxCApCDcRNV"),
        rpcClient.getSlotLeader(Commitment.CONFIRMED, RECENT).join()
    );

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSlotLeader","params":[{"commitment":"confirmed","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":"dv4ACNkpYPcE3aKmYDqZm9G5EB3J4MRoeE7WNDRBVJB","id":1}""");
    assertEquals(
        PublicKey.fromBase58Encoded("dv4ACNkpYPcE3aKmYDqZm9G5EB3J4MRoeE7WNDRBVJB"),
        rpcClient.getSlotLeader(BigInteger.ZERO).join()
    );

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSlotLeader","params":[{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_342L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getSlotLeader(U64_MAX).join()), 517_556_342L);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSlotLeader","params":[{"commitment":"processed"}]}""", """
        {"jsonrpc":"2.0","result":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","id":1}""");
    assertEquals(TOKEN_PROGRAM, rpcClient.getSlotLeader(Commitment.PROCESSED, null).join());
  }

  @Test
  void transactionCount() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTransactionCount","params":[{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":19799684002,"id":1}""");
    assertEquals(19_799_684_002L, rpcClient.getTransactionCount(Commitment.CONFIRMED, RECENT).join());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTransactionCount","params":[{"commitment":"confirmed","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":19799682987,"id":1}""");
    assertEquals(19_799_682_987L, rpcClient.getTransactionCount(BigInteger.ZERO).join());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTransactionCount","params":[{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_347L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getTransactionCount(U64_MAX).join()), 517_556_347L);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTransactionCount","params":[{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":268,"id":1}""");
    assertEquals(268L, rpcClient.getTransactionCount(Commitment.FINALIZED, null).join());
  }

  @Test
  void stakeMinimumDelegation() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getStakeMinimumDelegation","params":[{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517556287},"value":1000000000},"id":1}""");
    var minimum = rpcClient.getStakeMinimumDelegation(Commitment.CONFIRMED, RECENT).join();
    assertEquals(1_000_000_000L, minimum.lamports());
    assertEquals(517_556_287L, minimum.context().slot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getStakeMinimumDelegation","params":[{"commitment":"confirmed","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517556219},"value":1000000000},"id":1}""");
    minimum = rpcClient.getStakeMinimumDelegation(BigInteger.ZERO).join();
    assertEquals(517_556_219L, minimum.context().slot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getStakeMinimumDelegation","params":[{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_351L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getStakeMinimumDelegation(U64_MAX).join()), 517_556_351L);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getStakeMinimumDelegation","params":[{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":501},"value":3},"id":1}""");
    assertEquals(3L, rpcClient.getStakeMinimumDelegation(Commitment.FINALIZED, null).join().lamports());
  }

  @Test
  void latestBlockHash() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517556291},"value":{"blockhash":"D7goeZhMbzKWLffjbBLTSEy5WKE6nd8scitYNWALcQA5","lastValidBlockHeight":504726251}},"id":1}""");
    var latest = rpcClient.getLatestBlockHash(Commitment.CONFIRMED, RECENT).join();
    assertEquals("D7goeZhMbzKWLffjbBLTSEy5WKE6nd8scitYNWALcQA5", latest.blockHash());
    assertEquals(504_726_251L, latest.lastValidBlockHeight());
    assertEquals(517_556_291L, latest.context().slot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"confirmed","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517556223},"value":{"blockhash":"CqhmytQtHHHoyJhYgYaHcJkeFwSTMuf25vEwrgWcjdMq","lastValidBlockHeight":504726183}},"id":1}""");
    latest = rpcClient.getLatestBlockHash(BigInteger.ZERO).join();
    assertEquals("CqhmytQtHHHoyJhYgYaHcJkeFwSTMuf25vEwrgWcjdMq", latest.blockHash());
    assertEquals(504_726_183L, latest.lastValidBlockHeight());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_353L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getLatestBlockHash(U64_MAX).join()), 517_556_353L);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":5},"value":{"blockhash":"2M3LYsyXnBEpj1zsCGPfnAUoe4qCUQHbLY7fEm9uvAMe","lastValidBlockHeight":9}},"id":1}""");
    assertEquals(9L, rpcClient.getLatestBlockHash(Commitment.FINALIZED, null).join().lastValidBlockHeight());
  }

  @Test
  void blockHashValidity() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"isBlockhashValid","params":["2M3LYsyXnBEpj1zsCGPfnAUoe4qCUQHbLY7fEm9uvAMe",{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517556293},"value":true},"id":1}""");
    var valid = rpcClient.isBlockHashValid(Commitment.CONFIRMED, BLOCKHASH, RECENT).join();
    assertTrue(valid.bool());
    assertEquals(517_556_293L, valid.context().slot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"isBlockhashValid","params":["2M3LYsyXnBEpj1zsCGPfnAUoe4qCUQHbLY7fEm9uvAMe",{"commitment":"confirmed","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517556227},"value":true},"id":1}""");
    valid = rpcClient.isBlockHashValid(BLOCKHASH, BigInteger.ZERO).join();
    assertTrue(valid.bool());
    assertEquals(517_556_227L, valid.context().slot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"isBlockhashValid","params":["2M3LYsyXnBEpj1zsCGPfnAUoe4qCUQHbLY7fEm9uvAMe",{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_357L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.isBlockHashValid(BLOCKHASH, U64_MAX).join()), 517_556_357L);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"isBlockhashValid","params":["2M3LYsyXnBEpj1zsCGPfnAUoe4qCUQHbLY7fEm9uvAMe",{"commitment":"processed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":2483},"value":false},"id":1}""");
    assertFalse(rpcClient.isBlockHashValid(Commitment.PROCESSED, BLOCKHASH, null).join().bool());
  }

  @Test
  void feeForMessage() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getFeeForMessage","params":["%s",{"commitment":"confirmed","minContextSlot":517570444}]}""".formatted(FEE_MESSAGE), """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517570579},"value":5000},"id":1}""");
    var fee = rpcClient.getFeeForMessage(Commitment.CONFIRMED, FEE_MESSAGE, LATER).join();
    assertEquals(5_000L, fee.fee());
    assertEquals(517_570_579L, fee.context().slot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getFeeForMessage","params":["%s",{"commitment":"confirmed","minContextSlot":0}]}""".formatted(FEE_MESSAGE), """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517570559},"value":5000},"id":1}""");
    fee = rpcClient.getFeeForMessage(FEE_MESSAGE, BigInteger.ZERO).join();
    assertEquals(517_570_559L, fee.context().slot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getFeeForMessage","params":["%s",{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""".formatted(FEE_MESSAGE),
        notReached(517_570_593L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getFeeForMessage(FEE_MESSAGE, U64_MAX).join()), 517_570_593L);

    // the released body, and the released null for a blockhash the node no longer knows
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getFeeForMessage","params":["%s",{"commitment":"finalized"}]}""".formatted(FEE_MESSAGE), """
        {"jsonrpc":"2.0","result":{"context":{"slot":5068},"value":null},"id":1}""");
    assertNull(rpcClient.getFeeForMessage(Commitment.FINALIZED, FEE_MESSAGE, null).join());

    // a message shorter than the body's fixed part still builds the released body (synthetic)
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getFeeForMessage","params":["AQ==",{"commitment":"processed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":6},"value":10000},"id":1}""");
    assertEquals(10_000L, rpcClient.getFeeForMessage(Commitment.PROCESSED, "AQ==", null).join().fee());
  }

  @Test
  void blocksFromAStartSlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocks","params":[517556151,{"commitment":"confirmed","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":[517556151,517556152,517556153,517556154,517556155,517556156,517556157,517556158,517556159,517556160,517556161,517556162,517556163,517556164,517556165,517556166,517556167,517556168,517556169,517556170,517556171,517556172,517556173,517556174,517556175,517556176,517556177,517556178,517556179,517556180,517556181,517556182,517556183,517556188,517556189,517556190,517556191,517556192,517556193,517556194,517556195,517556196,517556197,517556198,517556199,517556200,517556201,517556202,517556203,517556204,517556205,517556206,517556207,517556208,517556209,517556210,517556211,517556212,517556213,517556214,517556215,517556216,517556217,517556218,517556219,517556220,517556221,517556222,517556223,517556224,517556225,517556226,517556227,517556228,517556229,517556230,517556231,517556232,517556233],"id":1}""");
    final var blocks = rpcClient.getBlocks(517_556_151L, BigInteger.ZERO).join();
    assertEquals(79, blocks.length);
    assertEquals(517_556_151L, blocks[0]);
    assertEquals(517_556_183L, blocks[32]);
    // four skipped slots, so the node's list is not a range
    assertEquals(517_556_188L, blocks[33]);
    assertEquals(517_556_233L, blocks[78]);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocks","params":[517556151,{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_363L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getBlocks(Commitment.CONFIRMED, 517_556_151L, U64_MAX).join()), 517_556_363L);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocks","params":[5,{"commitment":"finalized"}]}""", """
        {"jsonrpc":"2.0","result":[5,6],"id":1}""");
    assertArrayEquals(new long[]{5, 6}, rpcClient.getBlocks(Commitment.FINALIZED, 5, (BigInteger) null).join());
  }

  @Test
  void blocksInARange() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocks","params":[517556151,517556161,{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":[517556151,517556152,517556153,517556154,517556155,517556156,517556157,517556158,517556159,517556160,517556161],"id":1}""");
    var blocks = rpcClient.getBlocks(Commitment.CONFIRMED, 517_556_151L, 517_556_161L, RECENT).join();
    assertEquals(11, blocks.length);
    assertEquals(517_556_161L, blocks[10]);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocks","params":[517556151,517556161,{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_367L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getBlocks(517_556_151L, 517_556_161L, U64_MAX).join()), 517_556_367L);

    // finalized compares the minimum with the highest super-majority root rather than a bank slot
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocks","params":[517556121,517556131,{"commitment":"finalized","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":[517556121,517556122,517556123,517556124,517556125,517556126,517556127,517556128,517556129,517556130,517556131],"id":1}""");
    blocks = rpcClient.getBlocks(Commitment.FINALIZED, 517_556_121L, 517_556_131L, BigInteger.ZERO).join();
    assertEquals(11, blocks.length);
    assertEquals(517_556_121L, blocks[0]);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocks","params":[517556121,517556131,{"commitment":"finalized","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_371L));
    assertNotReached(joinExpectingRpcException(() ->
        rpcClient.getBlocks(Commitment.FINALIZED, 517_556_121L, 517_556_131L, U64_MAX).join()), 517_556_371L);

    // the end slot stays clamped to 500,000 slots after the start, and null still omits the option
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocks","params":[5,500005,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":[],"id":1}""");
    assertEquals(0, rpcClient.getBlocks(5, 600_000, (BigInteger) null).join().length);
  }

  @Test
  void blocksWithLimit() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocksWithLimit","params":[517556151,5,{"commitment":"confirmed","minContextSlot":517556081}]}""", """
        {"jsonrpc":"2.0","result":[517556151,517556152,517556153,517556154,517556155],"id":1}""");
    assertArrayEquals(
        new long[]{517_556_151L, 517_556_152L, 517_556_153L, 517_556_154L, 517_556_155L},
        rpcClient.getBlocksWithLimit(Commitment.CONFIRMED, 517_556_151L, 5, RECENT).join()
    );

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocksWithLimit","params":[517556151,5,{"commitment":"confirmed","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_373L));
    assertNotReached(joinExpectingRpcException(() -> rpcClient.getBlocksWithLimit(517_556_151L, 5, U64_MAX).join()), 517_556_373L);

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocksWithLimit","params":[517556121,5,{"commitment":"finalized","minContextSlot":0}]}""", """
        {"jsonrpc":"2.0","result":[517556121,517556122,517556123,517556124,517556125],"id":1}""");
    assertArrayEquals(
        new long[]{517_556_121L, 517_556_122L, 517_556_123L, 517_556_124L, 517_556_125L},
        rpcClient.getBlocksWithLimit(Commitment.FINALIZED, 517_556_121L, 5, BigInteger.ZERO).join()
    );

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocksWithLimit","params":[517556121,5,{"commitment":"finalized","minContextSlot":18446744073709551615}]}""",
        notReached(517_556_377L));
    assertNotReached(joinExpectingRpcException(() ->
        rpcClient.getBlocksWithLimit(Commitment.FINALIZED, 517_556_121L, 5, U64_MAX).join()), 517_556_377L);

    // the limit stays clamped to 500,000, and null still omits the option
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlocksWithLimit","params":[5,500000,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":[],"id":1}""");
    assertEquals(0, rpcClient.getBlocksWithLimit(5, 600_000, (BigInteger) null).join().length);
  }

  @Test
  void simulation() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":true,"innerInstructions":false,"commitment":"confirmed","minContextSlot":517570444}]}""".formatted(TX), """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517570585},"value":{"accounts":null,"err":"AccountNotFound","fee":null,"innerInstructions":null,"loadedAccountsDataSize":0,"loadedAddresses":{"readonly":[],"writable":[]},"logs":[],"postBalances":[0,1],"postTokenBalances":[],"preBalances":[0,1],"preTokenBalances":[],"replacementBlockhash":{"blockhash":"4upgkzLWSeaJL3t6dcz9SicTm3MRBBGhan1WtevCa2u7","lastValidBlockHeight":504740398},"returnData":null,"unitsConsumed":0}},"id":1}""");
    var simulation = rpcClient.simulateTransaction(Commitment.CONFIRMED, TX, true, false, List.of(), LATER).join();
    assertInstanceOf(TransactionError.AccountNotFound.class, simulation.error());
    assertEquals(517_570_585L, simulation.context().slot());
    assertEquals("4upgkzLWSeaJL3t6dcz9SicTm3MRBBGhan1WtevCa2u7", simulation.replacementBlockHash().blockhash());
    assertEquals(504_740_398L, simulation.replacementBlockHash().lastValidBlockHeight());
    assertEquals(List.of(0L, 1L), simulation.preBalances());
    assertEquals(OptionalInt.of(0), simulation.unitsConsumed());
    assertTrue(simulation.accounts().isEmpty());

    // a null account list omits the member like an empty one
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":true,"innerInstructions":false,"commitment":"confirmed","minContextSlot":0}]}""".formatted(TX), """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517570565},"value":{"accounts":null,"err":"AccountNotFound","fee":null,"innerInstructions":null,"loadedAccountsDataSize":0,"loadedAddresses":{"readonly":[],"writable":[]},"logs":[],"postBalances":[0,1],"postTokenBalances":[],"preBalances":[0,1],"preTokenBalances":[],"replacementBlockhash":{"blockhash":"EYQKvQaVD29PrcbLPFS5keyHWti9v2Dkobyd4auVKPnR","lastValidBlockHeight":504740378},"returnData":null,"unitsConsumed":0}},"id":1}""");
    simulation = rpcClient.simulateTransaction(Commitment.CONFIRMED, TX, true, false, null, BigInteger.ZERO).join();
    assertEquals(517_570_565L, simulation.context().slot());
    assertEquals("EYQKvQaVD29PrcbLPFS5keyHWti9v2Dkobyd4auVKPnR", simulation.replacementBlockHash().blockhash());
    assertTrue(simulation.accounts().isEmpty());

    // the minimum sits between the commitment and the requested accounts
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":true,"innerInstructions":true,"commitment":"confirmed","minContextSlot":517570444,"accounts":{"addresses":["2zprvnmJJ4szdmTHGqEpbqn29TRXezEtuCMZ9anGZd5y"],"encoding":"base64"}}]}""".formatted(TX), """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517570616},"value":{"accounts":[null],"err":"AccountNotFound","fee":null,"innerInstructions":null,"loadedAccountsDataSize":0,"loadedAddresses":{"readonly":[],"writable":[]},"logs":[],"postBalances":[0,1],"postTokenBalances":[],"preBalances":[0,1],"preTokenBalances":[],"replacementBlockhash":{"blockhash":"EicAZNKaR23tAqyok8F6XUjoVXina77LaNPTtuwZPL5s","lastValidBlockHeight":504740429},"returnData":null,"unitsConsumed":0}},"id":1}""");
    simulation = rpcClient.simulateTransaction(Commitment.CONFIRMED, TX, true, true, List.of(PAYER), LATER).join();
    assertEquals(517_570_616L, simulation.context().slot());
    assertEquals(1, simulation.accounts().size());
    assertNull(simulation.accounts().getFirst());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":true,"innerInstructions":false,"commitment":"confirmed","minContextSlot":18446744073709551615}]}""".formatted(TX),
        notReached(517_570_599L));
    assertNotReached(joinExpectingRpcException(() ->
        rpcClient.simulateTransaction(Commitment.CONFIRMED, TX, true, false, List.of(), U64_MAX).join()), 517_570_599L);

    // null leaves the released bodies, with and without accounts, as they were
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":false,"innerInstructions":true,"commitment":"finalized","accounts":{"addresses":["2zprvnmJJ4szdmTHGqEpbqn29TRXezEtuCMZ9anGZd5y"],"encoding":"base64"}}]}""".formatted(TX), """
        {"jsonrpc":"2.0","result":{"context":{"slot":218},"value":{"err":null,"accounts":[null],"logs":[],"unitsConsumed":150}},"id":1}""");
    simulation = rpcClient.simulateTransaction(Commitment.FINALIZED, TX, false, true, List.of(PAYER), null).join();
    assertNull(simulation.error());
    assertEquals(OptionalInt.of(150), simulation.unitsConsumed());
  }

  /// The `Transaction` convenience serializes the transaction itself and forwards every option.
  @Test
  void simulationOfATransactionObject() {
    final byte[] seed = new byte[32];
    java.util.Arrays.fill(seed, (byte) 3);
    final var signer = Signer.createFromPrivateKey(seed);
    final var transaction = Transaction.createTx(
        AccountMeta.createFeePayer(signer.publicKey()),
        List.of(Instruction.createInstruction(
            PublicKey.fromBase58Encoded("11111111111111111111111111111111"),
            List.of(AccountMeta.createWrite(PAYER)),
            new byte[]{7, 8, 9}))
    );
    final var base64 = transaction.base64EncodeToString();
    assertNotEquals(TX, base64);

    // synthetic: this transaction was never sent live
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":true,"innerInstructions":false,"commitment":"finalized","minContextSlot":517570444,"accounts":{"addresses":["2zprvnmJJ4szdmTHGqEpbqn29TRXezEtuCMZ9anGZd5y"],"encoding":"base64"}}]}""".formatted(base64), """
        {"jsonrpc":"2.0","result":{"context":{"slot":219},"value":{"err":null,"accounts":[null],"logs":[],"unitsConsumed":151}},"id":1}""");
    final var simulation = rpcClient.simulateTransaction(Commitment.FINALIZED, transaction, true, false, List.of(PAYER), LATER).join();
    assertEquals(OptionalInt.of(151), simulation.unitsConsumed());
    assertEquals(219L, simulation.context().slot());
    assertEquals(1, simulation.accounts().size());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":false,"innerInstructions":true,"commitment":"processed"}]}""".formatted(base64), """
        {"jsonrpc":"2.0","result":{"context":{"slot":220},"value":{"err":null,"accounts":null,"logs":[],"unitsConsumed":152}},"id":1}""");
    assertEquals(OptionalInt.of(152),
        rpcClient.simulateTransaction(Commitment.PROCESSED, transaction, false, true, null, null).join().unitsConsumed());
  }

  @Test
  void submission() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"sendTransaction","params":["%s",{"encoding":"base64","preflightCommitment":"confirmed","maxRetries":1,"minContextSlot":0}]}""".formatted(TX), """
        {"jsonrpc":"2.0","error":{"code":-32002,"message":"Transaction simulation failed: Transaction did not pass signature verification","data":{"accounts":null,"err":"SignatureFailure","fee":null,"innerInstructions":null,"loadedAccountsDataSize":0,"loadedAddresses":null,"logs":[],"postBalances":null,"postTokenBalances":null,"preBalances":null,"preTokenBalances":null,"replacementBlockhash":null,"returnData":null,"unitsConsumed":0}},"id":1}""");
    final var preflight = joinExpectingRpcException(() -> rpcClient.sendTransaction(TX, 1, BigInteger.ZERO).join());
    assertEquals(-32002, preflight.code());
    final var failure = assertInstanceOf(RpcCustomError.SendTransactionPreflightFailure.class, preflight.customError());
    assertInstanceOf(TransactionError.SignatureFailure.class, failure.simulation().error());
    assertEquals(OptionalInt.of(0), failure.simulation().unitsConsumed());

    // the minimum is checked before the signatures, so the unreachable one is the whole answer
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"sendTransaction","params":["%s",{"encoding":"base64","preflightCommitment":"confirmed","maxRetries":1,"minContextSlot":18446744073709551615}]}""".formatted(TX),
        notReached(517_570_609L));
    assertNotReached(joinExpectingRpcException(() ->
        rpcClient.sendTransaction(Commitment.CONFIRMED, TX, 1, U64_MAX).join()), 517_570_609L);

    // never sent live: skipping preflight keeps the released options and appends the minimum
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"sendTransaction","params":["%s",{"encoding":"base64","skipPreflight":true,"preflightCommitment":"processed","maxRetries":0,"minContextSlot":0}]}""".formatted(TX), """
        {"jsonrpc":"2.0","result":"2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAojnVuao8rkxwPYPe8cSwE5GzhEgJA2y8fVjDEo6iR6ykBvDxrTQrtpb","id":1}""");
    assertEquals(
        "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAojnVuao8rkxwPYPe8cSwE5GzhEgJA2y8fVjDEo6iR6ykBvDxrTQrtpb",
        rpcClient.sendTransactionSkipPreflight(TX, 0, BigInteger.ZERO).join()
    );

    // the answer borrows the captured preflight slot: this body was never sent live either
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"sendTransaction","params":["%s",{"encoding":"base64","skipPreflight":true,"preflightCommitment":"finalized","maxRetries":2,"minContextSlot":18446744073709551615}]}""".formatted(TX),
        notReached(517_570_609L));
    assertNotReached(joinExpectingRpcException(() ->
        rpcClient.sendTransactionSkipPreflight(Commitment.FINALIZED, TX, 2, U64_MAX).join()), 517_570_609L);

    // null leaves both released bodies as they were
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"sendTransaction","params":["%s",{"encoding":"base64","preflightCommitment":"processed","maxRetries":5}]}""".formatted(TX), """
        {"jsonrpc":"2.0","result":"5VERd3ZfLb2qBqZzbGjWEnwqZQFbHnLWuGh4H1oGrzXmMPHkOfkq8kSjMWZ7Vw6qLkyoBSc3mCQNMcxbNxJgpp1U","id":1}""");
    assertEquals(
        "5VERd3ZfLb2qBqZzbGjWEnwqZQFbHnLWuGh4H1oGrzXmMPHkOfkq8kSjMWZ7Vw6qLkyoBSc3mCQNMcxbNxJgpp1U",
        rpcClient.sendTransaction(Commitment.PROCESSED, TX, 5, null).join()
    );
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"sendTransaction","params":["%s",{"encoding":"base64","skipPreflight":true,"preflightCommitment":"confirmed","maxRetries":4}]}""".formatted(TX), """
        {"jsonrpc":"2.0","result":"5VERd3ZfLb2qBqZzbGjWEnwqZQFbHnLWuGh4H1oGrzXmMPHkOfkq8kSjMWZ7Vw6qLkyoBSc3mCQNMcxbNxJgpp1U","id":1}""");
    assertEquals(
        "5VERd3ZfLb2qBqZzbGjWEnwqZQFbHnLWuGh4H1oGrzXmMPHkOfkq8kSjMWZ7Vw6qLkyoBSc3mCQNMcxbNxJgpp1U",
        rpcClient.sendTransactionSkipPreflight(Commitment.CONFIRMED, TX, 4, null).join()
    );
  }
}
