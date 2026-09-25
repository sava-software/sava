package software.sava.core.accounts;

import software.sava.core.accounts.meta.AccountMeta;

public interface SolanaAccounts {

  SolanaAccounts MAIN_NET = SolanaAccountsBuilder.builder().create();

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

  /// The stake program no longer reads this account, and Solana has deprecated its id.
  PublicKey stakeConfig();

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

  PublicKey zkElGamalProofProgram();

  AccountMeta invokedZkElGamalProofProgram();

  AccountMeta readZkElGamalProofProgram();

  // Sysvar

  PublicKey sysvarOwner();

  AccountMeta readSysvarOwner();

  PublicKey clockSysVar();

  AccountMeta readClockSysVar();

  PublicKey epochScheduleSysVar();

  AccountMeta readEpochScheduleSysVar();

  PublicKey instructionsSysVar();

  AccountMeta readInstructionsSysVar();

  /// @deprecated Solana deprecated the RecentBlockhashes sysvar; use the `getLatestBlockhash`
  /// RPC method for a recent blockhash.
  @Deprecated
  PublicKey recentBlockhashesSysVar();

  /// @deprecated as for [#recentBlockhashesSysVar()].
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
