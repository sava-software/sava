package software.sava.core.accounts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SolanaAccountsTests {

  private static void assertAddress(final String expected, final PublicKey key) {
    assertEquals(expected, key.toBase58());
  }

  @Test
  void mainNetAddressConstants() {
    final var accounts = SolanaAccounts.MAIN_NET;

    assertAddress("11111111111111111111111111111111", accounts.systemProgram());
    assertAddress("Config1111111111111111111111111111111111111", accounts.configProgram());
    assertAddress("Stake11111111111111111111111111111111111111", accounts.stakeProgram());
    assertAddress("StakeConfig11111111111111111111111111111111", accounts.stakeConfig());
    assertAddress("Vote111111111111111111111111111111111111111", accounts.voteProgram());
    assertAddress("AddressLookupTab1e1111111111111111111111111", accounts.addressLookupTableProgram());
    assertAddress("BPFLoaderUpgradeab1e11111111111111111111111", accounts.bPFLoaderProgram());
    assertAddress("Ed25519SigVerify111111111111111111111111111", accounts.ed25519Program());
    assertAddress("KeccakSecp256k11111111111111111111111111111", accounts.secp256k1Program());
    assertAddress("ZkTokenProof1111111111111111111111111111111", accounts.zKTokenProofProgram());
    assertAddress("ZkE1Gama1Proof11111111111111111111111111111", accounts.zkElGamalProofProgram());
    assertAddress("So11111111111111111111111111111111111111112", accounts.wrappedSolTokenMint());
    assertAddress("ComputeBudget111111111111111111111111111111", accounts.computeBudgetProgram());
    assertAddress("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", accounts.tokenProgram());
    assertAddress("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL", accounts.associatedTokenAccountProgram());
    assertAddress("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb", accounts.token2022Program());
    assertAddress("TkupDoNseygccBCjSsrSpMccjwHfTYwcrjpnDSrFDhC", accounts.tokenUpgradeProgram());
    assertAddress("Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo", accounts.memoProgram());
    assertAddress("namesLPneVptA9Z5rqUDD9tMTWEJwofgaYwp8cawRkX", accounts.nameServiceProgram());
    assertAddress("shmem4EWT2sPdVGvTZCzXXRAURL9G5vpPxNwSeKhHUL", accounts.sharedMemoryProgram());
    assertAddress("Feat1YXHhH6t1juaWF74WLcfv4XoNocjXA6sPWHNgAse", accounts.featureProposalProgram());

    assertAddress("SysvarC1ock11111111111111111111111111111111", accounts.clockSysVar());
    assertAddress("SysvarEpochSchedu1e111111111111111111111111", accounts.epochScheduleSysVar());
    assertAddress("SysvarFees111111111111111111111111111111111", accounts.feesSysVar());
    assertAddress("Sysvar1nstructions1111111111111111111111111", accounts.instructionsSysVar());
    assertAddress("SysvarRecentB1ockHashes11111111111111111111", accounts.recentBlockhashesSysVar());
    assertAddress("SysvarRent111111111111111111111111111111111", accounts.rentSysVar());
    assertAddress("SysvarS1otHashes111111111111111111111111111", accounts.slotHashesSysVar());
    assertAddress("SysvarS1otHistory11111111111111111111111111", accounts.slotHistorySysVar());
    assertAddress("SysvarStakeHistory1111111111111111111111111", accounts.stakeHistorySysVar());
    assertAddress("SysvarEpochRewards1111111111111111111111111", accounts.epochRewardsSysVar());
    assertAddress("SysvarLastRestartS1ot1111111111111111111111", accounts.lastRestartSlotSysVar());
  }
}
