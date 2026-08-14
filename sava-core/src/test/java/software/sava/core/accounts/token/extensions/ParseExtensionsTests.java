package software.sava.core.accounts.token.extensions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.accounts.token.Token2022;
import software.sava.core.accounts.token.Token2022Account;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.io.IOException;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ParseExtensionsTests {

  private static byte[] mainnetFixture(final String name) throws IOException {
    try (final var input = ParseExtensionsTests.class.getResourceAsStream("/fuzz/token2022/" + name)) {
      assertNotNull(input, "missing Token-2022 mainnet fixture " + name);
      return input.readAllBytes();
    }
  }

  // extensions are keyed by their sealed type now that the ExtensionType map is deprecated
  private static <T extends TokenExtension> T assertExtension(final Set<TokenExtension> extensions, final Class<T> type) {
    for (final var extension : extensions) {
      if (type.isInstance(extension)) {
        return type.cast(extension);
      }
    }
    return fail("missing extension " + type.getSimpleName());
  }

  @Test
  void unsignedTypeAndLength() {
    // Type and length are u16 on-chain. A type >= 0x8000 must reach the unknown-extension
    // escape hatch, while an unsigned length that exceeds the remaining TLV data is corrupt.
    final var oversized = assertThrows(
        IndexOutOfBoundsException.class,
        () -> Token2022.parseExtensions(new byte[]{7, 0, (byte) 0xFC, (byte) 0xFF}, 0)
    );
    assertEquals("Extension 7 claims 65532 bytes, but only 0 remain.", oversized.getMessage());

    final var unknown = Token2022.parseExtensions(new byte[]{0, (byte) 0x80, 2, 0, (byte) 0xAB, (byte) 0xCD}, 0);
    assertEquals(1, unknown.size());
    final var extension = assertInstanceOf(UnknownTokenExtension.class, unknown.iterator().next());
    assertEquals(0x8000, extension.type());
    assertEquals(0x8000, extension.ordinal());
    assertArrayEquals(new byte[]{(byte) 0xAB, (byte) 0xCD}, extension.data());
  }

  @Test
  void unknownExtensionLengthBeyondDataEnd() {
    // an unknown extension claiming one byte more than remains must throw like known
    // extensions do, not fabricate a zero-padded tail via copyOfRange
    final byte[] data = {0, (byte) 0x80, 3, 0, (byte) 0xAB, (byte) 0xCD};
    final var exception = assertThrows(IndexOutOfBoundsException.class, () -> Token2022.parseExtensions(data, 0));
    assertEquals("Extension 32768 claims 3 bytes, but only 2 remain.", exception.getMessage());
  }

  @Test
  void duplicateRawExtensionTypesAreRejectedBeforeSetProjection() {
    // Identical singleton values used to collapse in the public Set, losing a TLV entry.
    final byte[] duplicateImmutableOwner = {7, 0, 0, 0, 7, 0, 0, 0};
    final var known = assertThrows(
        IllegalArgumentException.class,
        () -> Token2022.parseExtensions(duplicateImmutableOwner, 0)
    );
    assertEquals("Duplicate extension type: 7", known.getMessage());

    // Check the raw u16 before dispatch: unknown entries with the same type are duplicates
    // even when their values differ and therefore would both fit in the Set.
    final byte[] duplicateUnknown = {0, (byte) 0x80, 1, 0, 1, 0, (byte) 0x80, 1, 0, 2};
    final var unknown = assertThrows(
        IllegalArgumentException.class,
        () -> Token2022.parseExtensions(duplicateUnknown, 0)
    );
    assertEquals("Duplicate extension type: 32768", unknown.getMessage());
  }

  @Test
  void knownExtensionLengthMustMatchItsRustLayout() {
    final var longImmutableOwner = new byte[]{7, 0, 1, 0, 42};
    final var longError = assertThrows(
        IllegalArgumentException.class,
        () -> Token2022.parseExtensions(longImmutableOwner, 0)
    );
    assertEquals("Extension ImmutableOwner claims 1 bytes, expected 0.", longError.getMessage());

    // A short fixed-size value must not borrow bytes from the following TLV entry.
    final var shortMintCloseAuthority = new byte[4 + 31 + 4];
    shortMintCloseAuthority[0] = 3;
    shortMintCloseAuthority[2] = 31;
    shortMintCloseAuthority[4 + 31] = 7;
    final var shortError = assertThrows(
        IllegalArgumentException.class,
        () -> Token2022.parseExtensions(shortMintCloseAuthority, 0)
    );
    assertEquals("Extension MintCloseAuthority claims 31 bytes, expected 32.", shortError.getMessage());
  }

  /// SPL's variable-length TLV path passes the declared value slice to
  /// `TokenMetadata::unpack_from_slice`; an empty slice cannot decode the required Borsh
  /// fields and is rejected. It is not an absent extension once its TLV header is present.
  @Test
  @SuppressWarnings("removal") // the wire ordinal is owned by the deprecated compatibility enum until its removal
  void zeroLengthTokenMetadataIsRejected() {
    final byte[] data = new byte[Integer.BYTES];
    ByteUtil.putInt16LE(data, 0, ExtensionType.TokenMetadata.ordinal());

    final var exception = assertThrows(
        IllegalArgumentException.class,
        () -> Token2022.parseExtensions(data, 0)
    );
    assertEquals(
        "Extension TokenMetadata claims 0 bytes, but contains no value.",
        exception.getMessage()
    );
  }

  @Test
  void unsignedTransferFeeBasisPoints() {
    // PodU16 on the wire: read as signed i16, a value with the top bit set came back
    // negative, which passes a `fee <= MAX_FEE_BASIS_POINTS` sanity check at the caller
    // rather than failing it. The program caps the field at 10_000, so only a corrupt or
    // fabricated account reaches this — which is what a client-side parser is handed.
    final byte[] data = new byte[TransferFee.BYTES];
    ByteUtil.putInt64LE(data, 0, 605L);
    ByteUtil.putInt64LE(data, Long.BYTES, Long.MAX_VALUE);
    ByteUtil.putInt16LE(data, Long.BYTES + Long.BYTES, (short) 0xFFFF);

    final var transferFee = TransferFee.read(data, 0);
    assertEquals(605L, transferFee.epoch());
    assertEquals(Long.MAX_VALUE, transferFee.maximumFee());
    assertEquals(65_535, transferFee.transferFeeBasisPoints());

    // the widened value still serialises back to the bytes it was read from
    final byte[] written = new byte[TransferFee.BYTES];
    assertEquals(TransferFee.BYTES, transferFee.write(written, 0));
    assertArrayEquals(data, written);
  }

  @Test
  void corruptAdditionalMetadataCount() {
    // updateAuthority + mint + empty name/symbol/uri, then a count claiming far more
    // entries than the remaining bytes could hold; the reader must reject the count
    // instead of allocating an entry array sized by attacker-controlled account data
    final byte[] data = new byte[32 + 32 + 4 + 4 + 4 + 4];
    data[32 + 32 + 4 + 4 + 4] = (byte) 0xFF;
    data[32 + 32 + 4 + 4 + 4 + 1] = (byte) 0xFF;
    data[32 + 32 + 4 + 4 + 4 + 2] = (byte) 0xFF;
    data[32 + 32 + 4 + 4 + 4 + 3] = 0x7F;
    assertThrows(IllegalArgumentException.class, () -> TokenMetadata.read(data, 0));

    // the count is a u32, so the other half of the range is negative as an int and is
    // not caught by the upper bound: unguarded it reached `new Map.Entry[-16777216]`
    final byte[] topBitSet = new byte[32 + 32 + 4 + 4 + 4 + 4];
    topBitSet[32 + 32 + 4 + 4 + 4 + 3] = (byte) 0xFF;
    final var thrown = assertThrows(IllegalArgumentException.class, () -> TokenMetadata.read(topBitSet, 0));
    assertEquals("Invalid additional metadata count: -16777216", thrown.getMessage());
  }

  @Test
  void duplicateAdditionalMetadataKeyIsRejectedByTheDirectReader() {
    final int fieldsLength = (PublicKey.PUBLIC_KEY_LENGTH * 2) + (Integer.BYTES * 4);
    final int entriesLength = (Borsh.len("duplicate") + Borsh.len("first"))
        + (Borsh.len("duplicate") + Borsh.len("second"));
    final byte[] data = new byte[fieldsLength + entriesLength];
    int i = (PublicKey.PUBLIC_KEY_LENGTH * 2) + (Integer.BYTES * 3);
    ByteUtil.putInt32LE(data, i, 2);
    i += Integer.BYTES;
    i += Borsh.write("duplicate", data, i);
    i += Borsh.write("first", data, i);
    i += Borsh.write("duplicate", data, i);
    i += Borsh.write("second", data, i);
    assertEquals(data.length, i);

    final var duplicate = assertThrows(
        IllegalArgumentException.class,
        () -> TokenMetadata.read(data, 0)
    );
    assertEquals("Duplicate additional metadata key: duplicate", duplicate.getMessage());
  }

  @Test
  void confidentialTokenAccount() throws IOException {
    final byte[] data = Base64.getDecoder().decode("""
        RxdtkYpXhCDhXf92mJjxs3oZ3ex+alJvU1LnfB+xzfxhoty+GQ/aYo6Ekj8G1Njo9gjTTa9EWq9e338fGgvWKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgcAAAAFACcBASSV8fcw/nQLyJBayJHB+UCYPPPoNkClfQLx/iFBqvQVAmXmv7UFCY11M7oWWvTE/0mEsdyVHE3tkaMweLfcQFFEB5pvlUiDRJIiIijWYBxatPJAihLSrmvMypcRrCDCCC4mK0ztqaB12QEFnjXJkSvFIHVAsMcR8gIvEke8w3xPjEzQLztO6GgsHghnlrlLSF6lAOvS1NlIbW+Qlh+wlEX6oDJPu4alTqmSccdS4kDNLEOlkK/xR6CvDLcuskcDUfT1b9LH7qUJe2HcQsUi6f6xQ16KSVvXbfltklVYjx5+RWcFUwYZ+XswiBOuPy2ZjKHbPBY1fahZCigCqTg2IcrV7X8xAQEBAAAAAAAAAAAAAQAAAAAAAQAAAAAAAAABAAAAAAAAAA==
        """.stripTrailing());
    assertArrayEquals(mainnetFixture("confidential_account"), data);

    final var address = PublicKey.fromBase58Encoded("A9JXuXgm62QG3kTT5waRdEMiGw1w7TY2ovs8MoFWetmZ");
    final var account = Token2022Account.read(address, data);

    assertEquals(AccountType.Account, account.type());

    final var tokenAccount = account.tokenAccount();
    assertEquals(address, tokenAccount.address());
    assertEquals(PublicKey.fromBase58Encoded("5nWfeifw56n8mhpLp4b3BHW2foLdbF9PCo4Y6MJPdyEB"), tokenAccount.mint());
    assertEquals(PublicKey.fromBase58Encoded("7a8aotmeu1J11XCBBmXUorDeTDbVmxfBY23C4GUxvqVy"), tokenAccount.owner());
    assertEquals(0, tokenAccount.amount());
    assertEquals(0, tokenAccount.delegateOption());
    assertNull(tokenAccount.delegate());
    assertEquals(AccountState.Initialized, tokenAccount.state());
    assertEquals(0, tokenAccount.isNativeOption());
    assertEquals(0, tokenAccount.isNative());
    assertEquals(0, tokenAccount.delegatedAmount());
    assertEquals(0, tokenAccount.closeAuthorityOption());
    assertNull(tokenAccount.closeAuthority());

    final var extensions = account.tokenExtensions();
    assertEquals(2, extensions.size());

    assertExtension(extensions, ImmutableOwner.class);

    final var confidentialTransferAccount = assertExtension(extensions, ConfidentialTransferAccount.class);
    assertTrue(confidentialTransferAccount.approved());
    assertEquals(PublicKey.fromBase58Encoded("3TpHnXSnhyqK9re84Nvxg4iYsqqWpiZYdDSK3LvajNMS"), confidentialTransferAccount.elgamalPubkey());

    assertArrayEquals(
        Base64.getDecoder().decode("""
            AmXmv7UFCY11M7oWWvTE/0mEsdyVHE3tkaMweLfcQFFEB5pvlUiDRJIiIijWYBxatPJAihLSrmvMypcRrCDCCA=="""),
        confidentialTransferAccount.pendingBalanceLo()
    );
    assertArrayEquals(
        Base64.getDecoder().decode("""
            LiYrTO2poHXZAQWeNcmRK8UgdUCwxxHyAi8SR7zDfE+MTNAvO07oaCweCGeWuUtIXqUA69LU2Uhtb5CWH7CURQ=="""),
        confidentialTransferAccount.pendingBalanceHi()
    );
    assertArrayEquals(
        Base64.getDecoder().decode("""
            +qAyT7uGpU6pknHHUuJAzSxDpZCv8Uegrwy3LrJHA1H09W/Sx+6lCXth3ELFIun+sUNeiklb1235bZJVWI8efg=="""),
        confidentialTransferAccount.availableBalance()
    );
    assertArrayEquals(
        Base64.getDecoder().decode("""
            RWcFUwYZ+XswiBOuPy2ZjKHbPBY1fahZCigCqTg2IcrV7X8x"""),
        confidentialTransferAccount.decryptableAvailableBalance()
    );

    assertTrue(confidentialTransferAccount.allowConfidentialCredits());
    assertTrue(confidentialTransferAccount.allowNonConfidentialCredits());
    assertEquals(1, confidentialTransferAccount.pendingBalanceCreditCounter());
    assertEquals(65536, confidentialTransferAccount.maximumPendingBalanceCreditCounter());
    assertEquals(1, confidentialTransferAccount.expectedPendingBalanceCreditCounter());
    assertEquals(1, confidentialTransferAccount.actualPendingBalanceCreditCounter());
  }

  @Test
  void payPalExtensionsTest() throws IOException {
    final byte[] data = Base64.getDecoder().decode("""
        AQAAAN1MSGyQ+LbwB8ME7ySB+AUYa+j9X1Ks0QJct5ufZ/8hLuljgmRzAAAGAQEAAAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQMAIAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKwwAIAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKwEAbAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKxeFMmHvarhTKmfwU4ZarTEpP88HzxIKtbmhVwZUjcArAAAAAAAAAABdAgAAAAAAAAAAAAAAAAAAAABdAgAAAAAAAAAAAAAAAAAAAAAEAEEAF4UyYe9quFMqZ/BThlqtMSk/zwfPEgq1uaFXBlSNwCsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAIEAF4UyYe9quFMqZ/BThlqtMSk/zwfPEgq1uaFXBlSNwCscN+ZDO3ME3YJzeuQNm4vzxJ9bDmxJqNUzKLPlBpAcVwEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADgBAABeFMmHvarhTKmfwU4ZarTEpP88HzxIKtbmhVwZUjcArAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAASAEAAF4UyYe9quFMqZ/BThlqtMSk/zwfPEgq1uaFXBlSNwCsXkkg7bIoqh7dHHYFPlZH5OVyECpzj2fTVun06S4p0nhMArgAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKxeSSDtsiiqHt0cdgU+Vkfk5XIQKnOPZ9NW6fTpLinSeCgAAAFBheVBhbCBVU0QFAAAAUFlVU0RPAAAAaHR0cHM6Ly90b2tlbi1tZXRhZGF0YS5wYXhvcy5jb20vcHl1c2RfbWV0YWRhdGEvcHJvZC9zb2xhbmEvcHl1c2RfbWV0YWRhdGEuanNvbgAAAAA=
        """.stripTrailing());
    assertArrayEquals(mainnetFixture("pyusd_mint"), data);

    final var token2022 = Token2022.read(
        PublicKey.fromBase58Encoded("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo"),
        data
    );

    final var extensions = token2022.tokenExtensions();
    assertEquals(8, extensions.size());

    final var mintCloseAuthority = assertExtension(extensions, MintCloseAuthority.class);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", mintCloseAuthority.closeAuthority().toBase58());

    final var permanentDelegate = assertExtension(extensions, PermanentDelegate.class);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", permanentDelegate.delegate().toBase58());

    final var transferFeeConfig = assertExtension(extensions, TransferFeeConfig.class);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", transferFeeConfig.transferFeeConfigAuthority().toBase58());
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", transferFeeConfig.withdrawWithheldAuthority().toBase58());
    assertEquals(0, transferFeeConfig.withheldAmount());

    final var newTransferFee = transferFeeConfig.newerTransferFee();
    assertEquals(605, newTransferFee.epoch());
    assertEquals(0, newTransferFee.maximumFee());
    assertEquals(0, newTransferFee.transferFeeBasisPoints());

    final var olderTransferFee = transferFeeConfig.olderTransferFee();
    assertEquals(605, olderTransferFee.epoch());
    assertEquals(0, olderTransferFee.maximumFee());
    assertEquals(0, olderTransferFee.transferFeeBasisPoints());

    final var confidentialTransferMint = assertExtension(extensions, ConfidentialTransferMint.class);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", confidentialTransferMint.authority().toBase58());
    assertFalse(confidentialTransferMint.autoApproveNewAccounts());
    assertEquals(PublicKey.NONE, confidentialTransferMint.auditorElGamalKey());

    final var confidentialTransferFeeConfig = assertExtension(extensions, ConfidentialTransferFeeConfig.class);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", confidentialTransferFeeConfig.authority().toBase58());
    assertEquals("HDfmQztzBN2Cc3rkDZuL88SfWw5sSajVMyiz5QaQHFc=", confidentialTransferFeeConfig.withdrawWithheldAuthorityElgamalPubkey().toBase64());
    assertTrue(confidentialTransferFeeConfig.harvestToMintEnabled());
    assertEquals(
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==",
        Base64.getEncoder().encodeToString(confidentialTransferFeeConfig.withheldAmount())
    );

    final var transferHook = assertExtension(extensions, TransferHook.class);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", transferHook.authority().toBase58());

    final var metadataPointer = assertExtension(extensions, MetadataPointer.class);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", metadataPointer.authority().toBase58());
    assertEquals("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", metadataPointer.metadataAddress().toBase58());

    final var tokenMetadata = assertExtension(extensions, TokenMetadata.class);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", tokenMetadata.updateAuthority().toBase58());
    assertEquals("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", tokenMetadata.mint().toBase58());
    assertEquals("PayPal USD", tokenMetadata.name());
    assertEquals("PYUSD", tokenMetadata.symbol());
    assertEquals(
        "https://token-metadata.paxos.com/pyusd_metadata/prod/solana/pyusd_metadata.json",
        tokenMetadata.uri()
    );
    final var additionalMetadata = tokenMetadata.additionalMetadata();
    assertTrue(additionalMetadata.isEmpty());
  }
}
