package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.core.tx.TxBuilder;
import software.sava.rpc.json.http.request.BlockTxDetails;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/// Live check of sava-built SIMD-0385 transaction v1 against a real Agave `solana-test-validator`
/// (4.2.1 or later) whose `enable_tx_v1` gate is active. It is the only place the v1 builder, the
/// in-place config setters, the v1 arms of the response parsers and the `-32015` / `-32002` error
/// mappings meet a validator; the offline oracles for the wire format are the Rust and kit fixtures
/// under `sava-core/src/test/solana/`. Not part of the default test suite or CI, run on demand:
///
/// `SAVA_V1_LIVE=true ./gradlew :sava-rpc:test --tests '*LiveV1ValidatorCheck'`
///
/// The endpoint defaults to `http://127.0.0.1:8899` and is overridden by `SAVA_V1_RPC_URL`; the
/// validator must also serve airdrops. `sava-rpc/src/test/solana/v1-live/README.md` documents how
/// to start one. Before anything is sent, the `enable_tx_v1` feature account is read and decoded,
/// and the whole class is skipped — not failed — when the gate is inactive, so the check stays
/// honest on a pre-activation cluster instead of reporting `UnsupportedVersion` as a sava defect.
///
/// Keys are fresh per run on purpose: a fixed payer re-sending the same transfer inside one
/// blockhash window would be refused as `AlreadyProcessed`, which says nothing about v1.
@EnabledIfEnvironmentVariable(named = "SAVA_V1_LIVE", matches = "true")
final class LiveV1ValidatorCheck {

  private static final String DEFAULT_RPC_URL = "http://127.0.0.1:8899";
  /// `enable_tx_v1`, the SIMD-0385 activation gate (`agave:feature-set/src/lib.rs`).
  private static final PublicKey ENABLE_TX_V1 = PublicKey.fromBase58Encoded("txv1aq4pp281K9um3tnPgkfX8UqtFT6wcVW3hNezGLL");
  /// Owner of every feature account. Deliberately absent from `SolanaAccounts` (see AGAVE_SYNC.md).
  private static final PublicKey FEATURE_PROGRAM = PublicKey.fromBase58Encoded("Feature111111111111111111111111111111111111");
  /// `solana-feature-gate-interface` `Feature { activated_at: Option<u64> }` in bincode: a one-byte
  /// tag, then the activation slot as u64 LE only when the tag is 1.
  private static final int FEATURE_ACCOUNT_LENGTH = 9;

  /// Per-signature base fee of the local cluster (`solana-test-validator` genesis default).
  private static final long BASE_FEE = 5_000L;
  private static final long AIRDROP = 1_000_000_000L;
  private static final int LEGACY_PACKET_LIMIT = 1_232;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  /// Generous next to the client's 8s default: a fresh validator can pause on its first blocks.
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private static URI rpcUrl;
  private static HttpClient httpClient;
  private static SolanaRpcClient rpcClient;

  @BeforeAll
  static void requireActiveGate() {
    final var url = System.getenv("SAVA_V1_RPC_URL");
    rpcUrl = URI.create(url == null || url.isBlank() ? DEFAULT_RPC_URL : url);
    httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    rpcClient = SolanaRpcClient.createClient(rpcUrl, httpClient, Commitment.CONFIRMED);

    // An unreachable endpoint is a failure, not a skip: SAVA_V1_LIVE=true asked for a live run, and
    // silently skipping would report a validator that was never there as "checked". Only an
    // answering cluster whose gate is inactive is skipped.
    final AccountInfo<byte[]> featureAccount;
    try {
      featureAccount = rpcClient.getAccountInfo(ENABLE_TX_V1).join();
    } catch (final CompletionException e) {
      throw new AssertionError(
          "no validator answering at " + rpcUrl + " (set SAVA_V1_RPC_URL or start one via"
              + " sava-rpc/src/test/solana/v1-live/start.sh): " + e.getCause(),
          e.getCause()
      );
    }
    final var activationSlot = activationSlot(featureAccount);
    Assumptions.assumeTrue(
        activationSlot.isPresent(),
        "enable_tx_v1 (" + ENABLE_TX_V1 + ") is not active on " + rpcUrl
            + "; a v1 transaction would be refused with UnsupportedVersion, so nothing here is checkable."
            + " Start a 4.2.1+ solana-test-validator (sava-rpc/src/test/solana/v1-live/README.md)."
    );
    System.out.println("[v1-live] enable_tx_v1 active since slot " + activationSlot.getAsLong() + " on " + rpcUrl);
  }

