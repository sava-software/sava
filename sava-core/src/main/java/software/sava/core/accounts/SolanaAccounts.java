package software.sava.core.accounts;

import software.sava.core.accounts.meta.AccountMeta;

import static software.sava.core.accounts.PublicKey.fromBase58Encoded;

public interface SolanaAccounts {

  /// Every builder field defaults to its main-net address, see [SolanaAccountsBuilder].
  SolanaAccounts MAIN_NET = SolanaAccountsBuilder.builder().create();

  @Deprecated(forRemoval = true)
  static SolanaAccounts createAddressConstants(
      // Native
      final PublicKey systemProgram,
      final PublicKey configProgram,
      final PublicKey stakeProgram,
      final PublicKey stakeConfig,
      final PublicKey voteProgram,
      final PublicKey addressLookupTableProgram,
      final PublicKey bPFLoaderProgram,
      final PublicKey systemEd25519Program,
      final PublicKey secp256k1Program,
      final PublicKey zKTokenProofProgram,
      final PublicKey zkElGamalProofProgram,
      // Mint
      final PublicKey wrappedSolTokenMint,
      // Common
      final PublicKey computeBudgetProgram,
      final PublicKey tokenProgram,
      final PublicKey associatedTokenAccountProgram,
      final PublicKey token2022Program,
      final PublicKey tokenUpgradeProgram,
      final PublicKey memoProgram,
      final PublicKey memoProgramV2,
      final PublicKey nameServiceProgram,
      final PublicKey sharedMemoryProgram,
      final PublicKey featureProposalProgram,
      // Sysvar
      final PublicKey clockSysVar,
      final PublicKey epochScheduleSysVar,
      final PublicKey feesSysVar,
      final PublicKey instructionsSysVar,
      final PublicKey recentBlockhashesSysVar,
      final PublicKey rentSysVar,
      final PublicKey slotHashesSysVar,
      final PublicKey slotHistorySysVar,
      final PublicKey stakeHistorySysVar,
      final PublicKey epochRewardsSysVar,
      final PublicKey lastRestartSlotSysVar) {
    return SolanaAccountsBuilder.builder()
        .systemProgram(systemProgram)
        .configProgram(configProgram)
        .stakeProgram(stakeProgram)
        .stakeConfig(stakeConfig)
        .voteProgram(voteProgram)
        .addressLookupTableProgram(addressLookupTableProgram)
        .bPFLoaderProgram(bPFLoaderProgram)
        .ed25519Program(systemEd25519Program)
        .secp256k1Program(secp256k1Program)
        .zKTokenProofProgram(zKTokenProofProgram)
        .zkElGamalProofProgram(zkElGamalProofProgram)
        .wrappedSolTokenMint(wrappedSolTokenMint)
        .computeBudgetProgram(computeBudgetProgram)
        .tokenProgram(tokenProgram)
        .associatedTokenAccountProgram(associatedTokenAccountProgram)
        .token2022Program(token2022Program)
        .tokenUpgradeProgram(tokenUpgradeProgram)
        .memoProgram(memoProgram)
        .memoProgramV2(memoProgramV2)
        .nameServiceProgram(nameServiceProgram)
        .sharedMemoryProgram(sharedMemoryProgram)
        .featureProposalProgram(featureProposalProgram)
        .clockSysVar(clockSysVar)
        .epochScheduleSysVar(epochScheduleSysVar)
        .feesSysVar(feesSysVar)
        .instructionsSysVar(instructionsSysVar)
        .recentBlockhashesSysVar(recentBlockhashesSysVar)
        .rentSysVar(rentSysVar)
        .slotHashesSysVar(slotHashesSysVar)
        .slotHistorySysVar(slotHistorySysVar)
        .stakeHistorySysVar(stakeHistorySysVar)
        .epochRewardsSysVar(epochRewardsSysVar)
        .lastRestartSlotSysVar(lastRestartSlotSysVar)
        .create();
  }

