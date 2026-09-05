package software.sava.rpc.json.http.client;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TxBuilder;

import java.util.Arrays;
import java.util.List;

/// Raw JSON-RPC bodies captured on 2026-08-24 from a local Agave 4.2.1 test validator, plus the
/// seeded keys that produced the transactions inside them, so every v1 wire byte a test compares
/// against can be rebuilt from Java alone and checked against what the validator actually served.
///
/// Two captures: `GATE_OFF_*` with the `enable_tx_v1` feature gate inactive, `GATE_ON_*` with it
/// active. Both used payer = [#seeded] `0x11` (`F25s3DdjXdCxYBhh2z8FBusVEMT4b9bGNFVKJi3wFoF4`) and
/// recipient = [#seeded] `0x12` (`3Atsbq9N5EaCc9YWmqD2rUVedX4pqDe7hyk6JSyWRTrG`), airdropped
/// 1 SOL, sending a System transfer wrapped in a SIMD-0385 v1 transaction. The bodies are verbatim:
/// nothing was reformatted or trimmed, including `null` members and `"rewards":null`.
final class V1AgaveTestFixtures {

  static final PublicKey SYSTEM_PROGRAM = PublicKey.fromBase58Encoded("11111111111111111111111111111111");

  static final int PAYER_SEED = 0x11;
  static final int RECIPIENT_SEED = 0x12;

  static Signer seeded(final int fill) {
    final byte[] seed = new byte[32];
    Arrays.fill(seed, (byte) fill);
    return Signer.createFromPrivateKey(seed);
  }

  /// `SystemInstruction::Transfer`: u32 LE discriminator 2, then the lamports as u64 LE.
  static final int SYSTEM_TRANSFER_DATA_LENGTH = 12;

  static Instruction transfer(final PublicKey from, final PublicKey to, final long lamports) {
    return transfer(from, to, lamports, 0);
  }

  /// A transfer whose instruction data carries `trailingBytes` of filler after the 12-byte
  /// payload. Agave's `limited_deserialize` ignores trailing bytes, so this is the cheapest way to
  /// push a v1 transaction past the legacy 1232-byte packet limit: the runtime still executes a
  /// 150 CU transfer (see the historical Agave 4.2.1 observations in `AGAVE_SYNC.md`).
  static Instruction transfer(final PublicKey from, final PublicKey to, final long lamports, final int trailingBytes) {
    final byte[] data = new byte[SYSTEM_TRANSFER_DATA_LENGTH + trailingBytes];
    ByteUtil.putInt32LE(data, 0, 2);
    ByteUtil.putInt64LE(data, 4, lamports);
    for (int i = SYSTEM_TRANSFER_DATA_LENGTH; i < data.length; ++i) {
      data[i] = (byte) ('A' + (i % 26));
    }
    return Instruction.createInstruction(
        AccountMeta.createInvoked(SYSTEM_PROGRAM),
        List.of(AccountMeta.createWritableSigner(from), AccountMeta.createWrite(to)),
        data
    );
  }

  /// Single-transfer v1 transaction; `0` for `fee` or `heap` leaves that config slot unset, so the
  /// builder's default mask (compute-unit limit + account-data-size limit) is what a `0` `fee` and
  /// `heap` produce. This is the exact construction the capture used.
  static Transaction v1Transfer(final Signer payer,
                                final PublicKey to,
                                final long lamports,
                                final long fee,
                                final int computeUnitLimit,
                                final int accountDataSizeLimit,
                                final int heap) {
    final var builder = TxBuilder.createBuilder()
        .feePayer(payer.publicKey())
        .addInstruction(transfer(payer.publicKey(), to, lamports))
        .computeUnitLimit(computeUnitLimit)
        .accountDataSizeLimit(accountDataSizeLimit);
    if (fee != 0) {
      builder.priorityFeeLamports(fee);
    }
    if (heap != 0) {
      builder.heapSize(heap);
    }
    return builder.createTransaction();
  }

  // ---------------------------------------------------------------------------------------------
  // Gate off: the validator refuses every v1 transaction with TransactionError::UnsupportedVersion.
  // The transaction: 10_000_000 lamports, fee 5000, CU 20000, data 65536, heap 65536.

