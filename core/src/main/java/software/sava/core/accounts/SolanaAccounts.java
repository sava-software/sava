package software.sava.core.accounts;

import software.sava.core.accounts.meta.AccountMeta;

import static software.sava.core.accounts.meta.AccountMeta.createInvoked;
import static software.sava.core.accounts.meta.AccountMeta.createRead;
import static software.sava.core.accounts.PublicKey.fromBase58Encoded;

public interface SolanaAccounts {

  SolanaAccounts MAIN_NET = createAddressConstants(
      "11111111111111111111111111111111",
      "Config1111111111111111111111111111111111111",
      "Stake11111111111111111111111111111111111111",
      "StakeConfig11111111111111111111111111111111",
      "Vote111111111111111111111111111111111111111",
      "AddressLookupTab1e1111111111111111111111111",
      "BPFLoaderUpgradeab1e11111111111111111111111",
      "Ed25519SigVerify111111111111111111111111111",
      "KeccakSecp256k11111111111111111111111111111",
      "ZkTokenProof1111111111111111111111111111111",
      "So11111111111111111111111111111111111111112",
      "ComputeBudget111111111111111111111111111111",
      "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
      "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL",
      "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",
      "TkupDoNseygccBCjSsrSpMccjwHfTYwcrjpnDSrFDhC",
      "Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo",
      "namesLPneVptA9Z5rqUDD9tMTWEJwofgaYwp8cawRkX",
      "shmem4EWT2sPdVGvTZCzXXRAURL9G5vpPxNwSeKhHUL",
      "Feat1YXHhH6t1juaWF74WLcfv4XoNocjXA6sPWHNgAse",
      "SysvarC1ock11111111111111111111111111111111",
      "SysvarEpochSchedu1e111111111111111111111111",
      "SysvarFees111111111111111111111111111111111",
      "Sysvar1nstructions1111111111111111111111111",
      "SysvarRecentB1ockHashes11111111111111111111",
      "SysvarRent111111111111111111111111111111111",
      "SysvarS1otHashes111111111111111111111111111",
      "SysvarS1otHistory11111111111111111111111111",
      "SysvarStakeHistory1111111111111111111111111",
      "SysvarEpochRewards1111111111111111111111111",
      "SysvarLastRestartS1ot1111111111111111111111"
  );

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
      // Mint
      final PublicKey wrappedSolTokenMint,
      // Common
      final PublicKey computeBudgetProgram,
      final PublicKey tokenProgram,
      final PublicKey associatedTokenAccountProgram,
      final PublicKey token2022Program,
      final PublicKey tokenUpgradeProgram,
      final PublicKey memoProgram,
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
    return new SolanaAccountsRecord(
        // Native
        systemProgram,
        createInvoked(systemProgram),
        createRead(systemProgram),
        configProgram,
        createInvoked(configProgram),
        createRead(configProgram),
        stakeProgram,
        createInvoked(stakeProgram),
        createRead(stakeProgram),
        stakeConfig,
        createRead(stakeConfig),
        voteProgram,
        createInvoked(voteProgram),
        createRead(voteProgram),
        addressLookupTableProgram,
        createInvoked(addressLookupTableProgram),
        createRead(addressLookupTableProgram),
        bPFLoaderProgram,
        createInvoked(bPFLoaderProgram),
        createRead(bPFLoaderProgram),
        systemEd25519Program,
        createInvoked(systemEd25519Program),
        createRead(systemEd25519Program),
        secp256k1Program,
        createInvoked(secp256k1Program),
        createRead(secp256k1Program),
        zKTokenProofProgram,
        createInvoked(zKTokenProofProgram),
        createRead(zKTokenProofProgram),
        // Mint
        wrappedSolTokenMint,
        createRead(wrappedSolTokenMint),
        // Common
        computeBudgetProgram,
        createInvoked(computeBudgetProgram),
        tokenProgram,
        createInvoked(tokenProgram),
        createRead(tokenProgram),
        associatedTokenAccountProgram,
        createInvoked(associatedTokenAccountProgram),
        createRead(associatedTokenAccountProgram),
        token2022Program,
        createInvoked(token2022Program),
        createRead(token2022Program),
        tokenUpgradeProgram,
        createInvoked(tokenUpgradeProgram),
        createRead(tokenUpgradeProgram),
        memoProgram,
        createInvoked(memoProgram),
        createRead(memoProgram),
        nameServiceProgram,
        createInvoked(nameServiceProgram),
        createRead(nameServiceProgram),
        sharedMemoryProgram,
        createInvoked(sharedMemoryProgram),
        createRead(sharedMemoryProgram),
        featureProposalProgram,
        createInvoked(featureProposalProgram),
        createRead(featureProposalProgram),
        // Sysvar
        clockSysVar,
        createRead(clockSysVar),
        epochScheduleSysVar,
        createRead(epochScheduleSysVar),
        feesSysVar,
        createRead(feesSysVar),
        instructionsSysVar,
        createRead(instructionsSysVar),
        recentBlockhashesSysVar,
        createRead(recentBlockhashesSysVar),
        rentSysVar,
        createRead(rentSysVar),
        slotHashesSysVar,
        createRead(slotHashesSysVar),
        slotHistorySysVar,
        createRead(slotHistorySysVar),
        stakeHistorySysVar,
        createRead(stakeHistorySysVar),
        epochRewardsSysVar,
        createRead(epochRewardsSysVar),
        lastRestartSlotSysVar,
        createRead(lastRestartSlotSysVar)
    );
  }

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
      // Mint
      final String wrappedSolTokenMint,
      // Common
      final String computeBudgetProgram,
      final String tokenProgram,
      final String associatedTokenAccountProgram,
      final String token2022Program,
      final String tokenUpgradeProgram,
      final String memoProgram,
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
        // Mint
        fromBase58Encoded(wrappedSolTokenMint),
        fromBase58Encoded(computeBudgetProgram),
        // Common
        fromBase58Encoded(tokenProgram),
        fromBase58Encoded(associatedTokenAccountProgram),
        fromBase58Encoded(token2022Program),
        fromBase58Encoded(tokenUpgradeProgram),
        fromBase58Encoded(memoProgram),
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

  PublicKey zKTokenProofProgram();

  AccountMeta invokedZKTokenProofProgram();

  AccountMeta readZKTokenProofProgram();

  // Sysvar

  PublicKey clockSysVar();

  AccountMeta readClockSysVar();

  PublicKey epochScheduleSysVar();

  AccountMeta readEpochScheduleSysVar();

  PublicKey feesSysVar();

  AccountMeta readFeesSysVar();

  PublicKey instructionsSysVar();

  AccountMeta readInstructionsSysVar();

  PublicKey recentBlockhashesSysVar();

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
