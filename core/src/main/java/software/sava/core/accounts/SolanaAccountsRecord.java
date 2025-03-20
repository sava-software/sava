package software.sava.core.accounts;

import software.sava.core.accounts.meta.AccountMeta;

record SolanaAccountsRecord(
    // Common
    PublicKey systemProgram,
    AccountMeta invokedSystemProgram,
    AccountMeta readSystemProgram,
    PublicKey configProgram,
    AccountMeta invokedConfigProgram,
    AccountMeta readConfigProgram,
    PublicKey stakeProgram,
    AccountMeta invokedStakeProgram,
    AccountMeta readStakeProgram,
    PublicKey stakeConfig,
    AccountMeta readStakeConfig,
    PublicKey voteProgram,
    AccountMeta invokedVoteProgram,
    AccountMeta readVoteProgram,
    PublicKey addressLookupTableProgram,
    AccountMeta invokedAddressLookupTableProgram,
    AccountMeta readAddressLookupTableProgram,
    PublicKey bPFLoaderProgram,
    AccountMeta invokedBPFLoaderProgram,
    AccountMeta readBPFLoaderProgram,
    PublicKey ed25519Program,
    AccountMeta invokedEd25519Program,
    AccountMeta readEd25519Program,
    PublicKey secp256k1Program,
    AccountMeta invokedSecp256k1Program,
    AccountMeta readSecp256k1Program,
    PublicKey zKTokenProofProgram,
    AccountMeta invokedZKTokenProofProgram,
    AccountMeta readZKTokenProofProgram,
    // Mint
    PublicKey wrappedSolTokenMint,
    AccountMeta readWrappedSolTokenMint,
    // Common
    PublicKey computeBudgetProgram,
    AccountMeta invokedComputeBudgetProgram,
    PublicKey tokenProgram,
    AccountMeta invokedTokenProgram,
    AccountMeta readTokenProgram,
    PublicKey associatedTokenAccountProgram,
    AccountMeta invokedAssociatedTokenAccountProgram,
    AccountMeta readAssociatedTokenAccountProgram,
    PublicKey token2022Program,
    AccountMeta invokedToken2022Program,
    AccountMeta readToken2022Program,
    PublicKey tokenUpgradeProgram,
    AccountMeta invokedTokenUpgradeProgram,
    AccountMeta readTokenUpgradeProgram,
    PublicKey memoProgram,
    AccountMeta invokedMemoProgram,
    AccountMeta readMemoProgram,
    AccountMeta invokedMemoProgramV2,
    AccountMeta readMemoProgramV2,
    PublicKey nameServiceProgram,
    AccountMeta invokedNameServiceProgram,
    AccountMeta readNameServiceProgram,
    PublicKey sharedMemoryProgram,
    AccountMeta invokedSharedMemoryProgram,
    AccountMeta readSharedMemoryProgram,
    PublicKey featureProposalProgram,
    AccountMeta invokedFeatureProposalProgram,
    AccountMeta readFeatureProposalProgram,
    // Sysvar
    PublicKey clockSysVar,
    AccountMeta readClockSysVar,
    PublicKey epochScheduleSysVar,
    AccountMeta readEpochScheduleSysVar,
    PublicKey feesSysVar,
    AccountMeta readFeesSysVar,
    PublicKey instructionsSysVar,
    AccountMeta readInstructionsSysVar,
    PublicKey recentBlockhashesSysVar,
    AccountMeta readRecentBlockhashesSysVar,
    PublicKey rentSysVar,
    AccountMeta readRentSysVar,
    PublicKey slotHashesSysVar,
    AccountMeta readSlotHashesSysVar,
    PublicKey slotHistorySysVar,
    AccountMeta readSlotHistorySysVar,
    PublicKey stakeHistorySysVar,
    AccountMeta readStakeHistorySysVar,
    PublicKey epochRewardsSysVar,
    AccountMeta readEpochRewardsSysVar,
    PublicKey lastRestartSlotSysVar,
    AccountMeta readLastRestartSlotSysVar) implements SolanaAccounts {
}
