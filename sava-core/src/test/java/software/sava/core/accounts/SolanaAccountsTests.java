package software.sava.core.accounts;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

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

  /// One address the builder can override: its name as the [SolanaAccountsRecord] component,
  /// its base58 and [PublicKey] setters, and the accessor that reads it back.
  private record Setter(String name,
                        BiFunction<SolanaAccountsBuilder, String, SolanaAccountsBuilder> byBase58,
                        BiFunction<SolanaAccountsBuilder, PublicKey, SolanaAccountsBuilder> byKey,
                        Function<SolanaAccounts, PublicKey> accessor) {
  }

  private static final List<Setter> SETTERS = List.of(
      new Setter("systemProgram", SolanaAccountsBuilder::systemProgram, SolanaAccountsBuilder::systemProgram, SolanaAccounts::systemProgram),
      new Setter("configProgram", SolanaAccountsBuilder::configProgram, SolanaAccountsBuilder::configProgram, SolanaAccounts::configProgram),
      new Setter("stakeProgram", SolanaAccountsBuilder::stakeProgram, SolanaAccountsBuilder::stakeProgram, SolanaAccounts::stakeProgram),
      new Setter("stakeConfig", SolanaAccountsBuilder::stakeConfig, SolanaAccountsBuilder::stakeConfig, SolanaAccounts::stakeConfig),
      new Setter("voteProgram", SolanaAccountsBuilder::voteProgram, SolanaAccountsBuilder::voteProgram, SolanaAccounts::voteProgram),
      new Setter("addressLookupTableProgram", SolanaAccountsBuilder::addressLookupTableProgram, SolanaAccountsBuilder::addressLookupTableProgram, SolanaAccounts::addressLookupTableProgram),
      new Setter("bPFLoaderProgram", SolanaAccountsBuilder::bPFLoaderProgram, SolanaAccountsBuilder::bPFLoaderProgram, SolanaAccounts::bPFLoaderProgram),
      new Setter("ed25519Program", SolanaAccountsBuilder::ed25519Program, SolanaAccountsBuilder::ed25519Program, SolanaAccounts::ed25519Program),
      new Setter("secp256k1Program", SolanaAccountsBuilder::secp256k1Program, SolanaAccountsBuilder::secp256k1Program, SolanaAccounts::secp256k1Program),
      new Setter("secp256r1Program", SolanaAccountsBuilder::secp256r1Program, SolanaAccountsBuilder::secp256r1Program, SolanaAccounts::secp256r1Program),
      new Setter("zkElGamalProofProgram", SolanaAccountsBuilder::zkElGamalProofProgram, SolanaAccountsBuilder::zkElGamalProofProgram, SolanaAccounts::zkElGamalProofProgram),
      new Setter("wrappedSolTokenMint", SolanaAccountsBuilder::wrappedSolTokenMint, SolanaAccountsBuilder::wrappedSolTokenMint, SolanaAccounts::wrappedSolTokenMint),
      new Setter("computeBudgetProgram", SolanaAccountsBuilder::computeBudgetProgram, SolanaAccountsBuilder::computeBudgetProgram, SolanaAccounts::computeBudgetProgram),
      new Setter("tokenProgram", SolanaAccountsBuilder::tokenProgram, SolanaAccountsBuilder::tokenProgram, SolanaAccounts::tokenProgram),
      new Setter("associatedTokenAccountProgram", SolanaAccountsBuilder::associatedTokenAccountProgram, SolanaAccountsBuilder::associatedTokenAccountProgram, SolanaAccounts::associatedTokenAccountProgram),
      new Setter("token2022Program", SolanaAccountsBuilder::token2022Program, SolanaAccountsBuilder::token2022Program, SolanaAccounts::token2022Program),
      new Setter("tokenUpgradeProgram", SolanaAccountsBuilder::tokenUpgradeProgram, SolanaAccountsBuilder::tokenUpgradeProgram, SolanaAccounts::tokenUpgradeProgram),
      new Setter("memoProgram", SolanaAccountsBuilder::memoProgram, SolanaAccountsBuilder::memoProgram, SolanaAccounts::memoProgram),
      new Setter("memoProgramV2", SolanaAccountsBuilder::memoProgramV2, SolanaAccountsBuilder::memoProgramV2, SolanaAccounts::memoProgramV2),
      new Setter("nameServiceProgram", SolanaAccountsBuilder::nameServiceProgram, SolanaAccountsBuilder::nameServiceProgram, SolanaAccounts::nameServiceProgram),
      new Setter("sharedMemoryProgram", SolanaAccountsBuilder::sharedMemoryProgram, SolanaAccountsBuilder::sharedMemoryProgram, SolanaAccounts::sharedMemoryProgram),
      new Setter("featureProposalProgram", SolanaAccountsBuilder::featureProposalProgram, SolanaAccountsBuilder::featureProposalProgram, SolanaAccounts::featureProposalProgram),
      new Setter("sysvarOwner", SolanaAccountsBuilder::sysvarOwner, SolanaAccountsBuilder::sysvarOwner, SolanaAccounts::sysvarOwner),
      new Setter("clockSysVar", SolanaAccountsBuilder::clockSysVar, SolanaAccountsBuilder::clockSysVar, SolanaAccounts::clockSysVar),
      new Setter("epochScheduleSysVar", SolanaAccountsBuilder::epochScheduleSysVar, SolanaAccountsBuilder::epochScheduleSysVar, SolanaAccounts::epochScheduleSysVar),
      new Setter("instructionsSysVar", SolanaAccountsBuilder::instructionsSysVar, SolanaAccountsBuilder::instructionsSysVar, SolanaAccounts::instructionsSysVar),
      new Setter("recentBlockhashesSysVar", SolanaAccountsBuilder::recentBlockhashesSysVar, SolanaAccountsBuilder::recentBlockhashesSysVar, SolanaAccounts::recentBlockhashesSysVar),
      new Setter("rentSysVar", SolanaAccountsBuilder::rentSysVar, SolanaAccountsBuilder::rentSysVar, SolanaAccounts::rentSysVar),
      new Setter("slotHashesSysVar", SolanaAccountsBuilder::slotHashesSysVar, SolanaAccountsBuilder::slotHashesSysVar, SolanaAccounts::slotHashesSysVar),
      new Setter("slotHistorySysVar", SolanaAccountsBuilder::slotHistorySysVar, SolanaAccountsBuilder::slotHistorySysVar, SolanaAccounts::slotHistorySysVar),
      new Setter("stakeHistorySysVar", SolanaAccountsBuilder::stakeHistorySysVar, SolanaAccountsBuilder::stakeHistorySysVar, SolanaAccounts::stakeHistorySysVar),
      new Setter("epochRewardsSysVar", SolanaAccountsBuilder::epochRewardsSysVar, SolanaAccountsBuilder::epochRewardsSysVar, SolanaAccounts::epochRewardsSysVar),
      new Setter("lastRestartSlotSysVar", SolanaAccountsBuilder::lastRestartSlotSysVar, SolanaAccountsBuilder::lastRestartSlotSysVar, SolanaAccounts::lastRestartSlotSysVar)
  );

  /// A key per setter, distinct from every other override and from every main-net default:
  /// each is one repeated byte, which no main-net address is.
  private static PublicKey overrideKey(final int index) {
    final byte[] key = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(key, (byte) (0xA0 + index));
    return PublicKey.createPubKey(key);
  }

  /// The setter table must name every address [SolanaAccountsRecord] carries, so the
  /// override tests below cannot silently skip one that is added later.
  @Test
  void setterTableCoversEveryAddressComponent() {
    final var addressComponents = Arrays.stream(SolanaAccountsRecord.class.getRecordComponents())
        .filter(component -> component.getType() == PublicKey.class)
        .map(java.lang.reflect.RecordComponent::getName)
        .collect(Collectors.toSet());
    final var setterNames = SETTERS.stream().map(Setter::name).collect(Collectors.toSet());
    assertEquals(addressComponents, setterNames);
    assertEquals(SETTERS.size(), setterNames.size(), "the setter table names an address twice");
  }

  /// Every [PublicKey] setter returns the builder it was called on, and every address it sets
  /// reaches the created accounts, replacing the main-net default.
  @Test
  void publicKeySettersOverrideEveryAddress() {
    final var builder = SolanaAccountsBuilder.builder();
    for (int i = 0; i < SETTERS.size(); i++) {
      final var setter = SETTERS.get(i);
      assertSame(builder, setter.byKey().apply(builder, overrideKey(i)), setter.name());
    }
    assertEveryAddressOverridden(builder.create());
  }

  /// Every base58 setter returns the builder it was called on, and the key it decodes reaches
  /// the created accounts, replacing the main-net default.
  @Test
  void base58SettersOverrideEveryAddress() {
    final var builder = SolanaAccountsBuilder.builder();
    for (int i = 0; i < SETTERS.size(); i++) {
      final var setter = SETTERS.get(i);
      final var base58 = Base58.encode(overrideKey(i).toByteArray());
      assertSame(builder, setter.byBase58().apply(builder, base58), setter.name());
    }
    assertEveryAddressOverridden(builder.create());
  }

  /// Checks each address accessor against its override and against the main-net default, then
  /// checks that every derived [AccountMeta] component (`invokedX`, `readX`) carries the
  /// overridden key of its address `x`, so `create()` cannot pair a meta with the wrong address.
  private static void assertEveryAddressOverridden(final SolanaAccounts accounts) {
    final var keysByName = new HashMap<String, PublicKey>();
    for (int i = 0; i < SETTERS.size(); i++) {
      final var setter = SETTERS.get(i);
      final var expected = overrideKey(i);
      assertNotEquals(setter.accessor().apply(SolanaAccounts.MAIN_NET), expected, setter.name());
      assertEquals(expected, setter.accessor().apply(accounts), setter.name());
      keysByName.put(setter.name(), expected);
    }
    for (final var component : SolanaAccountsRecord.class.getRecordComponents()) {
      if (component.getType() != AccountMeta.class) {
        continue;
      }
      final var name = component.getName();
      final String stem;
      if (name.startsWith("invoked")) {
        stem = name.substring("invoked".length());
      } else {
        assertTrue(name.startsWith("read"), name);
        stem = name.substring("read".length());
      }
      final var addressName = Character.toLowerCase(stem.charAt(0)) + stem.substring(1);
      final var expected = keysByName.get(addressName);
      assertNotNull(expected, () -> "no address named " + addressName + " for " + name);
      final AccountMeta meta;
      try {
        meta = (AccountMeta) component.getAccessor().invoke(accounts);
      } catch (final IllegalAccessException | InvocationTargetException e) {
        throw new AssertionError(name, e);
      }
      assertEquals(expected, meta.publicKey(), name);
    }
  }

  private static void assertAddress(final String expected, final String actual) {
    assertEquals(expected, actual);
  }
}
