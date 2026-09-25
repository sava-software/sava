package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;

import java.util.Set;

/// The builtin programs that agave's `calculate_default_compute_unit_limit` budgets at
/// [#BUILTIN_COMPUTE_UNIT_LIMIT] per instruction, rather than
/// [#DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT], when a transaction requests no compute unit limit.
///
/// Fixed constants, not a [software.sava.core.accounts.SolanaAccounts] view: builtin status is a
/// runtime property, identical on every cluster. The set is closed (agave's
/// `BUILTIN_INSTRUCTION_COSTS`: "DO NOT ADD MORE ENTRIES", as an addition breaks consensus);
/// entries only leave, by migrating to BPF. Vote, the last migrating entry, is already evicted on
/// every public cluster (SIMD-0387's `bls_pubkey_management_in_vote_account` gate is active), so
/// it is budgeted as a non-builtin and needs no feature tracking.
final class BuiltinPrograms {

  /// `MAX_BUILTIN_ALLOCATION_COMPUTE_UNIT_LIMIT` in agave's `program-runtime`.
  static final int BUILTIN_COMPUTE_UNIT_LIMIT = 3_000;

  /// `DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT` in agave's `program-runtime`.
  static final int DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT = 200_000;

  private static final Set<PublicKey> NOT_MIGRATING = Set.of(
      PublicKey.fromBase58Encoded("11111111111111111111111111111111"),            // System
      PublicKey.fromBase58Encoded("ComputeBudget111111111111111111111111111111"), // ComputeBudget
      PublicKey.fromBase58Encoded("BPFLoaderUpgradeab1e11111111111111111111111"),
      PublicKey.fromBase58Encoded("BPFLoader1111111111111111111111111111111111"), // deprecated
      PublicKey.fromBase58Encoded("BPFLoader2111111111111111111111111111111111"),
      PublicKey.fromBase58Encoded("LoaderV411111111111111111111111111111111111"),
      PublicKey.fromBase58Encoded("KeccakSecp256k11111111111111111111111111111"),
      PublicKey.fromBase58Encoded("Ed25519SigVerify111111111111111111111111111")
  );

  /// The compute units the runtime allocates an instruction invoking `programId` when the
  /// transaction requests no limit.
  static int defaultComputeUnitLimit(final PublicKey programId) {
    return NOT_MIGRATING.contains(programId)
        ? BUILTIN_COMPUTE_UNIT_LIMIT
        : DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT;
  }

  private BuiltinPrograms() {
  }
}
