package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;

import java.util.Set;

/// The builtin programs the runtime budgets differently when a transaction requests no compute unit
/// limit of its own.
///
/// agave's `calculate_default_compute_unit_limit` allocates each builtin instruction only
/// [#BUILTIN_COMPUTE_UNIT_LIMIT] units and every other instruction
/// [#DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT], so a transaction made entirely of builtin
/// instructions — a plain System transfer being the most common shape there is — is budgeted
/// roughly 66 times less than a flat per-instruction estimate would suggest.
///
/// These are deliberately fixed constants rather than a [software.sava.core.accounts.SolanaAccounts]
/// view: builtin status is a property of the runtime, identical on every cluster, and is not
/// configurable the way the program ids a caller cares about are.
///
/// The set is closed. agave's `BUILTIN_INSTRUCTION_COSTS` carries an explicit
/// "DO NOT ADD MORE ENTRIES TO THIS MAP", because adding one changes the cost model and so breaks
/// consensus; entries only ever leave, by migrating to on-chain BPF. Its one remaining migrating
/// entry, the Vote program, is already evicted on every public cluster — SIMD-0387's
/// `bls_pubkey_management_in_vote_account` gate is active — so Vote is budgeted as a non-builtin
/// here and no feature tracking is needed.
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

  /// The compute units the runtime allocates an instruction invoking this program when the
  /// transaction requests no limit of its own.
  static int defaultComputeUnitLimit(final PublicKey programId) {
    return NOT_MIGRATING.contains(programId)
        ? BUILTIN_COMPUTE_UNIT_LIMIT
        : DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT;
  }

  private BuiltinPrograms() {
  }
}
