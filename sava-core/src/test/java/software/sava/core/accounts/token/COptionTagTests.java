package software.sava.core.accounts.token;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The five `COption` presence tags in the SPL Token base states — a mint's two authorities and
/// a token account's delegate, native reserve and close authority — take exactly two values in
/// an account a token program owns: the data was all zero when the program received it, and both
/// programs write only `[0, 0, 0, 0]` and `[1, 0, 0, 0]` (Token-2022 through `PodCOption` and the
/// `Pack` encoder, interface/src/pod.rs and interface/src/state.rs; p-token as a 0 or 1 into the
/// first byte, padding untouched, pinocchio/interface/src/state). The refusal enforces that
/// encoding rather than mirroring a program check, because the decoders differ on one: the
/// `Pack` decoder's `unpack_coption_key` and `unpack_coption_u64` — the validator's parsed
/// encoding, and Token-2022's `Reallocate` — return `InvalidAccountData` for a third tag,
/// Token-2022's other instructions read the base state zero-copy and reject it in one place only
/// (`WithdrawExcessLamports` on a mint), and p-token reads the first byte alone. This library
/// used to read such a tag as "absent" and, on a token account, write it back untouched —
/// decoding bytes no program writes into a record that looked whole, and re-encoding them byte
/// for byte as if one had.
///
/// Each site is exercised on its own, with its two legal values kept and its illegal ones
/// refused, because each site is its own comparison in production.
final class COptionTagTests {