  @AfterAll
  static void closeClient() {
    if (httpClient != null) {
      httpClient.close();
    }
  }

  /// Decodes the feature account per `solana-feature-gate-interface`: an absent account, a foreign
  /// owner, a short body or a `None` tag all read as inactive. Both halves of the tag are decoded
  /// here rather than trusting `lamports > 0`, because a staged-but-inactive feature is funded too.
  static OptionalLong activationSlot(final AccountInfo<byte[]> info) {
    if (info == null || !FEATURE_PROGRAM.equals(info.owner())) {
      return OptionalLong.empty();
    }
    final byte[] data = info.data();
    if (data == null || data.length < FEATURE_ACCOUNT_LENGTH || data[0] != 1) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(ByteUtil.getInt64LE(data, 1));
  }

  // -------------------------------------------------------------------------------------------
  // helpers

  private static Signer randomSigner() {
    final byte[] seed = new byte[Signer.KEY_LENGTH];
    RANDOM.nextBytes(seed);
    return Signer.createFromPrivateKey(seed);
  }

  private static Signer fundedSigner() throws InterruptedException {
    final var signer = randomSigner();
    awaitConfirmed(rpcClient.requestAirdrop(signer.publicKey(), AIRDROP).join());
    return signer;
  }

  private static TxStatus awaitConfirmed(final String signature) throws InterruptedException {
    for (int i = 0; i < 240; ++i) {
      final var status = rpcClient.getSignatureStatuses(List.of(signature)).join().get(signature);
      if (status != null && !status.nil()) {
        assertNull(status.error(), () -> signature + " failed on chain");
        final var confirmation = status.confirmationStatus();
        if (confirmation == Commitment.CONFIRMED || confirmation == Commitment.FINALIZED) {
          return status;
        }
      }
      Thread.sleep(250);
    }
    throw new AssertionError("timed out waiting for confirmation of " + signature);
  }

