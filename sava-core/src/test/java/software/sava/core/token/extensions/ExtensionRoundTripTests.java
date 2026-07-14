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
import java.util.Map;
import java.util.Set;

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

  private static byte[] writeExtensions(final TokenExtension... extensions) {
    int l = 0;
    for (final var extension : extensions) {
      l += Integer.BYTES + extension.l();
    }
    final byte[] data = new byte[l];
    int i = 0;
    for (final var extension : extensions) {
      i += TokenExtension.write(extension, data, i);
    }
    assertEquals(data.length, i);
    return data;
  }

  private static Set<TokenExtension> writeRead(final TokenExtension... extensions) {
    final var parsed = Token2022.parseExtensions(writeExtensions(extensions), 0);
    assertEquals(Set.of(extensions), parsed);
    return parsed;
  }

  @Test
  void mintExtensionsRoundTrip() {
    final var interestBearingConfig = new InterestBearingConfig(key(15), 1_700_000_000L, -50, 1_750_000_000L, 500);
    assertEquals(1_700_000_000L, interestBearingConfig.initializationTimestamp());

    writeRead(
        new TransferFeeConfig(
            key(10), key(11), 42L,
            new TransferFee(604, 5_000L, 25),
            new TransferFee(605, 10_000L, 50)
        ),
        new TransferFeeAmount(21L),
        new MintCloseAuthority(key(12)),
        new ConfidentialTransferMint(key(13), true, key(14)),
        new DefaultAccountState(AccountState.Frozen.ordinal()),
        NonTransferable.INSTANCE,
        interestBearingConfig,
        new PermanentDelegate(key(16)),
        new TransferHook(key(17), key(18)),
        new MetadataPointer(key(19), key(20)),
        new GroupPointer(key(21), key(22)),
        new TokenGroup(key(23), key(24), 7L, 512L),
        new GroupMemberPointer(key(25), key(26)),
        new TokenGroupMember(key(27), key(28), 7L),
        new ScaledUiAmountConfig(key(29), 1.5, 1_750_000_000L, 2.25),
        new PausableConfig(key(30), true),
        new PermissionedBurnConfig(key(31))
    );
  }

  @Test
  void confidentialMintExtensionsRoundTrip() {
    writeRead(
        new ConfidentialTransferFeeConfig(key(40), key(41), true, bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 42)),
        new ConfidentialTransferFeeAmount(bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 43)),
        new ConfidentialMintBurn(
            bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 44),
            bytes(ElGamal.AE_CIPHERTEXT_LEN, 45),
            key(46),
            bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 47)
        )
    );
  }

  @Test
  void accountExtensionsRoundTrip() {
    final var memoTransfer = new MemoTransfer(true);
    assertTrue(memoTransfer.requireIncomingTransferMemos());
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
    // In ordinal order so that the EnumMap based write below is byte identical.
    final var extensions = writeRead(
        confidentialTransferAccount,
        ImmutableOwner.INSTANCE,
        memoTransfer,
        new CpiGuard(true),
        NonTransferableAccount.INSTANCE,
        new TransferHookAccount(true),
        PausableAccount.INSTANCE
    );

    final var address = key(4);
    final var tokenAccount = new TokenAccount(
        address, key(5), key(6), 42L,
        1, key(7),
        AccountState.Initialized,
        0, 0L, 21L,
        1, key(8)
    );
    final byte[] extensionData = writeExtensions(extensions.toArray(TokenExtension[]::new));
    final byte[] data = new byte[TokenAccount.BYTES + 1 + extensionData.length];
    tokenAccount.write(data, 0);
    data[TokenAccount.BYTES] = (byte) AccountType.Account.ordinal();
    System.arraycopy(extensionData, 0, data, TokenAccount.BYTES + 1, extensionData.length);

    final var parsed = Token2022Account.read(address, data);
    assertEquals(tokenAccount, parsed.tokenAccount());
    assertEquals(AccountType.Account, parsed.type());
    assertEquals(extensions, parsed.tokenExtensions());

    final byte[] written = new byte[parsed.l()];
    assertEquals(data.length, parsed.write(written, 0));
    assertArrayEquals(data, written);
  }

  @Test
  void mintAccountRoundTrip() {
    // In ordinal order so that the EnumMap based write below is byte identical.
    final var extensions = writeRead(
        new TransferFeeConfig(
            key(60), key(61), 21L,
            new TransferFee(604, 5_000L, 25),
            new TransferFee(605, 10_000L, 50)
        ),
        new MintCloseAuthority(key(62)),
        new MetadataPointer(key(63), key(64)),
        new TokenMetadata(
            key(65), key(66),
            "Café ☕",
            "Ⓒ₳ⓕé",
            "https://example.com/café.json",
            Map.of("désc", "ünïcode ✓")
        )
    );

    final var address = key(1);
    final var mint = new Mint(address, key(2), 1_000_000_000L, 9, true, key(3));
    final byte[] extensionData = writeExtensions(extensions.toArray(TokenExtension[]::new));
    final int extensionsOffset = Mint.BYTES + 83 + 1;
    final byte[] data = new byte[extensionsOffset + extensionData.length];
    mint.write(data, 0);
    data[extensionsOffset - 1] = (byte) AccountType.Mint.ordinal();
    System.arraycopy(extensionData, 0, data, extensionsOffset, extensionData.length);

    final var parsed = Token2022.read(address, data);
    assertEquals(mint, parsed.mint());
    assertEquals(AccountType.Mint, parsed.accountType());
    assertEquals(extensions, parsed.tokenExtensions());

    final byte[] written = new byte[parsed.l()];
    assertEquals(data.length, parsed.write(written, 0));
    assertArrayEquals(data, written);
  }

  @Test
  void trailingPaddingRetainsParsedExtensions() {
    final var pausableConfig = new PausableConfig(key(70), false);
    final byte[] extensionData = writeExtensions(pausableConfig);
    // Extra zeroed space mimics re-allocated but uninitialized extension slots.
    final byte[] data = Arrays.copyOf(extensionData, extensionData.length + 64);

    assertEquals(Set.of(pausableConfig), Token2022.parseExtensions(data, 0));
  }

  @Test
  void uninitializedOnlyPadding() {
    assertEquals(Set.of(Uninitialized.INSTANCE), Token2022.parseExtensions(new byte[64], 0));
  }

  @Test
  void unknownExtensionParsing() {
    final var pausableConfig = new PausableConfig(key(80), true);
    // Extension types released after this library was last synced.
    final var firstUnknown = new UnknownTokenExtension(1_000, bytes(5, 81));
    final var secondUnknown = new UnknownTokenExtension(1_001, bytes(3, 82));
    assertEquals(1_000, firstUnknown.ordinal());

    final byte[] extensionData = writeExtensions(pausableConfig, firstUnknown, secondUnknown);
    assertEquals(Set.of(pausableConfig, firstUnknown, secondUnknown), Token2022.parseExtensions(extensionData, 0));

    // Unknown extensions are retained through a full account round trip.
    final var address = key(1);
    final var mint = new Mint(address, key(2), 0L, 6, true, null);
    final int extensionsOffset = Mint.BYTES + 83 + 1;
    final byte[] data = new byte[extensionsOffset + extensionData.length];
    mint.write(data, 0);
    data[extensionsOffset - 1] = (byte) AccountType.Mint.ordinal();
    System.arraycopy(extensionData, 0, data, extensionsOffset, extensionData.length);

    final var parsed = Token2022.read(address, data);
    assertEquals(Set.of(pausableConfig, firstUnknown, secondUnknown), parsed.tokenExtensions());

    final byte[] written = new byte[parsed.l()];
    assertEquals(data.length, parsed.write(written, 0));
    assertArrayEquals(data, written);
  }
}
