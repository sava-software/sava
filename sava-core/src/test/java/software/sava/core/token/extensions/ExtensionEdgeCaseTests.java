package software.sava.core.token.extensions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.accounts.token.Mint;
import software.sava.core.accounts.token.Token2022;
import software.sava.core.accounts.token.Token2022Account;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.accounts.token.extensions.*;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.zk.ElGamal;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

final class ExtensionEdgeCaseTests {

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

  @Test
  void nullOrEmptyDataReadsAsNull() {
    final var readers = List.<Function<byte[], Object>>of(
        data -> TransferFee.read(data, 0),
        data -> TransferFeeConfig.read(data, 0),
        data -> TransferFeeAmount.read(data, 0),
        data -> MintCloseAuthority.read(data, 0),
        data -> ConfidentialTransferMint.read(data, 0),
        data -> ConfidentialTransferAccount.read(data, 0),
        data -> DefaultAccountState.read(data, 0),
        data -> MemoTransfer.read(data, 0),
        data -> InterestBearingConfig.read(data, 0),
        data -> CpiGuard.read(data, 0),
        data -> PermanentDelegate.read(data, 0),
        data -> TransferHook.read(data, 0),
        data -> TransferHookAccount.read(data, 0),
        data -> ConfidentialTransferFeeConfig.read(data, 0, 0),
        data -> ConfidentialTransferFeeAmount.read(data, 0, 0),
        data -> MetadataPointer.read(data, 0),
        data -> TokenMetadata.read(data, 0),
        data -> GroupPointer.read(data, 0),
        data -> TokenGroup.read(data, 0),
        data -> GroupMemberPointer.read(data, 0),
        data -> TokenGroupMember.read(data, 0),
        data -> ConfidentialMintBurn.read(data, 0),
        data -> ScaledUiAmountConfig.read(data, 0),
        data -> PausableConfig.read(data, 0),
        data -> PermissionedBurnConfig.read(data, 0),
        // TokenAccount.read has no null/empty guard, unlike every other FACTORY reader; it
        // throws NPE/IndexOutOfBounds instead and is deliberately absent here
        data -> Mint.read(PublicKey.NONE, data),
        data -> Token2022.read(PublicKey.NONE, data),
        data -> Token2022Account.read(PublicKey.NONE, data)
    );
    for (int i = 0; i < readers.size(); ++i) {
      final var reader = readers.get(i);
      final int index = i;
      assertNull(reader.apply(null), () -> "reader " + index + " on null data");
      assertNull(reader.apply(new byte[0]), () -> "reader " + index + " on empty data");
    }
  }