  /// The validator serves `getTransaction` a moment after `getSignatureStatuses` reports the slot.
  private static Tx fetchTx(final String signature) throws InterruptedException {
    for (int i = 0; i < 40; ++i) {
      final var tx = rpcClient.getTransaction(signature).join();
      if (tx != null && tx.data() != null) {
        return tx;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("getTransaction never served " + signature);
  }

  private static byte[] blockHash() {
    return Base58.decode(rpcClient.getLatestBlockHash().join().blockHash());
  }

  /// The same encoder the offline fixtures use, so a live transfer and a replayed one are built
  /// the same way; see [V1AgaveTestFixtures#transfer(PublicKey, PublicKey, long, int)].
  private static Instruction transferIx(final PublicKey from,
                                        final PublicKey to,
                                        final long lamports,
                                        final int trailingBytes) {
    return V1AgaveTestFixtures.transfer(from, to, lamports, trailingBytes);
  }

  private static Instruction transferIx(final PublicKey from, final PublicKey to, final long lamports) {
    return transferIx(from, to, lamports, 0);
  }

  private static TxBuilder transferBuilder(final Signer payer, final long lamports) {
    return TxBuilder.createBuilder()
        .feePayer(payer.publicKey())
        .addInstruction(transferIx(payer.publicKey(), randomSigner().publicKey(), lamports));
  }

  /// Signs, sends, confirms, and reads the transaction back, asserting the two invariants every
  /// landed v1 transaction must satisfy: the node labels it version 1 and returns the sent bytes
  /// unchanged — `Tx.data()` is the base64 body decoded by sava's parser, so a byte-exact match
  /// covers the v1 arm of that parser as well as the builder.
  private static Tx sendAndReadBack(final Transaction tx, final Signer... signers) throws InterruptedException {
    for (final var signer : signers) {
      tx.sign(signer);
    }
    final byte[] sent = tx.serialized();
    final var signature = rpcClient.sendTransaction(tx.base64EncodeToString()).join();
    assertEquals(tx.getBase58Id(), signature, "the node must derive the same id from the first signature");
    awaitConfirmed(signature);
    final var landed = fetchTx(signature);
    assertEquals(1, landed.version(), "getTransaction must label the transaction version 1");
    assertArrayEquals(sent, landed.data(), "landed bytes must equal the sent bytes");
    assertNull(landed.meta().error());
    return landed;
  }

  /// Bypasses the client on purpose (the request shapes under test are the ones it never sends),
  /// but keeps its bounded-request discipline: a stalled validator fails the check instead of
  /// hanging Gradle.
  private static String rawRpc(final String body) throws Exception {
    final var request = HttpRequest.newBuilder(rpcUrl)
        .timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
  }

  /// Parses the `error` member of a raw JSON-RPC response through sava's own error parser, or
  /// returns null when the response carries a `result` instead.
  private static JsonRpcException rpcError(final String responseBody) {
    final var ji = JsonIterator.parse(responseBody);
    return ji.skipUntil("error") == null
        ? null
        : JsonRpcException.parseException(ji, OptionalLong.empty());
  }

  private static void assertUnsupportedVersion(final String responseBody) {
    final var error = rpcError(responseBody);
    assertNotNull(error, () -> "expected a JSON-RPC error in: " + responseBody);
    assertEquals(-32015, error.code());
    assertInstanceOf(RpcCustomError.UnsupportedTransactionVersion.class, error.customError());
    assertTrue(
        error.getMessage().contains("\"maxSupportedTransactionVersion\": 1"),
        () -> "the node must name the ceiling that would have served the request: " + error.getMessage()
    );
  }

  // -------------------------------------------------------------------------------------------
  // cases

  /// Case 1: a v1 transfer with every config value set lands byte-exact, the node charges exactly
  /// `base + priorityFeeLamports` — v1's fee is an absolute lamport amount, not a per-unit price —
  /// the skeleton reads every config value back from the landed bytes, and full `getBlock` (which
  /// sends `maxSupportedTransactionVersion: 1`) contains the same bytes.
  @Test
  void basicV1Transfer() throws Exception {
    final var payer = fundedSigner();
    final var tx = transferBuilder(payer, 10_000_000L)
        .priorityFeeLamports(5_000)
        .computeUnitLimit(20_000)
        .accountDataSizeLimit(65_536)
        .heapSize(65_536)
        .createTransaction();
    assertEquals(1, tx.version());
    tx.setRecentBlockHash(blockHash());

    final var landed = sendAndReadBack(tx, payer);
    assertEquals(BASE_FEE + 5_000, landed.meta().fee());

    final var skeleton = TransactionSkeleton.deserializeSkeleton(landed.data());
    assertEquals(1, skeleton.version());
    assertEquals(5_000, skeleton.priorityFeeLamports());
    assertEquals(20_000, skeleton.computeUnitLimit());
    assertEquals(65_536, skeleton.accountDataSizeLimit());
    assertEquals(65_536, skeleton.heapSize());
    assertEquals(1, skeleton.numSignatures());
    assertEquals(1, skeleton.numInstructions());

    final var block = rpcClient.getBlock(landed.slot(), BlockTxDetails.full).join();
    assertNotNull(block);
    assertNotNull(block.transactions());
    assertTrue(
        block.transactions().stream().anyMatch(blockTx -> Arrays.equals(blockTx.data(), landed.data())),
        "full getBlock must carry the byte-exact v1 transaction"
    );
    System.out.println("[v1-live] basicV1Transfer OK slot=" + landed.slot());
  }

  /// Case 2, over raw HTTP so the request shapes are explicit: once a v1 transaction exists, a
  /// `getTransaction` or full `getBlock` whose `maxSupportedTransactionVersion` is omitted or 0
  /// fails the whole response with `-32015`, which sava maps to
  /// [RpcCustomError.UnsupportedTransactionVersion]; a ceiling of 1 serves it; and the
  /// `signatures` detail level is served at ceiling 0 because that arm is never version-checked
  /// (which is why sava omits the parameter there — see AGAVE_SYNC.md).
  @Test
  void versionCeiling() throws Exception {
    final var payer = fundedSigner();
    final var tx = transferBuilder(payer, 1_000_000L)
        .priorityFeeLamports(5_000)
        .computeUnitLimit(20_000)
        .accountDataSizeLimit(65_536)
        .createTransaction();
    tx.setRecentBlockHash(blockHash());
    final var landed = sendAndReadBack(tx, payer);
    final var signature = tx.getBase58Id();
    final long slot = landed.slot();

    assertUnsupportedVersion(rawRpc("""
        {"jsonrpc":"2.0","id":1,"method":"getTransaction","params":["%s",{"commitment":"confirmed","encoding":"base64"}]}""".formatted(signature)));
    assertUnsupportedVersion(rawRpc("""
        {"jsonrpc":"2.0","id":2,"method":"getTransaction","params":["%s",{"commitment":"confirmed","encoding":"base64","maxSupportedTransactionVersion":0}]}""".formatted(signature)));
    assertUnsupportedVersion(rawRpc("""
        {"jsonrpc":"2.0","id":3,"method":"getBlock","params":[%d,{"commitment":"confirmed","encoding":"base64","transactionDetails":"full","rewards":false}]}""".formatted(slot)));
    assertUnsupportedVersion(rawRpc("""
        {"jsonrpc":"2.0","id":4,"method":"getBlock","params":[%d,{"commitment":"confirmed","encoding":"base64","transactionDetails":"full","rewards":false,"maxSupportedTransactionVersion":0}]}""".formatted(slot)));

    final var served = rawRpc("""
        {"jsonrpc":"2.0","id":5,"method":"getTransaction","params":["%s",{"commitment":"confirmed","encoding":"base64","maxSupportedTransactionVersion":1}]}""".formatted(signature));
    assertNull(rpcError(served), () -> "ceiling 1 must serve the transaction: " + served);
    assertTrue(served.contains("\"version\":1"), served);

    final var signaturesOnly = rawRpc("""
        {"jsonrpc":"2.0","id":6,"method":"getBlock","params":[%d,{"commitment":"confirmed","transactionDetails":"signatures","rewards":false,"maxSupportedTransactionVersion":0}]}""".formatted(slot));
    assertNull(rpcError(signaturesOnly), () -> "the signatures detail level is not version-checked: " + signaturesOnly);
    assertTrue(signaturesOnly.contains(signature), signaturesOnly);
    System.out.println("[v1-live] versionCeiling OK slot=" + slot);
  }

  /// Case 3: a v1 transaction larger than the 1232-byte legacy packet lands byte-exact. The bulk
  /// is trailing instruction data on a system transfer, which the system program ignores, so the
  /// transaction stays at the transfer's own cost — simulation and execution report the same units.
  /// Pins that the builder emits the u16 data length, the RPC accepts a >1232-byte base64 body for
  /// a `0x81` payload, and the parser reads the size back.
  @Test
  void largeV1Transaction() throws Exception {
    final var payer = fundedSigner();
    final var tx = TxBuilder.createBuilder()
        .feePayer(payer.publicKey())
        .addInstruction(transferIx(payer.publicKey(), randomSigner().publicKey(), 1_000_000L, 3_000))
        .priorityFeeLamports(5_000)
        .computeUnitLimit(1_400_000)
        .createTransaction();
    tx.setRecentBlockHash(blockHash());
    assertTrue(tx.size() > LEGACY_PACKET_LIMIT, "must exceed the legacy packet limit: " + tx.size());
    assertFalse(tx.exceedsSizeLimit(), "must stay within the v1 limit: " + tx.size());

    final var simulation = rpcClient.simulateTransaction(tx).join();
    assertNull(simulation.error(), () -> "simulation: " + simulation);
    final int simulatedUnits = simulation.unitsConsumed().orElseThrow();

    final var landed = sendAndReadBack(tx, payer);
    assertTrue(landed.data().length > LEGACY_PACKET_LIMIT);
    assertEquals(simulatedUnits, landed.meta().computeUnitsConsumed());
    System.out.println("[v1-live] largeV1Transaction OK size=" + landed.data().length + " units=" + simulatedUnits);
  }

  /// Case 4a, the simulate-then-tighten flow: the builder defaults reserve both limit slots at
  /// their maxima so the message simulates at its final size, the in-place setters overwrite the
  /// reserved values without rebuilding, and the landed transaction carries exactly the measured
  /// values and executes with them.
  @Test
  void simulateThenTighten() throws Exception {
    final var payer = fundedSigner();
    final var tx = transferBuilder(payer, 1_000_000L)
        .priorityFeeLamports(5_000)
        .createTransaction();
    tx.setRecentBlockHash(blockHash());

    final var provisory = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertEquals(1_400_000, provisory.computeUnitLimit(), "builder default reserves the CU slot at the runtime maximum");
    assertEquals(64 * 1024 * 1024, provisory.accountDataSizeLimit(), "builder default reserves the data-size slot at the runtime maximum");

    final var simulation = rpcClient.simulateTransaction(tx).join();
    assertNull(simulation.error(), () -> "simulation: " + simulation);
    final int units = simulation.unitsConsumed().orElseThrow();
    final int loaded = simulation.loadedAccountsDataSize();
    assertTrue(units > 0 && units < 1_400_000, () -> "units=" + units);
    assertTrue(loaded > 0, () -> "loaded=" + loaded);

    tx.setComputeUnitLimit(units);
    tx.setAccountDataSizeLimit(loaded);
    final var landed = sendAndReadBack(tx, payer);
    final var skeleton = TransactionSkeleton.deserializeSkeleton(landed.data());
    assertEquals(units, skeleton.computeUnitLimit());
    assertEquals(loaded, skeleton.accountDataSizeLimit());
    assertEquals(units, landed.meta().computeUnitsConsumed(), "the tightened limit is exactly the measured cost");
    System.out.println("[v1-live] simulateThenTighten OK units=" + units + " loaded=" + loaded);
  }

  /// Case 4b: `computeUnitLimit(0)` clears the mask bit, and a v1 transaction without a
  /// compute-unit limit runs with a 0-unit budget — the empirical basis for the builder-default
  /// row in AGAVE_SYNC.md's divergence table. Simulation reports
  /// `InstructionError(0, ComputationalBudgetExceeded)` with 0 units consumed, and preflight
  /// refuses the send with `-32002`, which sava maps to
  /// [RpcCustomError.SendTransactionPreflightFailure] carrying that same simulation.
  @Test
  void clearedComputeUnitLimitIsRejected() throws Exception {
    final var payer = fundedSigner();
    final var tx = transferBuilder(payer, 1_000_000L)
        .priorityFeeLamports(5_000)
        .computeUnitLimit(0)
        .createTransaction();
    tx.setRecentBlockHash(blockHash());
    assertEquals(0, TransactionSkeleton.deserializeSkeleton(tx.serialized()).computeUnitLimit());
    assertThrows(IllegalStateException.class, () -> tx.setComputeUnitLimit(20_000), "a cleared slot cannot be set in place");

    final var simulation = rpcClient.simulateTransaction(tx).join();
    final var simulationError = assertInstanceOf(TransactionError.InstructionError.class, simulation.error());
    assertEquals(0, simulationError.ixIndex());
    assertInstanceOf(IxError.ComputationalBudgetExceeded.class, simulationError.ixError());
    assertEquals(0, simulation.unitsConsumed().orElseThrow());

    tx.sign(payer);
    final var base64 = tx.base64EncodeToString();
    final var thrown = assertThrows(CompletionException.class, () -> rpcClient.sendTransaction(base64).join());
    final var rpcException = assertInstanceOf(JsonRpcException.class, thrown.getCause());
    assertEquals(-32002, rpcException.code());
    final var preflight = assertInstanceOf(RpcCustomError.SendTransactionPreflightFailure.class, rpcException.customError());
    final var preflightError = assertInstanceOf(TransactionError.InstructionError.class, preflight.simulation().error());
    assertInstanceOf(IxError.ComputationalBudgetExceeded.class, preflightError.ixError());
    assertEquals(0, preflight.simulation().unitsConsumed().orElseThrow());
    System.out.println("[v1-live] clearedComputeUnitLimitIsRejected OK");
  }

  /// Case 5: two required signatures. The header's `num_required_signatures` drives the trailing
  /// signature block, both signers are charged the base fee, and the priority fee is charged once.
  @Test
  void twoSigners() throws Exception {
    final var payer = fundedSigner();
    final var second = fundedSigner();
    final var tx = TxBuilder.createBuilder()
        .feePayer(payer.publicKey())
        .addInstruction(transferIx(payer.publicKey(), randomSigner().publicKey(), 1_000_000L))
        .addInstruction(transferIx(second.publicKey(), randomSigner().publicKey(), 2_000_000L))
        .priorityFeeLamports(5_000)
        .computeUnitLimit(20_000)
        .accountDataSizeLimit(65_536)
        .createTransaction();
    assertEquals(2, tx.numSigners());
    tx.setRecentBlockHash(blockHash());

    final var landed = sendAndReadBack(tx, payer, second);
    assertEquals(2, TransactionSkeleton.deserializeSkeleton(landed.data()).numSignatures());
    assertEquals(2 * BASE_FEE + 5_000, landed.meta().fee());
    System.out.println("[v1-live] twoSigners OK slot=" + landed.slot());
  }

  /// Case 6: `priorityFeeLamports` is charged verbatim. The same transfer with a priority fee of
  /// 0 pays exactly the base fee, and with 5000 pays exactly 5000 more — no per-unit pricing, no
  /// rounding, no dependence on the compute-unit limit.
  @Test
  void priorityFeeIsAbsoluteLamports() throws Exception {
    final var payer = fundedSigner();
    final long[] priority = {0L, 5_000L};
    final long[] fees = new long[priority.length];
    for (int i = 0; i < priority.length; ++i) {
      final var tx = transferBuilder(payer, 1_000_000L)
          .priorityFeeLamports(priority[i])
          .computeUnitLimit(20_000)
          .accountDataSizeLimit(65_536)
          .createTransaction();
      tx.setRecentBlockHash(blockHash());
      assertEquals(priority[i], TransactionSkeleton.deserializeSkeleton(tx.serialized()).priorityFeeLamports());
      fees[i] = sendAndReadBack(tx, payer).meta().fee();
    }
    assertEquals(BASE_FEE, fees[0]);
    assertEquals(5_000L, fees[1] - fees[0]);
    System.out.println("[v1-live] priorityFeeIsAbsoluteLamports OK fees=" + Arrays.toString(fees));
  }
}