  @Deprecated(forRemoval = true)
  static SolanaAccounts createAddressConstants(
      // Native
      final String systemProgram,
      final String configProgram,
      final String stakeProgram,
      final String stakeConfig,
      final String voteProgram,
      final String addressLookupTableProgram,
      final String bPFLoaderProgram,
      final String systemEd25519Program,
      final String secp256k1Program,
      final String zKTokenProofProgram,
      final String zkElGamalProofProgram,
      // Mint
      final String wrappedSolTokenMint,
      // Common
      final String computeBudgetProgram,
      final String tokenProgram,
      final String associatedTokenAccountProgram,
      final String token2022Program,
      final String tokenUpgradeProgram,
      final String memoProgram,
      final String memoProgramV2,
      final String nameServiceProgram,
      final String sharedMemoryProgram,
      final String featureProposalProgram,
      // Sysvar
      final String clockSysVar,
      final String epochScheduleSysVar,
      final String feesSysVar,
      final String instructionsSysVar,
      final String recentBlockhashesSysVar,
      final String rentSysVar,
      final String slotHashesSysVar,
      final String slotHistorySysVar,
      final String stakeHistorySysVar,
      final String epochRewardsSysVar,
      final String lastRestartSlotSysVar) {
    return createAddressConstants(
        // Native
        fromBase58Encoded(systemProgram),
        fromBase58Encoded(configProgram),
        fromBase58Encoded(stakeProgram),
        fromBase58Encoded(stakeConfig),
        fromBase58Encoded(voteProgram),
        fromBase58Encoded(addressLookupTableProgram),
        fromBase58Encoded(bPFLoaderProgram),
        fromBase58Encoded(systemEd25519Program),
        fromBase58Encoded(secp256k1Program),
        fromBase58Encoded(zKTokenProofProgram),
        fromBase58Encoded(zkElGamalProofProgram),
        // Mint
        fromBase58Encoded(wrappedSolTokenMint),
        // Common
        fromBase58Encoded(computeBudgetProgram),
        fromBase58Encoded(tokenProgram),
        fromBase58Encoded(associatedTokenAccountProgram),
        fromBase58Encoded(token2022Program),
        fromBase58Encoded(tokenUpgradeProgram),
        fromBase58Encoded(memoProgram),
        fromBase58Encoded(memoProgramV2),
        fromBase58Encoded(nameServiceProgram),
        fromBase58Encoded(sharedMemoryProgram),
        fromBase58Encoded(featureProposalProgram),
        // Sysvar
        fromBase58Encoded(clockSysVar),
        fromBase58Encoded(epochScheduleSysVar),
        fromBase58Encoded(feesSysVar),
        fromBase58Encoded(instructionsSysVar),
        fromBase58Encoded(recentBlockhashesSysVar),
        fromBase58Encoded(rentSysVar),
        fromBase58Encoded(slotHashesSysVar),
        fromBase58Encoded(slotHistorySysVar),
        fromBase58Encoded(stakeHistorySysVar),
        fromBase58Encoded(epochRewardsSysVar),
        fromBase58Encoded(lastRestartSlotSysVar)
    );
  }

  // Mint

  PublicKey wrappedSolTokenMint();

  AccountMeta readWrappedSolTokenMint();

  // Common

  PublicKey computeBudgetProgram();

  AccountMeta invokedComputeBudgetProgram();

  PublicKey tokenProgram();

  AccountMeta invokedTokenProgram();

  AccountMeta readTokenProgram();

  PublicKey associatedTokenAccountProgram();

  AccountMeta invokedAssociatedTokenAccountProgram();

  AccountMeta readAssociatedTokenAccountProgram();

  PublicKey token2022Program();

  AccountMeta invokedToken2022Program();

  AccountMeta readToken2022Program();

  PublicKey tokenUpgradeProgram();

  AccountMeta invokedTokenUpgradeProgram();

  AccountMeta readTokenUpgradeProgram();

  PublicKey memoProgram();