  @Test
  void alternatePolarityRoundTrip() {
    final var memoTransfer = new MemoTransfer(false);
    assertFalse(memoTransfer.requireIncomingTransferMemos());
    final var extensions = new TokenExtension[]{
        new ConfidentialTransferMint(key(1), false, key(2)),
        new ConfidentialTransferAccount(
            false,
            key(3),
            bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 4),
            bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 5),
            bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 6),
            bytes(ElGamal.AE_CIPHERTEXT_LEN, 7),
            false,
            true,
            4L,
            5L,
            6L,
            7L
        ),
        new DefaultAccountState(AccountState.Initialized.ordinal()),
        memoTransfer,
        new CpiGuard(false),
        new TransferHookAccount(false),
        new ConfidentialTransferFeeConfig(key(8), key(9), false, bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 10))
    };
    int l = 0;
    for (final var extension : extensions) {
      l += Integer.BYTES + extension.l();
    }
    final byte[] data = new byte[l];
    int i = 0;
    for (final var extension : extensions) {
      i += TokenExtension.write(extension, data, i);
    }
    assertEquals(l, i);
    assertEquals(Set.of(extensions), Token2022.parseExtensions(data, 0));
  }

  private static void assertValueSemantics(final TokenExtension base,
                                           final TokenExtension same,
                                           final TokenExtension... variants) {
    assertEquals(base, base);
    assertEquals(base, same);
    assertEquals(base.hashCode(), same.hashCode());
    assertNotEquals(base, null);
    assertNotEquals(base, ImmutableOwner.INSTANCE);
    assertFalse(base.toString().isEmpty());
    for (final var variant : variants) {
      assertNotEquals(base, variant, variant.toString());
      assertNotEquals(variant, base, variant.toString());
      assertNotEquals(base.hashCode(), variant.hashCode(), variant.toString());
    }
  }

  @Test
  void confidentialTransferAccountValueSemantics() {
    assertValueSemantics(
        confidentialTransferAccount(0),
        confidentialTransferAccount(0),
        confidentialTransferAccount(1),
        confidentialTransferAccount(2),
        confidentialTransferAccount(3),
        confidentialTransferAccount(4),
        confidentialTransferAccount(5),
        confidentialTransferAccount(6),
        confidentialTransferAccount(7),
        confidentialTransferAccount(8),
        confidentialTransferAccount(9),
        confidentialTransferAccount(10),
        confidentialTransferAccount(11),
        confidentialTransferAccount(12)
    );
  }

  /// The base instance with the numbered component replaced, 0 for the base itself.
  private static ConfidentialTransferAccount confidentialTransferAccount(final int variant) {
    return new ConfidentialTransferAccount(
        variant != 1,
        variant == 2 ? key(99) : key(3),
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, variant == 3 ? 99 : 4),
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, variant == 4 ? 99 : 5),
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, variant == 5 ? 99 : 6),
        bytes(ElGamal.AE_CIPHERTEXT_LEN, variant == 6 ? 99 : 7),
        variant == 7,
        variant == 8,
        variant == 9 ? 99L : 4L,
        variant == 10 ? 99L : 5L,
        variant == 11 ? 99L : 6L,
        variant == 12 ? 99L : 7L
    );
  }

  @Test
  void confidentialMintBurnValueSemantics() {
    assertValueSemantics(
        confidentialMintBurn(0),
        confidentialMintBurn(0),
        confidentialMintBurn(1),
        confidentialMintBurn(2),
        confidentialMintBurn(3),
        confidentialMintBurn(4)
    );
  }

  private static ConfidentialMintBurn confidentialMintBurn(final int variant) {
    return new ConfidentialMintBurn(
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, variant == 1 ? 99 : 4),
        bytes(ElGamal.AE_CIPHERTEXT_LEN, variant == 2 ? 99 : 5),
        variant == 3 ? key(99) : key(6),
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, variant == 4 ? 99 : 7)
    );
  }

  @Test
  void confidentialTransferFeeValueSemantics() {
    assertValueSemantics(
        confidentialTransferFeeConfig(0),
        confidentialTransferFeeConfig(0),
        confidentialTransferFeeConfig(1),
        confidentialTransferFeeConfig(2),
        confidentialTransferFeeConfig(3),
        confidentialTransferFeeConfig(4)
    );
    assertValueSemantics(
        new ConfidentialTransferFeeAmount(bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 4)),
        new ConfidentialTransferFeeAmount(bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 4)),
        new ConfidentialTransferFeeAmount(bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, 99))
    );
  }

  private static ConfidentialTransferFeeConfig confidentialTransferFeeConfig(final int variant) {
    return new ConfidentialTransferFeeConfig(
        variant == 1 ? key(99) : key(4),
        variant == 2 ? key(99) : key(5),
        variant != 3,
        bytes(ElGamal.ELGAMAL_CIPHERTEXT_LEN, variant == 4 ? 99 : 6)
    );
  }

  @Test
  void unknownExtensionValueSemantics() {
    assertValueSemantics(
        new UnknownTokenExtension(999, bytes(3, 4)),
        new UnknownTokenExtension(999, bytes(3, 4)),
        new UnknownTokenExtension(998, bytes(3, 4)),
        new UnknownTokenExtension(999, bytes(3, 5))
    );
    assertTrue(new UnknownTokenExtension(999, bytes(3, 4)).toString().contains("999"));
  }

  @Test
  void accountTypesReleasedAfterLastSyncParseAsNull() {
    final byte[] mintData = new byte[Mint.BYTES + 83 + 1];
    mintData[Mint.BYTES + 83] = (byte) 200;
    final var token2022 = Token2022.read(key(1), mintData);
    assertNull(token2022.accountType());

    final byte[] accountData = new byte[TokenAccount.BYTES + 1];
    accountData[TokenAccount.BYTES] = (byte) 200;
    final var account = Token2022Account.read(key(1), accountData);
    assertNull(account.type());
  }

  @Test
  @SuppressWarnings("deprecation")
  void extensionsMapDropsUnknownExtensions() {
    final var pausableConfig = new PausableConfig(key(1), true);
    final var unknown = new UnknownTokenExtension(999, bytes(2, 3));
    final var mint = new Mint(key(2), null, 0L, 6, true, null);
    final var token2022 = new Token2022(
        mint,
        AccountType.Mint,
        new LinkedHashSet<>(List.of(pausableConfig, unknown))
    );
    assertEquals(Map.of(ExtensionType.Pausable, pausableConfig), token2022.extensions());
  }

  @Test
  void uninitializedExtension() {
    assertEquals(ExtensionType.Uninitialized, Uninitialized.INSTANCE.extensionType());
    assertEquals(0, Uninitialized.INSTANCE.l());
  }

  @Test
  void mixedAuthorityMintsRoundTrip() {
    final int freezeOptionOffset = Integer.BYTES + PublicKey.PUBLIC_KEY_LENGTH + Long.BYTES + 1 + 1;
    for (final var mint : new Mint[]{
        new Mint(key(1), null, 21L, 6, false, key(2)),
        new Mint(key(1), key(3), 42L, 9, true, null)
    }) {
      assertEquals(Mint.BYTES, mint.l());
      final byte[] data = new byte[Mint.BYTES];
      assertEquals(Mint.BYTES, mint.write(data, 0));
      assertEquals(mint, Mint.read(key(1), data));

      // authority options must be written even into a dirty buffer; only the 32 key bytes
      // of an absent authority may retain whatever was there before
      final byte[] dirty = new byte[Mint.BYTES];
      Arrays.fill(dirty, (byte) 0xAA);
      mint.write(dirty, 0);
      assertEquals(mint.mintAuthority() == null ? 0 : 1, ByteUtil.getInt32LE(dirty, 0));
      assertEquals(mint.freezeAuthority() == null ? 0 : 1, ByteUtil.getInt32LE(dirty, freezeOptionOffset));
      assertEquals(mint, Mint.read(key(1), dirty));
    }
  }

  @Test
  void writeReturnsConsumedLengthAtNonZeroOffsets() {
    final var mint = new Mint(key(1), key(2), 5L, 6, true, key(3));
    final var token2022 = new Token2022(mint, AccountType.Mint, Set.of(new CpiGuard(true)));
    byte[] out = new byte[7 + token2022.l()];
    assertEquals(token2022.l(), token2022.write(out, 7));

    final var tokenAccount = new TokenAccount(
        key(4), key(5), key(6), 42L,
        1, key(7),
        AccountState.Initialized,
        0, 0L, 21L,
        1, key(8)
    );
    final var account = new Token2022Account(tokenAccount, AccountType.Account, Set.of(new CpiGuard(false)));
    out = new byte[5 + account.l()];
    assertEquals(account.l(), account.write(out, 5));
  }

  @Test
  void ordinalsExactlyAtTheEnumBoundary() {
    // values().length is the first ordinal released after the last sync
    final byte[] mintData = new byte[Mint.BYTES + 83 + 1];
    mintData[Mint.BYTES + 83] = (byte) AccountType.values().length;
    assertNull(Token2022.read(key(1), mintData).accountType());

    final byte[] tlv = new byte[Integer.BYTES];
    ByteUtil.putInt16LE(tlv, 0, ExtensionType.values().length);
    assertEquals(
        Set.of(new UnknownTokenExtension(ExtensionType.values().length, new byte[0])),
        Token2022.parseExtensions(tlv, 0)
    );
  }

  @Test
  void isNativeGatedByItsOption() {
    final byte[] data = new byte[TokenAccount.BYTES];
    ByteUtil.putInt64LE(data, TokenAccount.IS_NATIVE_OFFSET, 42L);
    final var account = TokenAccount.read(key(1), data);
    assertEquals(0, account.isNativeOption());
    assertEquals(0, account.isNative());
  }

  @Test
  void multiEntryAdditionalMetadataRoundTrip() {
    // two entries so the cursor advance between entries is exercised, not just the final one
    final var tokenMetadata = new TokenMetadata(
        key(1), key(2), "n", "s", "u",
        Map.of("k1", "v1", "key2", "value2")
    );
    final byte[] data = new byte[Integer.BYTES + tokenMetadata.l()];
    TokenExtension.write(tokenMetadata, data, 0);
    assertEquals(Set.of(tokenMetadata), Token2022.parseExtensions(data, 0));
  }

  @Test
  void tokenAccountFilters() {
    final var probe = key(9);
    final var expectedJson = """
        {"memcmp":{"offset":%d,"bytes":"%s"}}""";
    assertEquals(expectedJson.formatted(TokenAccount.MINT_OFFSET, probe.toBase58()),
        TokenAccount.createMintFilter(probe).toJson());
    assertEquals(expectedJson.formatted(TokenAccount.OWNER_OFFSET, probe.toBase58()),
        TokenAccount.createOwnerFilter(probe).toJson());
    assertEquals(expectedJson.formatted(TokenAccount.DELEGATE_OFFSET, probe.toBase58()),
        TokenAccount.createDelegateFilter(probe).toJson());
    assertEquals(expectedJson.formatted(TokenAccount.CLOSE_AUTHORITY_OFFSET, probe.toBase58()),
        TokenAccount.createCloseAuthorityFilter(probe).toJson());
  }

  @Test
  void additionalMetadataCountAtTheBound() {
    // updateAuthority + mint + empty name/symbol/uri, one entry of two empty strings: the
    // count exactly matches the eight remaining bytes and must parse
    final byte[] data = new byte[32 + 32 + 4 + 4 + 4 + 4 + 8];
    data[32 + 32 + 4 + 4 + 4] = 1;
    final var tokenMetadata = TokenMetadata.read(data, 0);
    assertEquals(Map.of("", ""), tokenMetadata.additionalMetadata());

    // one entry more than the remaining bytes can hold must be rejected
    data[32 + 32 + 4 + 4 + 4] = 2;
    assertThrows(IllegalArgumentException.class, () -> TokenMetadata.read(data, 0));
  }
}