  static final byte[] GATE_OFF_BLOCKHASH = Base58.decode("BRUKZ7KJWc1k3tyRVkYGoxDLBsSbLwPXB3dmBm6uf3Fb");
  static final String GATE_OFF_V1_SIGNATURE =
      "4u8jASyBwFtFxPvbdoAfTg6RfDui32eTjSJYbDKeVV28hWjsarbGgoDVGqCtoi4MT5mWDhHBahPmfi8ESvBWqkMZ";
  static final String GATE_OFF_V1_BASE64 =
      "gQEAAR8AAACa2bMKgbQ00vRASCQ7uDxeJ0iOu9Yfpj0l1PkLZR365gED0EqyMnQrtKs6E2i9RhXk5tAiSrcaAWuvhSCjMsl3hzcgQEDjZMEPK+ycH+UAoc1MJHyJ1lCgHtfoLKuoZ4d8IQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAiBMAAAAAAAAgTgAAAAABAAAAAQACAgwAAAECAAAAgJaYAAAAAADDAjqu1qmnQRHWkO7+/6OTTEO6eSCa8gnVNEngiwIOAAvKMWrgocW4Gv/UIOUcXAG9qYbZoSZvhX3m3KL9lx0E";

  /// `simulateTransaction` of the signed v1 transaction with the gate inactive.
  static final String GATE_OFF_SIMULATE_RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.2.1","slot":483},"value":{"accounts":null,"err":"UnsupportedVersion","fee":null,"innerInstructions":null,"loadedAccountsDataSize":0,"loadedAddresses":{"readonly":[],"writable":[]},"logs":[],"postBalances":[2000000000,0,1],"postTokenBalances":[],"preBalances":[2000000000,0,1],"preTokenBalances":[],"replacementBlockhash":null,"returnData":null,"unitsConsumed":0}},"id":1}""";

  /// `sendTransaction` with preflight of the same transaction with the gate inactive.
  static final String GATE_OFF_SEND_PREFLIGHT_ERROR = """
      {"jsonrpc":"2.0","error":{"code":-32002,"message":"Transaction simulation failed: Transaction version is unsupported","data":{"accounts":null,"err":"UnsupportedVersion","fee":null,"innerInstructions":null,"loadedAccountsDataSize":0,"loadedAddresses":null,"logs":[],"postBalances":null,"postTokenBalances":null,"preBalances":null,"preTokenBalances":null,"replacementBlockhash":null,"returnData":null,"unitsConsumed":0}},"id":3}""";

  /// `sendTransaction` with `skipPreflight` of the same transaction with the gate inactive: the node
  /// accepts and forwards it, and it then never lands.
  static final String GATE_OFF_SEND_SKIP_PREFLIGHT_RESPONSE = """
      {"jsonrpc":"2.0","result":"%s","id":4}""".formatted(GATE_OFF_V1_SIGNATURE);

  // ---------------------------------------------------------------------------------------------
  // Gate on: the same construction lands in slot 6 alongside a vote and a legacy control transfer.

  static final byte[] GATE_ON_BLOCKHASH = Base58.decode("F6zAJmFANkBykAHcJJUbBiC6GsjhA6jNKoytYRQe5mZK");
  static final String GATE_ON_V1_SIGNATURE =
      "3QTqrJw9xhoPmh6MHhzo2P9YwsH1r2jrMJqwpwZGxDhoe5uwtUD32pXbas4mFHAAUCo8CtjcpnNEq1ehwS61FVj3";
  static final String GATE_ON_LEGACY_CONTROL_SIGNATURE =
      "5zF8toQmWaK5hFxtHJ9zeW6sZjt9X4Qm1uRQaumjgHN4EqNAXuWua1eYv9E61MTcPL8EVSmMKtxnHEgV6FAGTjCA";

  /// The block-6 blockhash, used as the recent blockhash of the simulate-then-tighten transaction.
  static final byte[] BLOCK_6_BLOCKHASH = Base58.decode("CiaMdATaDdkz6tQwbvCMrWp2Z6HPi7SdW8uaPhnJzifT");

