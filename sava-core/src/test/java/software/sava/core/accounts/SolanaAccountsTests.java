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
    assertAddress("Secp256r1SigVerify1111111111111111111111111", accounts.secp256r1Program());
    assertAddress("ZkE1Gama1Proof11111111111111111111111111111", accounts.zkElGamalProofProgram());
    assertAddress("So11111111111111111111111111111111111111112", accounts.wrappedSolTokenMint());
    assertAddress("ComputeBudget111111111111111111111111111111", accounts.computeBudgetProgram());
    assertAddress("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", accounts.tokenProgram());
    assertAddress("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL", accounts.associatedTokenAccountProgram());
    assertAddress("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb", accounts.token2022Program());
    assertAddress("TkupDoNseygccBCjSsrSpMccjwHfTYwcrjpnDSrFDhC", accounts.tokenUpgradeProgram());
    assertAddress("Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo", accounts.memoProgram());
    assertAddress("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr", accounts.memoProgramV2());
    assertAddress("namesLPneVptA9Z5rqUDD9tMTWEJwofgaYwp8cawRkX", accounts.nameServiceProgram());
    assertAddress("shmem4EWT2sPdVGvTZCzXXRAURL9G5vpPxNwSeKhHUL", accounts.sharedMemoryProgram());
    assertAddress("Feat1YXHhH6t1juaWF74WLcfv4XoNocjXA6sPWHNgAse", accounts.featureProposalProgram());

    assertAddress("Sysvar1111111111111111111111111111111111111", accounts.sysvarOwner());
    assertAddress("SysvarC1ock11111111111111111111111111111111", accounts.clockSysVar());
    assertAddress("SysvarEpochSchedu1e111111111111111111111111", accounts.epochScheduleSysVar());
    assertAddress("Sysvar1nstructions1111111111111111111111111", accounts.instructionsSysVar());
    assertAddress("SysvarRecentB1ockHashes11111111111111111111", accounts.recentBlockhashesSysVar());
    assertAddress("SysvarRent111111111111111111111111111111111", accounts.rentSysVar());
    assertAddress("SysvarS1otHashes111111111111111111111111111", accounts.slotHashesSysVar());
    assertAddress("SysvarS1otHistory11111111111111111111111111", accounts.slotHistorySysVar());
    assertAddress("SysvarStakeHistory1111111111111111111111111", accounts.stakeHistorySysVar());
    assertAddress("SysvarEpochRewards1111111111111111111111111", accounts.epochRewardsSysVar());
    assertAddress("SysvarLastRestartS1ot1111111111111111111111", accounts.lastRestartSlotSysVar());
  }

  @Test
  void builderDefaultsAndOverrides() {
    final var forkedTokenProgram = "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo";
    final var accounts = SolanaAccountsBuilder.builder()
        .tokenProgram(forkedTokenProgram)
        .create();

    assertAddress(forkedTokenProgram, accounts.tokenProgram());
    assertAddress(forkedTokenProgram, accounts.invokedTokenProgram().publicKey().toBase58());
    // Everything else retains its main-net default.
    assertEquals(SolanaAccounts.MAIN_NET.systemProgram(), accounts.systemProgram());
    assertEquals(SolanaAccounts.MAIN_NET.token2022Program(), accounts.token2022Program());
    assertEquals(SolanaAccounts.MAIN_NET.clockSysVar(), accounts.clockSysVar());
  }

  private static void assertAddress(final String expected, final String actual) {
    assertEquals(expected, actual);
  }
}
