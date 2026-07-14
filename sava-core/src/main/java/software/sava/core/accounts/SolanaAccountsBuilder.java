package software.sava.core.accounts;

import static software.sava.core.accounts.PublicKey.fromBase58Encoded;
import static software.sava.core.accounts.meta.AccountMeta.*;

/// Builds a [SolanaAccounts] view. Every address defaults to its main-net value, override
/// only the accounts which differ for your cluster or fork.
public final class SolanaAccountsBuilder {

  // Native
  private PublicKey systemProgram = fromBase58Encoded("11111111111111111111111111111111");
  private PublicKey configProgram = fromBase58Encoded("Config1111111111111111111111111111111111111");
  private PublicKey stakeProgram = fromBase58Encoded("Stake11111111111111111111111111111111111111");
  private PublicKey stakeConfig = fromBase58Encoded("StakeConfig11111111111111111111111111111111");
  private PublicKey voteProgram = fromBase58Encoded("Vote111111111111111111111111111111111111111");
  private PublicKey addressLookupTableProgram = fromBase58Encoded("AddressLookupTab1e1111111111111111111111111");
  private PublicKey bPFLoaderProgram = fromBase58Encoded("BPFLoaderUpgradeab1e11111111111111111111111");
  private PublicKey ed25519Program = fromBase58Encoded("Ed25519SigVerify111111111111111111111111111");
  private PublicKey secp256k1Program = fromBase58Encoded("KeccakSecp256k11111111111111111111111111111");
  private PublicKey secp256r1Program = fromBase58Encoded("Secp256r1SigVerify1111111111111111111111111");
  private PublicKey zKTokenProofProgram = fromBase58Encoded("ZkTokenProof1111111111111111111111111111111");
  private PublicKey zkElGamalProofProgram = fromBase58Encoded("ZkE1Gama1Proof11111111111111111111111111111");
  private PublicKey incinerator = fromBase58Encoded("1nc1nerator11111111111111111111111111111111");
  // Mint
  private PublicKey wrappedSolTokenMint = fromBase58Encoded("So11111111111111111111111111111111111111112");
  // Common
  private PublicKey computeBudgetProgram = fromBase58Encoded("ComputeBudget111111111111111111111111111111");
  private PublicKey tokenProgram = fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private PublicKey associatedTokenAccountProgram = fromBase58Encoded("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
  private PublicKey token2022Program = fromBase58Encoded("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");
  private PublicKey tokenUpgradeProgram = fromBase58Encoded("TkupDoNseygccBCjSsrSpMccjwHfTYwcrjpnDSrFDhC");
  private PublicKey memoProgram = fromBase58Encoded("Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo");
  private PublicKey memoProgramV2 = fromBase58Encoded("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr");
  private PublicKey nameServiceProgram = fromBase58Encoded("namesLPneVptA9Z5rqUDD9tMTWEJwofgaYwp8cawRkX");
  private PublicKey sharedMemoryProgram = fromBase58Encoded("shmem4EWT2sPdVGvTZCzXXRAURL9G5vpPxNwSeKhHUL");
  private PublicKey featureProposalProgram = fromBase58Encoded("Feat1YXHhH6t1juaWF74WLcfv4XoNocjXA6sPWHNgAse");
  // Sysvar
  private PublicKey sysvarOwner = fromBase58Encoded("Sysvar1111111111111111111111111111111111111");
  private PublicKey clockSysVar = fromBase58Encoded("SysvarC1ock11111111111111111111111111111111");
  private PublicKey epochScheduleSysVar = fromBase58Encoded("SysvarEpochSchedu1e111111111111111111111111");
  private PublicKey feesSysVar = fromBase58Encoded("SysvarFees111111111111111111111111111111111");
  private PublicKey instructionsSysVar = fromBase58Encoded("Sysvar1nstructions1111111111111111111111111");
  private PublicKey recentBlockhashesSysVar = fromBase58Encoded("SysvarRecentB1ockHashes11111111111111111111");
  private PublicKey rentSysVar = fromBase58Encoded("SysvarRent111111111111111111111111111111111");
  private PublicKey slotHashesSysVar = fromBase58Encoded("SysvarS1otHashes111111111111111111111111111");
  private PublicKey slotHistorySysVar = fromBase58Encoded("SysvarS1otHistory11111111111111111111111111");
  private PublicKey stakeHistorySysVar = fromBase58Encoded("SysvarStakeHistory1111111111111111111111111");
  private PublicKey epochRewardsSysVar = fromBase58Encoded("SysvarEpochRewards1111111111111111111111111");
  private PublicKey lastRestartSlotSysVar = fromBase58Encoded("SysvarLastRestartS1ot1111111111111111111111");

  private SolanaAccountsBuilder() {
  }

  public static SolanaAccountsBuilder builder() {
    return new SolanaAccountsBuilder();
  }

  public SolanaAccounts create() {
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
        ed25519Program,
        createInvoked(ed25519Program),
        createRead(ed25519Program),
        secp256k1Program,
        createInvoked(secp256k1Program),
        createRead(secp256k1Program),
        secp256r1Program,
        createInvoked(secp256r1Program),
        createRead(secp256r1Program),
        zKTokenProofProgram,
        createInvoked(zKTokenProofProgram),
        createRead(zKTokenProofProgram),
        zkElGamalProofProgram,
        createInvoked(zkElGamalProofProgram),
        createRead(zkElGamalProofProgram),
        incinerator,
        createWrite(incinerator),
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
        memoProgramV2,
        createInvoked(memoProgramV2),
        createRead(memoProgramV2),
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
        sysvarOwner,
        createRead(sysvarOwner),
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

  public SolanaAccountsBuilder systemProgram(final PublicKey systemProgram) {
    this.systemProgram = systemProgram;
    return this;
  }

  public SolanaAccountsBuilder systemProgram(final String systemProgram) {
    return systemProgram(fromBase58Encoded(systemProgram));
  }

  public SolanaAccountsBuilder configProgram(final PublicKey configProgram) {
    this.configProgram = configProgram;
    return this;
  }

  public SolanaAccountsBuilder configProgram(final String configProgram) {
    return configProgram(fromBase58Encoded(configProgram));
  }

  public SolanaAccountsBuilder stakeProgram(final PublicKey stakeProgram) {
    this.stakeProgram = stakeProgram;
    return this;
  }

  public SolanaAccountsBuilder stakeProgram(final String stakeProgram) {
    return stakeProgram(fromBase58Encoded(stakeProgram));
  }

  public SolanaAccountsBuilder stakeConfig(final PublicKey stakeConfig) {
    this.stakeConfig = stakeConfig;
    return this;
  }

  public SolanaAccountsBuilder stakeConfig(final String stakeConfig) {
    return stakeConfig(fromBase58Encoded(stakeConfig));
  }

  public SolanaAccountsBuilder voteProgram(final PublicKey voteProgram) {
    this.voteProgram = voteProgram;
    return this;
  }

  public SolanaAccountsBuilder voteProgram(final String voteProgram) {
    return voteProgram(fromBase58Encoded(voteProgram));
  }

  public SolanaAccountsBuilder addressLookupTableProgram(final PublicKey addressLookupTableProgram) {
    this.addressLookupTableProgram = addressLookupTableProgram;
    return this;
  }

  public SolanaAccountsBuilder addressLookupTableProgram(final String addressLookupTableProgram) {
    return addressLookupTableProgram(fromBase58Encoded(addressLookupTableProgram));
  }

  public SolanaAccountsBuilder bPFLoaderProgram(final PublicKey bPFLoaderProgram) {
    this.bPFLoaderProgram = bPFLoaderProgram;
    return this;
  }

  public SolanaAccountsBuilder bPFLoaderProgram(final String bPFLoaderProgram) {
    return bPFLoaderProgram(fromBase58Encoded(bPFLoaderProgram));
  }

  public SolanaAccountsBuilder ed25519Program(final PublicKey ed25519Program) {
    this.ed25519Program = ed25519Program;
    return this;
  }

  public SolanaAccountsBuilder ed25519Program(final String ed25519Program) {
    return ed25519Program(fromBase58Encoded(ed25519Program));
  }

  public SolanaAccountsBuilder secp256k1Program(final PublicKey secp256k1Program) {
    this.secp256k1Program = secp256k1Program;
    return this;
  }

  public SolanaAccountsBuilder secp256k1Program(final String secp256k1Program) {
    return secp256k1Program(fromBase58Encoded(secp256k1Program));
  }

  public SolanaAccountsBuilder secp256r1Program(final PublicKey secp256r1Program) {
    this.secp256r1Program = secp256r1Program;
    return this;
  }

  public SolanaAccountsBuilder secp256r1Program(final String secp256r1Program) {
    return secp256r1Program(fromBase58Encoded(secp256r1Program));
  }

  public SolanaAccountsBuilder zKTokenProofProgram(final PublicKey zKTokenProofProgram) {
    this.zKTokenProofProgram = zKTokenProofProgram;
    return this;
  }

  public SolanaAccountsBuilder zKTokenProofProgram(final String zKTokenProofProgram) {
    return zKTokenProofProgram(fromBase58Encoded(zKTokenProofProgram));
  }

  public SolanaAccountsBuilder zkElGamalProofProgram(final PublicKey zkElGamalProofProgram) {
    this.zkElGamalProofProgram = zkElGamalProofProgram;
    return this;
  }

  public SolanaAccountsBuilder zkElGamalProofProgram(final String zkElGamalProofProgram) {
    return zkElGamalProofProgram(fromBase58Encoded(zkElGamalProofProgram));
  }

  public SolanaAccountsBuilder incinerator(final PublicKey incinerator) {
    this.incinerator = incinerator;
    return this;
  }

  public SolanaAccountsBuilder incinerator(final String incinerator) {
    return incinerator(fromBase58Encoded(incinerator));
  }

  public SolanaAccountsBuilder wrappedSolTokenMint(final PublicKey wrappedSolTokenMint) {
    this.wrappedSolTokenMint = wrappedSolTokenMint;
    return this;
  }

  public SolanaAccountsBuilder wrappedSolTokenMint(final String wrappedSolTokenMint) {
    return wrappedSolTokenMint(fromBase58Encoded(wrappedSolTokenMint));
  }

  public SolanaAccountsBuilder computeBudgetProgram(final PublicKey computeBudgetProgram) {
    this.computeBudgetProgram = computeBudgetProgram;
    return this;
  }

  public SolanaAccountsBuilder computeBudgetProgram(final String computeBudgetProgram) {
    return computeBudgetProgram(fromBase58Encoded(computeBudgetProgram));
  }

  public SolanaAccountsBuilder tokenProgram(final PublicKey tokenProgram) {
    this.tokenProgram = tokenProgram;
    return this;
  }

  public SolanaAccountsBuilder tokenProgram(final String tokenProgram) {
    return tokenProgram(fromBase58Encoded(tokenProgram));
  }

  public SolanaAccountsBuilder associatedTokenAccountProgram(final PublicKey associatedTokenAccountProgram) {
    this.associatedTokenAccountProgram = associatedTokenAccountProgram;
    return this;
  }

  public SolanaAccountsBuilder associatedTokenAccountProgram(final String associatedTokenAccountProgram) {
    return associatedTokenAccountProgram(fromBase58Encoded(associatedTokenAccountProgram));
  }

  public SolanaAccountsBuilder token2022Program(final PublicKey token2022Program) {
    this.token2022Program = token2022Program;
    return this;
  }

  public SolanaAccountsBuilder token2022Program(final String token2022Program) {
    return token2022Program(fromBase58Encoded(token2022Program));
  }

  public SolanaAccountsBuilder tokenUpgradeProgram(final PublicKey tokenUpgradeProgram) {
    this.tokenUpgradeProgram = tokenUpgradeProgram;
    return this;
  }

  public SolanaAccountsBuilder tokenUpgradeProgram(final String tokenUpgradeProgram) {
    return tokenUpgradeProgram(fromBase58Encoded(tokenUpgradeProgram));
  }

  public SolanaAccountsBuilder memoProgram(final PublicKey memoProgram) {
    this.memoProgram = memoProgram;
    return this;
  }

  public SolanaAccountsBuilder memoProgram(final String memoProgram) {
    return memoProgram(fromBase58Encoded(memoProgram));
  }

  public SolanaAccountsBuilder memoProgramV2(final PublicKey memoProgramV2) {
    this.memoProgramV2 = memoProgramV2;
    return this;
  }

  public SolanaAccountsBuilder memoProgramV2(final String memoProgramV2) {
    return memoProgramV2(fromBase58Encoded(memoProgramV2));
  }

  public SolanaAccountsBuilder nameServiceProgram(final PublicKey nameServiceProgram) {
    this.nameServiceProgram = nameServiceProgram;
    return this;
  }

  public SolanaAccountsBuilder nameServiceProgram(final String nameServiceProgram) {
    return nameServiceProgram(fromBase58Encoded(nameServiceProgram));
  }

  public SolanaAccountsBuilder sharedMemoryProgram(final PublicKey sharedMemoryProgram) {
    this.sharedMemoryProgram = sharedMemoryProgram;
    return this;
  }

  public SolanaAccountsBuilder sharedMemoryProgram(final String sharedMemoryProgram) {
    return sharedMemoryProgram(fromBase58Encoded(sharedMemoryProgram));
  }

  public SolanaAccountsBuilder featureProposalProgram(final PublicKey featureProposalProgram) {
    this.featureProposalProgram = featureProposalProgram;
    return this;
  }

  public SolanaAccountsBuilder featureProposalProgram(final String featureProposalProgram) {
    return featureProposalProgram(fromBase58Encoded(featureProposalProgram));
  }

  public SolanaAccountsBuilder sysvarOwner(final PublicKey sysvarOwner) {
    this.sysvarOwner = sysvarOwner;
    return this;
  }

  public SolanaAccountsBuilder sysvarOwner(final String sysvarOwner) {
    return sysvarOwner(fromBase58Encoded(sysvarOwner));
  }

  public SolanaAccountsBuilder clockSysVar(final PublicKey clockSysVar) {
    this.clockSysVar = clockSysVar;
    return this;
  }

  public SolanaAccountsBuilder clockSysVar(final String clockSysVar) {
    return clockSysVar(fromBase58Encoded(clockSysVar));
  }

  public SolanaAccountsBuilder epochScheduleSysVar(final PublicKey epochScheduleSysVar) {
    this.epochScheduleSysVar = epochScheduleSysVar;
    return this;
  }

  public SolanaAccountsBuilder epochScheduleSysVar(final String epochScheduleSysVar) {
    return epochScheduleSysVar(fromBase58Encoded(epochScheduleSysVar));
  }

  public SolanaAccountsBuilder feesSysVar(final PublicKey feesSysVar) {
    this.feesSysVar = feesSysVar;
    return this;
  }

  public SolanaAccountsBuilder feesSysVar(final String feesSysVar) {
    return feesSysVar(fromBase58Encoded(feesSysVar));
  }

  public SolanaAccountsBuilder instructionsSysVar(final PublicKey instructionsSysVar) {
    this.instructionsSysVar = instructionsSysVar;
    return this;
  }

  public SolanaAccountsBuilder instructionsSysVar(final String instructionsSysVar) {
    return instructionsSysVar(fromBase58Encoded(instructionsSysVar));
  }

  public SolanaAccountsBuilder recentBlockhashesSysVar(final PublicKey recentBlockhashesSysVar) {
    this.recentBlockhashesSysVar = recentBlockhashesSysVar;
    return this;
  }

  public SolanaAccountsBuilder recentBlockhashesSysVar(final String recentBlockhashesSysVar) {
    return recentBlockhashesSysVar(fromBase58Encoded(recentBlockhashesSysVar));
  }

  public SolanaAccountsBuilder rentSysVar(final PublicKey rentSysVar) {
    this.rentSysVar = rentSysVar;
    return this;
  }

  public SolanaAccountsBuilder rentSysVar(final String rentSysVar) {
    return rentSysVar(fromBase58Encoded(rentSysVar));
  }

  public SolanaAccountsBuilder slotHashesSysVar(final PublicKey slotHashesSysVar) {
    this.slotHashesSysVar = slotHashesSysVar;
    return this;
  }

  public SolanaAccountsBuilder slotHashesSysVar(final String slotHashesSysVar) {
    return slotHashesSysVar(fromBase58Encoded(slotHashesSysVar));
  }

  public SolanaAccountsBuilder slotHistorySysVar(final PublicKey slotHistorySysVar) {
    this.slotHistorySysVar = slotHistorySysVar;
    return this;
  }

  public SolanaAccountsBuilder slotHistorySysVar(final String slotHistorySysVar) {
    return slotHistorySysVar(fromBase58Encoded(slotHistorySysVar));
  }

  public SolanaAccountsBuilder stakeHistorySysVar(final PublicKey stakeHistorySysVar) {
    this.stakeHistorySysVar = stakeHistorySysVar;
    return this;
  }

  public SolanaAccountsBuilder stakeHistorySysVar(final String stakeHistorySysVar) {
    return stakeHistorySysVar(fromBase58Encoded(stakeHistorySysVar));
  }

  public SolanaAccountsBuilder epochRewardsSysVar(final PublicKey epochRewardsSysVar) {
    this.epochRewardsSysVar = epochRewardsSysVar;
    return this;
  }

  public SolanaAccountsBuilder epochRewardsSysVar(final String epochRewardsSysVar) {
    return epochRewardsSysVar(fromBase58Encoded(epochRewardsSysVar));
  }

  public SolanaAccountsBuilder lastRestartSlotSysVar(final PublicKey lastRestartSlotSysVar) {
    this.lastRestartSlotSysVar = lastRestartSlotSysVar;
    return this;
  }

  public SolanaAccountsBuilder lastRestartSlotSysVar(final String lastRestartSlotSysVar) {
    return lastRestartSlotSysVar(fromBase58Encoded(lastRestartSlotSysVar));
  }
}