  /// `simulateTransaction` of the unsigned loose transaction: builder defaults (CU 1_400_000, data
  /// 64MiB), fee 5000, 1_000_000 lamports, recent blockhash [#BLOCK_6_BLOCKHASH].
  static final String GATE_ON_SIMULATE_LOOSE_RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.2.1","slot":6},"value":{"accounts":null,"err":null,"fee":10000,"innerInstructions":null,"loadedAccountsDataSize":213,"loadedAddresses":{"readonly":[],"writable":[]},"logs":["Program 11111111111111111111111111111111 invoke [1]","Program 11111111111111111111111111111111 success"],"postBalances":[987975000,12000000,1],"postTokenBalances":[],"preBalances":[988985000,11000000,1],"preTokenBalances":[],"replacementBlockhash":null,"returnData":null,"unitsConsumed":150}},"id":10}""";

  /// The loose transaction after `setComputeUnitLimit(150)` and `setAccountDataSizeLimit(213)`,
  /// signed. The validator accepted these exact bytes and confirmed them in slot 7.
  static final String TIGHTENED_SIGNATURE =
      "3t48h6LEnU4hnJ7onjhRGmLyE3BPLqEXpv917qFZo9EPRsVBioxH7gxdnC6sqvJVCpzBpkbaJvxdLiPH7e2wptFY";
  static final String TIGHTENED_BASE64 =
      "gQEAAQ8AAACuFxWqj2gcqIozOx0hxcpUML5i9f7XXNlZAcAQ1MHPkgED0EqyMnQrtKs6E2i9RhXk5tAiSrcaAWuvhSCjMsl3hzcgQEDjZMEPK+ycH+UAoc1MJHyJ1lCgHtfoLKuoZ4d8IQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAiBMAAAAAAACWAAAA1QAAAAICDAAAAQIAAABAQg8AAAAAAJAPoCZwdUocO11etXoqgdUtom82O6dDOggiJ4+79/HNgMzNXk0qDkGYcGHzl5+xgQoyifzcdCQ0i2V4bZsx9A8=";
  static final String GATE_ON_SEND_TIGHTENED_RESPONSE = """
      {"jsonrpc":"2.0","result":"%s","id":11}""".formatted(TIGHTENED_SIGNATURE);
  static final String GATE_ON_GET_TRANSACTION_TIGHTENED_RESPONSE = """
      {"jsonrpc":"2.0","result":{"blockTime":1787587902,"meta":{"computeUnitsConsumed":150,"costUnits":1481,"err":null,"fee":10000,"innerInstructions":[],"loadedAddresses":{"readonly":[],"writable":[]},"logMessages":["Program 11111111111111111111111111111111 invoke [1]","Program 11111111111111111111111111111111 success"],"postBalances":[987975000,12000000,1],"postTokenBalances":[],"preBalances":[988985000,11000000,1],"preTokenBalances":[],"rewards":[],"status":{"Ok":null}},"slot":7,"transaction":["%s","base64"],"transactionIndex":1,"version":1},"id":12}"""
      .formatted(TIGHTENED_BASE64);

  /// `sendTransaction` with preflight of a transfer whose compute-unit limit was cleared to `0`
  /// (`TxBuilder#computeUnitLimit(0)` drops the mask bit; the runtime then meters against 0).
  static final String GATE_ON_SEND_CU0_PREFLIGHT_ERROR = """
      {"jsonrpc":"2.0","error":{"code":-32002,"message":"Transaction simulation failed: Error processing Instruction 0: Computational budget exceeded","data":{"accounts":null,"err":{"InstructionError":[0,"ComputationalBudgetExceeded"]},"fee":10000,"innerInstructions":null,"loadedAccountsDataSize":213,"loadedAddresses":null,"logs":["Program 11111111111111111111111111111111 invoke [1]","Program 11111111111111111111111111111111 failed: Computational budget exceeded"],"postBalances":null,"postTokenBalances":null,"preBalances":null,"preTokenBalances":null,"replacementBlockhash":null,"returnData":null,"unitsConsumed":0}},"id":13}""";

  /// `getTransaction` without `maxSupportedTransactionVersion`, or `getBlock` with
  /// `maxSupportedTransactionVersion: 0`, for a v1 transaction: the node refuses with -32015 and no
  /// `data` member.
  static final String UNSUPPORTED_TRANSACTION_VERSION_ERROR = """
      {"jsonrpc":"2.0","error":{"code":-32015,"message":"Transaction version (1) is not supported by the requesting client. Please try the request again with the following configuration parameter: \\"maxSupportedTransactionVersion\\": 1"},"id":9}""";

  private V1AgaveTestFixtures() {
  }
}
