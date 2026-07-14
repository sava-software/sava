package software.sava.core.token.extensions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.accounts.token.Mint;
import software.sava.core.accounts.token.Token2022;
import software.sava.core.accounts.token.Token2022Account;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.accounts.token.extensions.*;
import software.sava.core.zk.ElGamal;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ExtensionRoundTripTests {

  private static PublicKey key(final int fill) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(bytes, (byte) fill);
    return PublicKey.createPubKey(bytes);
  }

  private static byte[] bytes(final int len, final int fill) {
    final byte[] bytes = new byte[len];
    Arrays.fill(bytes, (byte) fill);
    return bytes;
  }

  private static Map<ExtensionType, TokenExtension> writeReadMint(final Map<ExtensionType, TokenExtension> extensions) {
    final var address = key(1);
    final var mint = new Mint(address, key(2), 1_000_000_000L, 9, true, key(3));
    final var token2022 = new Token2022(mint, AccountType.Mint, extensions);
    final byte[] data = new byte[token2022.l()];
    assertEquals(data.length, token2022.write(data, 0));
    final var parsed = Token2022.read(address, data);
    assertEquals(mint, parsed.mint());
    assertEquals(AccountType.Mint, parsed.accountType());
    return parsed.extensions();
  }

  @Test
  void mintExtensionsRoundTrip() {
    final var extensions = new EnumMap<ExtensionType, TokenExtension>(ExtensionType.class);
    final var transferFeeConfig = new TransferFeeConfig(
        key(10), key(11), 42L,
        new TransferFee(604, 5_000L, 25),
        new TransferFee(605, 10_000L, 50)
    );
    extensions.put(ExtensionType.TransferFeeConfig, transferFeeConfig);
    final var transferFeeAmount = new TransferFeeAmount(21L);
    extensions.put(ExtensionType.TransferFeeAmount, transferFeeAmount);
    final var mintCloseAuthority = new MintCloseAuthority(key(12));
    extensions.put(ExtensionType.MintCloseAuthority, mintCloseAuthority);
    final var confidentialTransferMint = new ConfidentialTransferMint(key(13), true, key(14));
    extensions.put(ExtensionType.ConfidentialTransferMint, confidentialTransferMint);
    final var defaultAccountState = new DefaultAccountState(AccountState.Frozen.ordinal());
    extensions.put(ExtensionType.DefaultAccountState, defaultAccountState);
    extensions.put(ExtensionType.NonTransferable, NonTransferable.INSTANCE);
    final var interestBearingConfig = new InterestBearingConfig(key(15), 1_700_000_000L, -50, 1_750_000_000L, 500);
    extensions.put(ExtensionType.InterestBearingConfig, interestBearingConfig);
    final var permanentDelegate = new PermanentDelegate(key(16));
    extensions.put(ExtensionType.PermanentDelegate, permanentDelegate);
    final var transferHook = new TransferHook(key(17), key(18));
    extensions.put(ExtensionType.TransferHook, transferHook);
    final var metadataPointer = new MetadataPointer(key(19), key(20));
    extensions.put(ExtensionType.MetadataPointer, metadataPointer);
    final var groupPointer = new GroupPointer(key(21), key(22));
    extensions.put(ExtensionType.GroupPointer, groupPointer);
    final var tokenGroup = new TokenGroup(key(23), key(24), 7L, 512L);
    extensions.put(ExtensionType.TokenGroup, tokenGroup);
    final var groupMemberPointer = new GroupMemberPointer(key(25), key(26));
    extensions.put(ExtensionType.GroupMemberPointer, groupMemberPointer);
    final var tokenGroupMember = new TokenGroupMember(key(27), key(28), 7L);
    extensions.put(ExtensionType.TokenGroupMember, tokenGroupMember);
    final var scaledUiAmountConfig = new ScaledUiAmountConfig(key(29), 1.5, 1_750_000_000L, 2.25);
    extensions.put(ExtensionType.ScaledUiAmount, scaledUiAmountConfig);
    final var pausableConfig = new PausableConfig(key(30), true);
    extensions.put(ExtensionType.Pausable, pausableConfig);
    final var permissionedBurnConfig = new PermissionedBurnConfig(key(31));
    extensions.put(ExtensionType.PermissionedBurn, permissionedBurnConfig);

    final var parsed = writeReadMint(extensions);
    assertEquals(extensions.size(), parsed.size());
    assertEquals(transferFeeConfig, parsed.get(ExtensionType.TransferFeeConfig));
    assertEquals(transferFeeAmount, parsed.get(ExtensionType.TransferFeeAmount));
    assertEquals(mintCloseAuthority, parsed.get(ExtensionType.MintCloseAuthority));
    assertEquals(confidentialTransferMint, parsed.get(ExtensionType.ConfidentialTransferMint));
    assertEquals(defaultAccountState, parsed.get(ExtensionType.DefaultAccountState));
    assertSame(NonTransferable.INSTANCE, parsed.get(ExtensionType.NonTransferable));
    assertEquals(interestBearingConfig, parsed.get(ExtensionType.InterestBearingConfig));
    assertEquals(permanentDelegate, parsed.get(ExtensionType.PermanentDelegate));
    assertEquals(transferHook, parsed.get(ExtensionType.TransferHook));
    assertEquals(metadataPointer, parsed.get(ExtensionType.MetadataPointer));
    assertEquals(groupPointer, parsed.get(ExtensionType.GroupPointer));
    assertEquals(tokenGroup, parsed.get(ExtensionType.TokenGroup));
    assertEquals(groupMemberPointer, parsed.get(ExtensionType.GroupMemberPointer));
    assertEquals(tokenGroupMember, parsed.get(ExtensionType.TokenGroupMember));
    assertEquals(scaledUiAmountConfig, parsed.get(ExtensionType.ScaledUiAmount));
    assertEquals(pausableConfig, parsed.get(ExtensionType.Pausable));
    assertEquals(permissionedBurnConfig, parsed.get(ExtensionType.PermissionedBurn));

    final var interestBearing = (InterestBearingConfig) parsed.get(ExtensionType.InterestBearingConfig);
    assertEquals(interestBearing.unixTimestamp(), interestBearing.initializationTimestamp());
  }

  @Test
  void confidentialMintExtensionsRoundTrip() {
    final var extensions = new EnumMap<ExtensionType, TokenExtension>(ExtensionType.class);
    final var confidentialTransferFeeConfig = new ConfidentialTransferFeeConfig(
        key(40), key(41), true, bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 42)
    );
    extensions.put(ExtensionType.ConfidentialTransferFeeConfig, confidentialTransferFeeConfig);
    final var confidentialTransferFeeAmount = new ConfidentialTransferFeeAmount(bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 43));
    extensions.put(ExtensionType.ConfidentialTransferFeeAmount, confidentialTransferFeeAmount);
    final var confidentialMintBurn = new ConfidentialMintBurn(
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 44),
        bytes(ElGamal.AE_CIPHERTEXT_LEN, 45),
        key(46),
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 47)
    );
    extensions.put(ExtensionType.ConfidentialMintBurn, confidentialMintBurn);

    final var parsed = writeReadMint(extensions);
    assertEquals(extensions.size(), parsed.size());

    final var parsedFeeConfig = (ConfidentialTransferFeeConfig) parsed.get(ExtensionType.ConfidentialTransferFeeConfig);
    assertEquals(confidentialTransferFeeConfig.authority(), parsedFeeConfig.authority());
    assertEquals(confidentialTransferFeeConfig.withdrawWithheldAuthorityElgamalPubkey(), parsedFeeConfig.withdrawWithheldAuthorityElgamalPubkey());
    assertTrue(parsedFeeConfig.harvestToMintEnabled());
    assertArrayEquals(confidentialTransferFeeConfig.withheldAmount(), parsedFeeConfig.withheldAmount());

    final var parsedFeeAmount = (ConfidentialTransferFeeAmount) parsed.get(ExtensionType.ConfidentialTransferFeeAmount);
    assertArrayEquals(confidentialTransferFeeAmount.withheldAmount(), parsedFeeAmount.withheldAmount());

    final var parsedMintBurn = (ConfidentialMintBurn) parsed.get(ExtensionType.ConfidentialMintBurn);
    assertArrayEquals(confidentialMintBurn.confidentialSupply(), parsedMintBurn.confidentialSupply());
    assertArrayEquals(confidentialMintBurn.decryptableSupply(), parsedMintBurn.decryptableSupply());
    assertEquals(confidentialMintBurn.supplyElGamalPubKey(), parsedMintBurn.supplyElGamalPubKey());
    assertArrayEquals(confidentialMintBurn.pendingBurn(), parsedMintBurn.pendingBurn());
  }

  @Test
  void accountExtensionsRoundTrip() {
    final var extensions = new EnumMap<ExtensionType, TokenExtension>(ExtensionType.class);
    extensions.put(ExtensionType.ImmutableOwner, ImmutableOwner.INSTANCE);
    final var memoTransfer = new MemoTransfer(true);
    extensions.put(ExtensionType.MemoTransfer, memoTransfer);
    final var cpiGuard = new CpiGuard(true);
    extensions.put(ExtensionType.CpiGuard, cpiGuard);
    extensions.put(ExtensionType.NonTransferableAccount, NonTransferableAccount.INSTANCE);
    final var transferHookAccount = new TransferHookAccount(true);
    extensions.put(ExtensionType.TransferHookAccount, transferHookAccount);
    extensions.put(ExtensionType.PausableAccount, PausableAccount.INSTANCE);
    final var confidentialTransferAccount = new ConfidentialTransferAccount(
        true,
        key(50),
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 51),
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 52),
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 53),
        bytes(ElGamal.AE_CIPHERTEXT_LEN, 54),
        true,
        false,
        1L,
        65_536L,
        2L,
        3L
    );
    extensions.put(ExtensionType.ConfidentialTransferAccount, confidentialTransferAccount);

    final var address = key(4);
    final var tokenAccount = new TokenAccount(
        address, key(5), key(6), 42L,
        1, key(7),
        AccountState.Initialized,
        0, 0L, 21L,
        1, key(8)
    );
    final var token2022Account = new Token2022Account(tokenAccount, AccountType.Account, extensions);
    final byte[] data = new byte[token2022Account.l()];
    assertEquals(data.length, token2022Account.write(data, 0));

    final var parsed = Token2022Account.read(address, data);
    assertEquals(tokenAccount, parsed.tokenAccount());
    assertEquals(AccountType.Account, parsed.type());
    assertEquals(extensions.size(), parsed.extensions().size());
    assertSame(ImmutableOwner.INSTANCE, parsed.extensions().get(ExtensionType.ImmutableOwner));
    assertEquals(memoTransfer, parsed.extensions().get(ExtensionType.MemoTransfer));
    assertTrue(parsed.memoTransfer().requireIncomingTransferMemos());
    assertEquals(cpiGuard, parsed.extensions().get(ExtensionType.CpiGuard));
    assertSame(NonTransferableAccount.INSTANCE, parsed.extensions().get(ExtensionType.NonTransferableAccount));
    assertEquals(transferHookAccount, parsed.extensions().get(ExtensionType.TransferHookAccount));
    assertSame(PausableAccount.INSTANCE, parsed.extensions().get(ExtensionType.PausableAccount));

    final var parsedConfidential = (ConfidentialTransferAccount) parsed.extensions().get(ExtensionType.ConfidentialTransferAccount);
    assertTrue(parsedConfidential.approved());
    assertEquals(confidentialTransferAccount.elgamalPubkey(), parsedConfidential.elgamalPubkey());
    assertArrayEquals(confidentialTransferAccount.pendingBalanceLo(), parsedConfidential.pendingBalanceLo());
    assertArrayEquals(confidentialTransferAccount.pendingBalanceHi(), parsedConfidential.pendingBalanceHi());
    assertArrayEquals(confidentialTransferAccount.availableBalance(), parsedConfidential.availableBalance());
    assertArrayEquals(confidentialTransferAccount.decryptableAvailableBalance(), parsedConfidential.decryptableAvailableBalance());
    assertTrue(parsedConfidential.allowConfidentialCredits());
    assertFalse(parsedConfidential.allowNonConfidentialCredits());
    assertEquals(1L, parsedConfidential.pendingBalanceCreditCounter());
    assertEquals(65_536L, parsedConfidential.maximumPendingBalanceCreditCounter());
    assertEquals(2L, parsedConfidential.expectedPendingBalanceCreditCounter());
    assertEquals(3L, parsedConfidential.actualPendingBalanceCreditCounter());
  }

  @Test
  void tokenMetadataUtf8RoundTrip() {
    final var tokenMetadata = new TokenMetadata(
        key(60), key(61),
        "Café ☕",
        "Ⓒ₳ⓕé",
        "https://example.com/café.json",
        Map.of("désc", "ünïcode ✓", "plain", "ascii")
    );
    final var extensions = new EnumMap<ExtensionType, TokenExtension>(ExtensionType.class);
    extensions.put(ExtensionType.TokenMetadata, tokenMetadata);

    final var parsed = writeReadMint(extensions);
    assertEquals(tokenMetadata, parsed.get(ExtensionType.TokenMetadata));
  }

  @Test
  void trailingPaddingRetainsParsedExtensions() {
    final var extensions = new EnumMap<ExtensionType, TokenExtension>(ExtensionType.class);
    final var pausableConfig = new PausableConfig(key(70), false);
    extensions.put(ExtensionType.Pausable, pausableConfig);

    final var address = key(1);
    final var mint = new Mint(address, key(2), 0L, 6, true, null);
    final var token2022 = new Token2022(mint, AccountType.Mint, extensions);
    // Allocate extra zeroed space to mimic re-allocated but uninitialized extension slots.
    final byte[] data = new byte[token2022.l() + 64];
    token2022.write(data, 0);

    final var parsed = Token2022.read(address, data);
    assertEquals(1, parsed.extensions().size());
    assertEquals(pausableConfig, parsed.extensions().get(ExtensionType.Pausable));
  }

  @Test
  void uninitializedOnlyPadding() {
    final var address = key(1);
    final var mint = new Mint(address, key(2), 0L, 6, true, null);
    final var token2022 = new Token2022(mint, AccountType.Mint, Map.of());
    // Only zeroed padding after the account type byte.
    final byte[] data = new byte[token2022.l() + 64];
    token2022.write(data, 0);

    final var parsed = Token2022.read(address, data);
    assertEquals(Map.of(ExtensionType.Uninitialized, Uninitialized.INSTANCE), parsed.extensions());
  }
}