  private static PublicKey key(final int fill) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(bytes, (byte) fill);
    return PublicKey.createPubKey(bytes);
  }

  private static byte[] mintWithBothAuthorities() {
    final byte[] data = new byte[Mint.BYTES];
    new Mint(key(1), key(2), 5L, 6, true, key(3)).write(data, 0);
    return data;
  }

  private static byte[] tokenAccountWithEveryOption() {
    final byte[] data = new byte[TokenAccount.BYTES];
    new TokenAccount(
        key(4), key(5), key(6), 42L,
        1, key(7),
        AccountState.Initialized,
        1, 2_039_280L, 21L,
        1, key(8)
    ).write(data, 0);
    return data;
  }

  private static byte[] withTag(final byte[] data, final int offset, final int tag) {
    final byte[] copy = data.clone();
    ByteUtil.putInt32LE(copy, offset, tag);
    return copy;
  }

  private static final int MINT_AUTHORITY_TAG = 0;
  private static final int FREEZE_AUTHORITY_TAG = Integer.BYTES + PublicKey.PUBLIC_KEY_LENGTH
      + Long.BYTES + 1 + 1;

  private static void assertRefused(final Runnable read, final String field, final String tag) {
    final var thrown = assertThrows(IllegalArgumentException.class, read::run);
    assertEquals("Invalid " + field + " tag " + tag + ": a COption tag is 0 or 1.", thrown.getMessage());
  }

  @Test
  void mintAuthorityTagTakesOnlyItsTwoValues() {
    final byte[] data = mintWithBothAuthorities();
    assertEquals(key(2), Mint.read(key(1), withTag(data, MINT_AUTHORITY_TAG, 1)).mintAuthority());
    assertNull(Mint.read(key(1), withTag(data, MINT_AUTHORITY_TAG, 0)).mintAuthority());
    assertRefused(() -> Mint.read(key(1), withTag(data, MINT_AUTHORITY_TAG, 2)), "mint authority", "2");
    // Printed as the unsigned number the four bytes spell, not as a sign-extended int.
    assertRefused(() -> Mint.read(key(1), withTag(data, MINT_AUTHORITY_TAG, -1)), "mint authority", "4294967295");
  }

  @Test
  void freezeAuthorityTagTakesOnlyItsTwoValues() {
    final byte[] data = mintWithBothAuthorities();
    assertEquals(key(3), Mint.read(key(1), withTag(data, FREEZE_AUTHORITY_TAG, 1)).freezeAuthority());
    assertNull(Mint.read(key(1), withTag(data, FREEZE_AUTHORITY_TAG, 0)).freezeAuthority());
    assertRefused(() -> Mint.read(key(1), withTag(data, FREEZE_AUTHORITY_TAG, 2)), "freeze authority", "2");
    assertRefused(() -> Mint.read(key(1), withTag(data, FREEZE_AUTHORITY_TAG, 0x100)), "freeze authority", "256");
  }

  @Test
  void delegateTagTakesOnlyItsTwoValues() {
    final byte[] data = tokenAccountWithEveryOption();
    final var present = TokenAccount.read(key(4), withTag(data, TokenAccount.DELEGATE_OPTION_OFFSET, 1));
    assertEquals(1, present.delegateOption());
    assertEquals(key(7), present.delegate());
    final var absent = TokenAccount.read(key(4), withTag(data, TokenAccount.DELEGATE_OPTION_OFFSET, 0));
    assertEquals(0, absent.delegateOption());
    assertNull(absent.delegate());
    assertRefused(() -> TokenAccount.read(key(4), withTag(data, TokenAccount.DELEGATE_OPTION_OFFSET, 2)), "delegate", "2");
    assertRefused(() -> TokenAccount.read(key(4), withTag(data, TokenAccount.DELEGATE_OPTION_OFFSET, -1)), "delegate", "4294967295");
  }

  @Test
  void isNativeTagTakesOnlyItsTwoValues() {
    final byte[] data = tokenAccountWithEveryOption();
    final var present = TokenAccount.read(key(4), withTag(data, TokenAccount.IS_NATIVE_OPTION_OFFSET, 1));
    assertEquals(1, present.isNativeOption());
    assertEquals(2_039_280L, present.isNative());
    final var absent = TokenAccount.read(key(4), withTag(data, TokenAccount.IS_NATIVE_OPTION_OFFSET, 0));
    assertEquals(0, absent.isNativeOption());
    assertEquals(0L, absent.isNative());
    assertRefused(() -> TokenAccount.read(key(4), withTag(data, TokenAccount.IS_NATIVE_OPTION_OFFSET, 2)), "is-native", "2");
    assertRefused(() -> TokenAccount.read(key(4), withTag(data, TokenAccount.IS_NATIVE_OPTION_OFFSET, 0x02000000)), "is-native", "33554432");
  }

  @Test
  void closeAuthorityTagTakesOnlyItsTwoValues() {
    final byte[] data = tokenAccountWithEveryOption();
    final var present = TokenAccount.read(key(4), withTag(data, TokenAccount.CLOSE_AUTHORITY_OPTION_OFFSET, 1));
    assertEquals(1, present.closeAuthorityOption());
    assertEquals(key(8), present.closeAuthority());
    final var absent = TokenAccount.read(key(4), withTag(data, TokenAccount.CLOSE_AUTHORITY_OPTION_OFFSET, 0));
    assertEquals(0, absent.closeAuthorityOption());
    assertNull(absent.closeAuthority());
    assertRefused(() -> TokenAccount.read(key(4), withTag(data, TokenAccount.CLOSE_AUTHORITY_OPTION_OFFSET, 2)), "close authority", "2");
    assertRefused(() -> TokenAccount.read(key(4), withTag(data, TokenAccount.CLOSE_AUTHORITY_OPTION_OFFSET, -1)), "close authority", "4294967295");
  }

  /// The extended readers decode the base state first, so a bad tag stops them before the
  /// account-type byte or any extension is looked at.
  @Test
  void theExtendedReadersRefuseTheSameTags() {
    final byte[] mint = Arrays.copyOf(mintWithBothAuthorities(), Mint.BYTES + 83 + 1);
    mint[Mint.BYTES + 83] = 1;
    assertNotNull(Token2022.read(key(1), mint));
    assertRefused(() -> Token2022.read(key(1), withTag(mint, FREEZE_AUTHORITY_TAG, 3)), "freeze authority", "3");

    final byte[] account = Arrays.copyOf(tokenAccountWithEveryOption(), TokenAccount.BYTES + 1);
    account[TokenAccount.BYTES] = 2;
    assertNotNull(Token2022Account.read(key(4), account));
    assertRefused(() -> Token2022Account.read(key(4), withTag(account, TokenAccount.DELEGATE_OPTION_OFFSET, 3)), "delegate", "3");
  }

  /// A record built by a caller is not validated on the way out: the tags it carries are written
  /// as given, which is what lets a decoded account round-trip, and a caller who builds a bad one
  /// finds out when it is read back.
  @Test
  void writeDoesNotValidateWhatReadRefuses() {
    final var account = new TokenAccount(
        key(4), key(5), key(6), 42L,
        2, null,
        AccountState.Initialized,
        0, 0L, 0L,
        0, null
    );
    final byte[] data = new byte[TokenAccount.BYTES];
    assertEquals(TokenAccount.BYTES, account.write(data, 0));
    assertEquals(2, ByteUtil.getInt32LE(data, TokenAccount.DELEGATE_OPTION_OFFSET));
    assertTrue(assertThrows(IllegalArgumentException.class, () -> TokenAccount.read(key(4), data))
        .getMessage().startsWith("Invalid delegate tag 2"));
  }
}
