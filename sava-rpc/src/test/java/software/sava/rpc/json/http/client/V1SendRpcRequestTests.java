package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.core.tx.TxBuilder;
import software.sava.rpc.json.http.response.IxError;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.response.RpcCustomError;
import software.sava.rpc.json.http.response.TransactionError;

import java.util.Base64;
import java.util.HexFormat;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.client.V1AgaveTestFixtures.*;

/// The send and simulate paths for SIMD-0385 v1 transactions, held to two external oracles:
///
/// - `@solana/kit` 8.0.0's signed wire bytes for a fixed-seed transfer, which pins sava's
///   build -> sign -> encode -> send path independently of anything sava wrote itself; and
/// - the raw bodies an Agave 4.2.1 test validator served for sava-built transactions, before and
///   after the `enable_tx_v1` gate, which pin how those bodies parse and — for the
///   simulate-then-tighten flow — that the bytes sava re-signs after updating the config section
///   in place are the bytes the validator accepted and confirmed.
///
/// Request bodies are asserted verbatim by the stub server, so a change to the option set sava
/// sends for `sendTransaction` / `simulateTransaction` / `getTransaction` fails here too.
final class V1SendRpcRequestTests extends RpcRequestTests {

  private static final HexFormat HEX = HexFormat.of();

  private static String sendRequest(final String base64Tx, final String options) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sendTransaction\",\"params\":[\""
        + base64Tx + "\",{\"encoding\":\"base64\"," + options + "}]}";
  }

  private static String simulateRequest(final String base64Tx, final boolean replaceRecentBlockhash) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"simulateTransaction\",\"params\":[\""
        + base64Tx + "\",{\"encoding\":\"base64\",\"sigVerify\":false,\"replaceRecentBlockhash\":"
        + replaceRecentBlockhash + ",\"innerInstructions\":false,\"commitment\":\"confirmed\"}]}";
  }

  private static String getTransactionRequest(final String signature) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getTransaction\",\"params\":[\"" + signature
        + "\",{\"commitment\":\"confirmed\",\"maxSupportedTransactionVersion\":1,\"encoding\":\"base64\"}]}";
  }

  private static JsonRpcException joinExpectingRpcException(final Runnable join) {
    final var ex = assertThrows(CompletionException.class, join::run);
    return assertInstanceOf(JsonRpcException.class, ex.getCause());
  }

  /// `a_example_config` from the `@solana/kit` 8.0.0 differential: payer seed `32 x 0x01`
  /// (`AKnL4NNf3DGWZJS6cPknBuEGnVsV4A4m5tgebLHaRSZ9`), recipient seed `32 x 0x02`, blockhash
  /// `32 x 0x04`, a 1-lamport transfer, and all four config slots set (fee 5000, CU 20000, data
  /// 65536, heap 65536). `signedWireHex` is kit's output, not sava's; the base64 payload of the
  /// `sendTransaction` request sava emits must be its exact encoding, and the signature the stub
  /// echoes back must be the one sava computed, so both the message bytes and the ed25519 signature
  /// over them are pinned by a second implementation.
  @Test
  void sendsAKitIdenticalV1Transaction() {
    final byte[] kitSignedWire = HEX.parseHex(
        "810100011f000000"
            + "0404040404040404040404040404040404040404040404040404040404040404"
            + "0103"
            + "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"
            + "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394"
            + "0000000000000000000000000000000000000000000000000000000000000000"
            + "8813000000000000" + "204e0000" + "00000100" + "00000100"
            + "02020c000001020000000100000000000000"
            + "52568d39b933135eaaca30803658eef20d475acd813a5d5a28d6a7ea33a87d4d"
            + "4b4a91642620ea5735f4a253df2ccd879e2eecb70c75af4fcce64d5269f2f800"
    );
    assertEquals(240, kitSignedWire.length);
    final var kitSignature = Base58.encode(kitSignedWire, 240 - Transaction.SIGNATURE_LENGTH, 240);

    final var payer = seeded(0x01);
    final var recipient = seeded(0x02).publicKey();
    assertEquals("AKnL4NNf3DGWZJS6cPknBuEGnVsV4A4m5tgebLHaRSZ9", payer.publicKey().toBase58());
    assertEquals("9hSR6S7WPtxmTojgo6GG3k4yDPecgJY292j7xrsUGWBu", recipient.toBase58());
    final byte[] blockHash = new byte[32];
    java.util.Arrays.fill(blockHash, (byte) 0x04);

    final var transaction = TxBuilder.createBuilder()
        .feePayer(payer.publicKey())
        .addInstruction(transfer(payer.publicKey(), recipient, 1L))
        .priorityFeeLamports(5_000L)
        .computeUnitLimit(20_000)
        .accountDataSizeLimit(65_536)
        .heapSize(65_536)
        .createTransaction();

    registerRequest(
        sendRequest(Base64.getEncoder().encodeToString(kitSignedWire), "\"preflightCommitment\":\"confirmed\",\"maxRetries\":1"),
        "{\"jsonrpc\":\"2.0\",\"result\":\"" + kitSignature + "\",\"id\":1}"
    );

    final var signature = rpcClient.sendTransaction(transaction, payer, blockHash).join();

    assertEquals(kitSignature, signature);
    assertEquals(kitSignature, transaction.getBase58Id(), "sava's signature must be kit's");
    assertArrayEquals(kitSignedWire, transaction.serialized());
    assertEquals(1, transaction.version());
    assertEquals(240, transaction.size());
  }

  /// The gate-on flow the validator confirmed end to end. A transaction is built with the
  /// builder's serialized defaults (CU 1_400_000, data 64MiB — deliberately loose, see
  /// AGAVE_SYNC.md), simulated unsigned, then tightened *in place* to what the simulation
  /// measured, signed and sent. The bytes sava sends must be the ones Agave accepted, and the
  /// bytes Agave then served back for the confirmed signature must be the same again.
  @Test
  void simulateThenTightenRoundTrip() {
    final var payer = seeded(PAYER_SEED);
    final var recipient = seeded(RECIPIENT_SEED).publicKey();

    final var transaction = TxBuilder.createBuilder()
        .feePayer(payer.publicKey())
        .addInstruction(transfer(payer.publicKey(), recipient, 1_000_000L))
        .priorityFeeLamports(5_000L)
        .createTransaction();
    transaction.setRecentBlockHash(BLOCK_6_BLOCKHASH);
    assertEquals(1, transaction.version());
    assertNotEquals(TIGHTENED_BASE64, transaction.base64EncodeToString(), "loose bytes differ from tightened");

    registerRequest(simulateRequest(transaction.base64EncodeToString(), false), GATE_ON_SIMULATE_LOOSE_RESPONSE);
    final var simulation = rpcClient.simulateTransaction(transaction, false).join();
    assertNull(simulation.error());
    assertEquals(6, simulation.context().slot());
    assertEquals("4.2.1", simulation.context().apiVersion());
    assertEquals(OptionalInt.of(150), simulation.unitsConsumed());
    assertEquals(213, simulation.loadedAccountsDataSize());
    assertEquals(OptionalLong.of(10_000), simulation.fee());
    assertNull(simulation.replacementBlockHash());
    assertEquals(2, simulation.logs().size());

    transaction.setComputeUnitLimit(simulation.unitsConsumed().orElseThrow());
    transaction.setAccountDataSizeLimit(simulation.loadedAccountsDataSize());

    registerRequest(
        sendRequest(TIGHTENED_BASE64, "\"preflightCommitment\":\"confirmed\",\"maxRetries\":1"),
        GATE_ON_SEND_TIGHTENED_RESPONSE
    );
    final var signature = rpcClient.sendTransaction(transaction, payer, BLOCK_6_BLOCKHASH).join();
    assertEquals(TIGHTENED_SIGNATURE, signature);
    assertEquals(TIGHTENED_SIGNATURE, transaction.getBase58Id());
    assertEquals(TIGHTENED_BASE64, transaction.base64EncodeToString());

    registerRequest(getTransactionRequest(signature), GATE_ON_GET_TRANSACTION_TIGHTENED_RESPONSE);
    final var confirmed = rpcClient.getTransaction(signature).join();
    assertEquals(1, confirmed.version());
    assertEquals(7L, confirmed.slot());
    assertEquals(150, confirmed.meta().computeUnitsConsumed());
    assertArrayEquals(transaction.serialized(), confirmed.data());
    assertArrayEquals(Base64.getDecoder().decode(TIGHTENED_BASE64), confirmed.data());

    final var skeleton = confirmed.skeleton();
    assertEquals(5_000L, skeleton.priorityFeeLamports());
    assertEquals(150, skeleton.computeUnitLimit());
    assertEquals(213, skeleton.accountDataSizeLimit());
    assertEquals(0, skeleton.heapSize(), "heap was never requested");
    assertArrayEquals(BLOCK_6_BLOCKHASH, skeleton.blockHash());
    assertEquals(1, skeleton.numSignatures());
  }

  /// Before activation the runtime rejects a v1 transaction as a whole — `err` is the top-level
  /// string `UnsupportedVersion` rather than an `InstructionError` — and reports nothing consumed.
  /// The seeded transaction is rebuilt here so the bytes simulated are the captured ones.
  @Test
  void simulateBeforeActivationReportsUnsupportedVersion() {
    final var payer = seeded(PAYER_SEED);
    final var recipient = seeded(RECIPIENT_SEED).publicKey();
    final var transaction = v1Transfer(payer, recipient, 10_000_000L, 5_000L, 20_000, 65_536, 65_536);
    transaction.setRecentBlockHash(GATE_OFF_BLOCKHASH);
    transaction.sign(payer);
    assertEquals(GATE_OFF_V1_BASE64, transaction.base64EncodeToString());
    assertEquals(GATE_OFF_V1_SIGNATURE, transaction.getBase58Id());

    registerRequest(simulateRequest(GATE_OFF_V1_BASE64, false), GATE_OFF_SIMULATE_RESPONSE);
    final var simulation = rpcClient.simulateTransaction(transaction, false).join();

    assertInstanceOf(TransactionError.UnsupportedVersion.class, simulation.error());
    assertEquals(483, simulation.context().slot());
    assertEquals(OptionalInt.of(0), simulation.unitsConsumed());
    assertEquals(0, simulation.loadedAccountsDataSize());
    assertTrue(simulation.fee().isEmpty());
    assertTrue(simulation.logs().isEmpty());
    assertEquals(3, simulation.preBalances().size());
    assertEquals(simulation.preBalances(), simulation.postBalances(), "nothing moved");
  }

  /// Sending the same transaction with preflight surfaces the rejection as a
  /// [RpcCustomError.SendTransactionPreflightFailure] whose simulation carries the same
  /// `UnsupportedVersion`; with preflight skipped the node accepts the signature and forwards the
  /// bytes — which then never land — so a caller cannot rely on `skipPreflight` to detect a
  /// pre-activation cluster.
  @Test
  void sendBeforeActivationFailsPreflightAndIsAcceptedWithoutIt() {
    final var payer = seeded(PAYER_SEED);
    final var recipient = seeded(RECIPIENT_SEED).publicKey();
    final var transaction = v1Transfer(payer, recipient, 10_000_000L, 5_000L, 20_000, 65_536, 65_536);

    registerRequest(
        sendRequest(GATE_OFF_V1_BASE64, "\"preflightCommitment\":\"confirmed\",\"maxRetries\":1"),
        GATE_OFF_SEND_PREFLIGHT_ERROR
    );
    final var exception = joinExpectingRpcException(
        () -> rpcClient.sendTransaction(transaction, payer, GATE_OFF_BLOCKHASH).join()
    );
    assertEquals(-32002, exception.code());
    assertEquals("Transaction simulation failed: Transaction version is unsupported", exception.getMessage());
    if (exception.customError() instanceof RpcCustomError.SendTransactionPreflightFailure(final var simulation)) {
      assertInstanceOf(TransactionError.UnsupportedVersion.class, simulation.error());
      assertEquals(OptionalInt.of(0), simulation.unitsConsumed());
      assertTrue(simulation.fee().isEmpty());
    } else {
      fail(exception.customError().getClass().getSimpleName());
    }

    // Signed in place by the failed send; the bytes are the captured ones.
    assertEquals(GATE_OFF_V1_BASE64, transaction.base64EncodeToString());

    registerRequest(
        sendRequest(GATE_OFF_V1_BASE64, "\"skipPreflight\":true,\"preflightCommitment\":\"processed\",\"maxRetries\":0"),
        GATE_OFF_SEND_SKIP_PREFLIGHT_RESPONSE
    );
    assertEquals(GATE_OFF_V1_SIGNATURE, rpcClient.sendTransactionSkipPreflight(transaction.base64EncodeToString()).join());
    assertEquals(GATE_OFF_V1_SIGNATURE, transaction.getBase58Id());
  }

  /// `TxBuilder#computeUnitLimit(0)` clears the mask bit rather than writing a 0, and the runtime
  /// reads an absent limit as 0 (AGAVE_SYNC.md, "Deliberate divergences"), so the first metered
  /// instruction fails preflight with `ComputationalBudgetExceeded` at index 0 and zero units
  /// consumed. The preflight body still prices the transaction: `fee` is 10000 (base 5000 plus the
  /// 5000 priority fee) and the accounts were loaded (213 bytes).
  @Test
  void sendClearedComputeUnitLimitFailsPreflightAtTheFirstInstruction() {
    final var payer = seeded(PAYER_SEED);
    final var recipient = seeded(RECIPIENT_SEED).publicKey();
    final var transaction = v1Transfer(payer, recipient, 1_000L, 5_000L, 0, 64 * 1024 * 1024, 0);
    transaction.setRecentBlockHash(BLOCK_6_BLOCKHASH);
    transaction.sign(payer);
    final var skeleton = TransactionSkeleton.deserializeSkeleton(transaction.serialized());
    assertEquals(0, skeleton.computeUnitLimit());
    assertEquals(64 * 1024 * 1024, skeleton.accountDataSizeLimit());
    assertEquals(5_000L, skeleton.priorityFeeLamports());
    assertThrows(IllegalStateException.class, () -> transaction.setComputeUnitLimit(150),
        "a cleared slot cannot be updated in place");

    registerRequest(
        sendRequest(transaction.base64EncodeToString(), "\"preflightCommitment\":\"confirmed\",\"maxRetries\":1"),
        GATE_ON_SEND_CU0_PREFLIGHT_ERROR
    );
    final var exception = joinExpectingRpcException(
        () -> rpcClient.sendTransaction(transaction.base64EncodeToString()).join()
    );
    assertEquals(-32002, exception.code());
    if (exception.customError() instanceof RpcCustomError.SendTransactionPreflightFailure(final var simulation)) {
      if (simulation.error() instanceof TransactionError.InstructionError(final int index, final var ixError)) {
        assertEquals(0, index);
        assertInstanceOf(IxError.ComputationalBudgetExceeded.class, ixError);
      } else {
        fail(String.valueOf(simulation.error()));
      }
      assertEquals(OptionalInt.of(0), simulation.unitsConsumed());
      assertEquals(OptionalLong.of(10_000), simulation.fee());
      assertEquals(213, simulation.loadedAccountsDataSize());
    } else {
      fail(exception.customError().getClass().getSimpleName());
    }
  }
}