  AccountMeta invokedMemoProgram();

  AccountMeta readMemoProgram();

  PublicKey memoProgramV2();

  AccountMeta invokedMemoProgramV2();

  AccountMeta readMemoProgramV2();

  PublicKey nameServiceProgram();

  AccountMeta invokedNameServiceProgram();

  AccountMeta readNameServiceProgram();

  PublicKey sharedMemoryProgram();

  AccountMeta invokedSharedMemoryProgram();

  AccountMeta readSharedMemoryProgram();

  PublicKey featureProposalProgram();

  AccountMeta invokedFeatureProposalProgram();

  AccountMeta readFeatureProposalProgram();

  // Native

  PublicKey systemProgram();

  AccountMeta invokedSystemProgram();

  AccountMeta readSystemProgram();

  PublicKey configProgram();

  AccountMeta invokedConfigProgram();

  AccountMeta readConfigProgram();

  PublicKey stakeProgram();

  AccountMeta invokedStakeProgram();

  /// Deprecated upstream, the stake config account is no longer used by the stake program.
  @Deprecated
  PublicKey stakeConfig();

  @Deprecated
  AccountMeta readStakeConfig();

  AccountMeta readStakeProgram();

  PublicKey voteProgram();

  AccountMeta invokedVoteProgram();

  AccountMeta readVoteProgram();

  PublicKey addressLookupTableProgram();

  AccountMeta invokedAddressLookupTableProgram();

  AccountMeta readAddressLookupTableProgram();

  PublicKey bPFLoaderProgram();

  AccountMeta invokedBPFLoaderProgram();

  AccountMeta readBPFLoaderProgram();

  PublicKey ed25519Program();

  AccountMeta invokedEd25519Program();

  AccountMeta readEd25519Program();

  PublicKey secp256k1Program();

  AccountMeta invokedSecp256k1Program();

  AccountMeta readSecp256k1Program();

  PublicKey secp256r1Program();

  AccountMeta invokedSecp256r1Program();

  AccountMeta readSecp256r1Program();

  /// Deprecated upstream, superseded by [#zkElGamalProofProgram()].
  @Deprecated
  PublicKey zKTokenProofProgram();

  @Deprecated
  AccountMeta invokedZKTokenProofProgram();

  @Deprecated
  AccountMeta readZKTokenProofProgram();

  PublicKey zkElGamalProofProgram();

  AccountMeta invokedZkElGamalProofProgram();

  AccountMeta readZkElGamalProofProgram();

  // Sysvar

  /// Owner of the sysvar accounts.
  PublicKey sysvarOwner();

  AccountMeta readSysvarOwner();

  PublicKey clockSysVar();

  AccountMeta readClockSysVar();

  PublicKey epochScheduleSysVar();

  AccountMeta readEpochScheduleSysVar();

  /// Deprecated upstream, the Fees sysvar is no longer updated by the runtime.
  @Deprecated
  PublicKey feesSysVar();

  @Deprecated
  AccountMeta readFeesSysVar();

  PublicKey instructionsSysVar();

  AccountMeta readInstructionsSysVar();

  /// Deprecated upstream, the RecentBlockhashes sysvar is no longer updated by the
  /// runtime, use the getLatestBlockhash RPC method.
  @Deprecated
  PublicKey recentBlockhashesSysVar();

  @Deprecated
  AccountMeta readRecentBlockhashesSysVar();

  PublicKey rentSysVar();

  AccountMeta readRentSysVar();

  PublicKey slotHashesSysVar();

  AccountMeta readSlotHashesSysVar();

  PublicKey slotHistorySysVar();

  AccountMeta readSlotHistorySysVar();

  PublicKey stakeHistorySysVar();

  AccountMeta readStakeHistorySysVar();

  PublicKey epochRewardsSysVar();

  AccountMeta readEpochRewardsSysVar();

  PublicKey lastRestartSlotSysVar();

  AccountMeta readLastRestartSlotSysVar();
}
