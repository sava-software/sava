package software.sava.core.accounts.token;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.extensions.AccountType;
import software.sava.core.accounts.token.extensions.CpiGuard;
import software.sava.core.accounts.token.extensions.MetadataPointer;
import software.sava.core.accounts.token.extensions.PausableConfig;
import software.sava.core.accounts.token.extensions.TokenExtension;
import software.sava.core.accounts.token.extensions.TokenMetadata;
import software.sava.core.accounts.token.extensions.Uninitialized;
import software.sava.core.accounts.token.extensions.UnknownTokenExtension;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Structural checks the Token-2022 interface crate applies before it will look at a
/// buffer's extension data. Ground truth is
/// `token-2022/interface/src/extension/mod.rs`: `check_min_len_and_not_multisig`,
/// `type_and_tlv_indices`, `check_account_type` and `unpack_tlv_data`.
final class Token2022StructuralTests {

  /// `Multisig::LEN` — interface/src/state.rs. Never extensible, so a buffer of exactly
  /// this size cannot be told apart from a mint or account carrying extensions.
  private static final int MULTISIG_BYTES = 355;

  private static final int MINT_PADDING = 83;
  private static final int MINT_ACCOUNT_TYPE_OFFSET = Mint.BYTES + MINT_PADDING;

  private static PublicKey key(final int fill) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(bytes, (byte) fill);
    return PublicKey.createPubKey(bytes);
  }

  private static Mint mint(final PublicKey address) {
    return new Mint(address, key(2), 1_000_000_000L, 9, true, key(3));
  }

  private static TokenAccount tokenAccount(final PublicKey address) {
    return new TokenAccount(
        address, key(5), key(6), 42L,
        1, key(7),
        AccountState.Initialized,
        0, 0L, 21L,
        1, key(8)
    );
  }

  /// A mint that never had extensions allocated is exactly `Mint::LEN` bytes and has no
  /// remainder at all. `type_and_tlv_indices` answers `Ok(None)` for an empty remainder,
  /// so the account decodes with no account type and no extensions rather than reaching
  /// for a byte at index 165 that a legal buffer need not have. An *empty* remainder is the
  /// only one it accepts: a shorter-than-84-byte remainder is `InvalidAccountData`, so a
  /// mint is either 82 bytes or 166 and up.
  @Test
  void extensionFreeMintDecodesAtItsBaseLength() {
    final var address = key(1);
    final var mint = mint(address);
    final byte[] data = new byte[Mint.BYTES];
    assertEquals(Mint.BYTES, mint.write(data, 0));

    final var parsed = Token2022.read(address, data);
    assertEquals(mint, parsed.mint());
    assertNull(parsed.accountType());
    assertEquals(Set.of(), parsed.tokenExtensions());

    // 82 in -> 82 out: the base state is the whole account, so nothing may be appended.
    assertEquals(Mint.BYTES, parsed.l());
    final byte[] written = new byte[parsed.l()];
    assertEquals(Mint.BYTES, parsed.write(written, 0));
    assertArrayEquals(data, written);
    assertEquals(parsed, Token2022.read(address, written));

    // A mint longer than Mint::LEN but with no room for the padding and the account-type byte
    // is malformed, not extension-free: one byte past the base length, and the last length
    // whose remainder is still too short.
    for (final int length : new int[]{Mint.BYTES + 1, MINT_ACCOUNT_TYPE_OFFSET}) {
      final var error = assertThrows(
          IllegalArgumentException.class,
          () -> Token2022.read(address, Arrays.copyOf(data, length)),
          () -> "a mint of " + length + " bytes must be rejected"
      );
      assertEquals(
          "A mint of " + length + " bytes is malformed: an extension-free mint is 82 bytes,"
              + " and a mint with extensions is at least 166.",
          error.getMessage()
      );
    }

    // 166 is the first length that carries a discriminant, and it parses.
    final byte[] extended = new byte[MINT_ACCOUNT_TYPE_OFFSET + 1];
    mint.write(extended, 0);
    extended[MINT_ACCOUNT_TYPE_OFFSET] = (byte) AccountType.Mint.ordinal();
    assertEquals(AccountType.Mint, Token2022.read(address, extended).accountType());
  }

  /// The base-state shortcut is the "no discriminant and nothing after it" case only. A known
  /// account type with no extensions is still an extended account: it keeps its 83 padding
  /// bytes and its discriminant, so it is 166 bytes, not 82.
  @Test
  void aKnownAccountTypeIsWrittenEvenWithNoExtensions() {
    final var address = key(1);
    final byte[] mintData = new byte[MINT_ACCOUNT_TYPE_OFFSET + 1];
    mintData[45] = 1; // Mint.is_initialized
    mintData[MINT_ACCOUNT_TYPE_OFFSET] = (byte) AccountType.Mint.ordinal();
    final var parsedMint = Token2022.read(address, mintData);
    assertEquals(AccountType.Mint, parsedMint.accountType());
    assertEquals(Set.of(), parsedMint.tokenExtensions());
    assertEquals(mintData.length, parsedMint.l());
    final byte[] writtenMint = new byte[parsedMint.l()];
    assertEquals(mintData.length, parsedMint.write(writtenMint, 0));
    assertArrayEquals(mintData, writtenMint);

    final byte[] accountData = new byte[TokenAccount.BYTES + 1];
    accountData[TokenAccount.STATE_OFFSET] = (byte) AccountState.Initialized.ordinal();
    accountData[TokenAccount.BYTES] = (byte) AccountType.Account.ordinal();
    final var parsedAccount = Token2022Account.read(address, accountData);
    assertEquals(AccountType.Account, parsedAccount.type());
    assertEquals(Set.of(), parsedAccount.tokenExtensions());
    assertEquals(accountData.length, parsedAccount.l());
    final byte[] writtenAccount = new byte[parsedAccount.l()];
    assertEquals(accountData.length, parsedAccount.write(writtenAccount, 0));
    assertArrayEquals(accountData, writtenAccount);
  }

  /// The other half: extensions with an account type this library does not know. The record
  /// keeps them, and its length still accounts for them — but there is no discriminant byte to
  /// write, so the write side has never been able to serialize this shape and still cannot.
  /// Pinned, not fixed: only a synced [AccountType] can express it.
  @Test
  void unknownAccountTypeWithExtensionsIsNotTheBaseStateShape() {
    final var address = key(1);
    final var pausableConfig = new PausableConfig(key(70), false);
    final var token2022 = new Token2022(mint(address), null, Set.of(pausableConfig));
    assertEquals(MINT_ACCOUNT_TYPE_OFFSET + 1 + Integer.BYTES + pausableConfig.l(), token2022.l());
    assertThrows(NullPointerException.class, () -> token2022.write(new byte[token2022.l()], 0));

    final var cpiGuard = new CpiGuard(false);
    final var account = new Token2022Account(tokenAccount(address), null, Set.of(cpiGuard));
    assertEquals(TokenAccount.BYTES + 1 + Integer.BYTES + cpiGuard.l(), account.l());
    assertThrows(NullPointerException.class, () -> account.write(new byte[account.l()], 0));
  }

  /// The account half of the same rule: `Account::LEN` bytes leave an empty remainder.
  @Test
  void extensionFreeTokenAccountDecodesAtItsBaseLength() {
    final var address = key(4);
    final var tokenAccount = tokenAccount(address);
    final byte[] data = new byte[TokenAccount.BYTES];
    assertEquals(TokenAccount.BYTES, tokenAccount.write(data, 0));

    final var parsed = Token2022Account.read(address, data);
    assertEquals(tokenAccount, parsed.tokenAccount());
    assertNull(parsed.type());
    assertEquals(Set.of(), parsed.tokenExtensions());

    // 165 in -> 165 out.
    assertEquals(TokenAccount.BYTES, parsed.l());
    final byte[] written = new byte[parsed.l()];
    assertEquals(TokenAccount.BYTES, parsed.write(written, 0));
    assertArrayEquals(data, written);
    assertEquals(parsed, Token2022Account.read(address, written));
  }

  /// The other half of `check_min_len_and_not_multisig`: `input.len() < Account::LEN` is
  /// `InvalidAccountData`. [TokenAccount#read] alone cannot catch it — its option-guarded
  /// fields are only read when the tag says to, so a buffer truncated anywhere after the
  /// close-authority tag decodes as a complete account and would then serialize back out at
  /// the full 165 bytes, inventing the bytes that were missing.
  @Test
  void tokenAccountBuffersShorterThanTheBaseStateAreRejected() {
    final var address = key(4);
    final var noOptions = new TokenAccount(
        address, key(5), key(6), 42L,
        0, null,
        AccountState.Initialized,
        0, 0L, 21L,
        0, null
    );
    final byte[] full = new byte[TokenAccount.BYTES];
    assertEquals(TokenAccount.BYTES, noOptions.write(full, 0));

    for (final int length : new int[]{133, 150, TokenAccount.BYTES - 1}) {
      final var error = assertThrows(
          IllegalArgumentException.class,
          () -> Token2022Account.read(address, Arrays.copyOf(full, length)),
          () -> "a token account of " + length + " bytes must be rejected"
      );
      assertEquals(
          "A token account of " + length + " bytes is malformed: an extension-free token"
              + " account is 165 bytes, and a token account with extensions is at least 166.",
          error.getMessage()
      );
    }

    // The base length itself still decodes.
    assertEquals(noOptions, Token2022Account.read(address, full).tokenAccount());
  }

  /// `adjust_len_for_multisig` — interface/src/extension/mod.rs. An account whose extensions
  /// land it exactly on `Multisig::LEN` is allocated one extra, empty type word, because a
  /// buffer of that length is refused outright. So an account of that natural size is 357
  /// bytes on chain, and this library has to size and write it the same way or it emits
  /// bytes its own reader rejects.
  @Test
  void mintsLandingOnTheMultisigLengthCarryTheProgramsTwoBytePad() {
    final var address = key(1);
    final var pointer = new MetadataPointer(key(63), key(64));
    final var metadata = new TokenMetadata(key(65), key(66), "a".repeat(37), "", "", Map.of());
    final var extensions = new LinkedHashSet<TokenExtension>(List.of(pointer, metadata));
    final var token2022 = new Token2022(mint(address), AccountType.Mint, extensions);

    assertEquals(
        MULTISIG_BYTES,
        MINT_ACCOUNT_TYPE_OFFSET + 1 + (2 * Integer.BYTES) + pointer.l() + metadata.l(),
        "this shape must sit exactly on the multisig length"
    );
    assertEquals(MULTISIG_BYTES + Short.BYTES, token2022.l());

    // A dirty buffer: the pad has to be written, not assumed zero.
    final byte[] dirty = new byte[token2022.l()];
    Arrays.fill(dirty, (byte) 0xFF);
    assertEquals(MULTISIG_BYTES + Short.BYTES, token2022.write(dirty, 0));
    assertEquals(0, dirty[MULTISIG_BYTES], "the pad must be written, not assumed zero");
    assertEquals(0, dirty[MULTISIG_BYTES + 1], "the pad must be written, not assumed zero");

    // The 357-byte on-chain shape reads back equal and re-serializes byte for byte. The
    // comparison starts from a clean buffer because write skips the mint's 83 padding bytes
    // rather than clearing them, so a dirty buffer keeps whatever was in that region.
    final byte[] data = new byte[token2022.l()];
    assertEquals(MULTISIG_BYTES + Short.BYTES, token2022.write(data, 0));
    final var parsed = Token2022.read(address, data);
    assertEquals(token2022, parsed);
    final byte[] again = new byte[parsed.l()];
    assertEquals(data.length, parsed.write(again, 0));
    assertArrayEquals(data, again);
  }

  @Test
  void tokenAccountsLandingOnTheMultisigLengthCarryTheProgramsTwoBytePad() {
    final var address = key(4);
    final var unknown = new UnknownTokenExtension(1_000, new byte[185]);
    final var account = new Token2022Account(
        tokenAccount(address), AccountType.Account, Set.of(unknown)
    );

    assertEquals(
        MULTISIG_BYTES,
        TokenAccount.BYTES + 1 + Integer.BYTES + unknown.l(),
        "this shape must sit exactly on the multisig length"
    );
    assertEquals(MULTISIG_BYTES + Short.BYTES, account.l());

    final byte[] data = new byte[account.l()];
    Arrays.fill(data, (byte) 0xFF);
    assertEquals(MULTISIG_BYTES + Short.BYTES, account.write(data, 0));
    assertEquals(0, data[MULTISIG_BYTES], "the pad must be written, not assumed zero");
    assertEquals(0, data[MULTISIG_BYTES + 1], "the pad must be written, not assumed zero");

    final var parsed = Token2022Account.read(address, data);
    assertEquals(account, parsed);
    final byte[] again = new byte[parsed.l()];
    assertEquals(data.length, parsed.write(again, 0));
    assertArrayEquals(data, again);
  }

  /// `check_min_len_and_not_multisig` rejects `Multisig::LEN` outright: a multisig is never
  /// extensible, so a buffer of that length is ambiguous rather than decodable.
  @Test
  void multisigLengthIsAmbiguousAndRejected() {
    final byte[] data = new byte[MULTISIG_BYTES];
    data[MINT_ACCOUNT_TYPE_OFFSET] = (byte) AccountType.Mint.ordinal();

    final var mintError = assertThrows(
        IllegalArgumentException.class,
        () -> Token2022.read(key(1), data)
    );
    assertEquals(
        "Account data of 355 bytes is the length of a Multisig, which is never extensible.",
        mintError.getMessage()
    );

    final byte[] accountData = new byte[MULTISIG_BYTES];
    accountData[TokenAccount.BYTES] = (byte) AccountType.Account.ordinal();
    final var accountError = assertThrows(
        IllegalArgumentException.class,
        () -> Token2022Account.read(key(1), accountData)
    );
    assertEquals(
        "Account data of 355 bytes is the length of a Multisig, which is never extensible.",
        accountError.getMessage()
    );

    // One byte either side of the ambiguity is an ordinary extensible account.
    final byte[] shorter = new byte[MULTISIG_BYTES - 1];
    shorter[45] = 1; // Mint.is_initialized
    shorter[MINT_ACCOUNT_TYPE_OFFSET] = (byte) AccountType.Mint.ordinal();
    assertEquals(AccountType.Mint, Token2022.read(key(1), shorter).accountType());
    final byte[] longer = new byte[MULTISIG_BYTES + 1];
    longer[45] = 1; // Mint.is_initialized
    longer[MINT_ACCOUNT_TYPE_OFFSET] = (byte) AccountType.Mint.ordinal();
    assertEquals(AccountType.Mint, Token2022.read(key(1), longer).accountType());
  }

  /// An initialized base requires the matching account-type discriminant. In particular,
  /// `Uninitialized` is only legitimate before base initialization, as exercised separately
  /// by Token2022PreInitializationTests; these buffers must not take that lifecycle path.
  @Test
  void accountTypeMustMatchTheInitializedStateBeingRead() {
    for (final var wrong : new AccountType[]{AccountType.Uninitialized, AccountType.Account}) {
      final byte[] data = new byte[MINT_ACCOUNT_TYPE_OFFSET + 1];
      data[45] = 1; // Mint.is_initialized
      data[MINT_ACCOUNT_TYPE_OFFSET] = (byte) wrong.ordinal();
      final var error = assertThrows(
          IllegalArgumentException.class,
          () -> Token2022.read(key(1), data),
          () -> "mint reader must reject " + wrong
      );
      assertEquals("Expected account type Mint, but found " + wrong + '.', error.getMessage());
    }

    for (final var state : new AccountState[]{AccountState.Initialized, AccountState.Frozen}) {
      for (final var wrong : new AccountType[]{AccountType.Uninitialized, AccountType.Mint}) {
        final byte[] data = new byte[TokenAccount.BYTES + 1];
        data[TokenAccount.STATE_OFFSET] = (byte) state.ordinal();
        data[TokenAccount.BYTES] = (byte) wrong.ordinal();
        final var error = assertThrows(
            IllegalArgumentException.class,
            () -> Token2022Account.read(key(1), data),
            () -> state + " account must reject " + wrong
        );
        assertEquals("Expected account type Account, but found " + wrong + '.', error.getMessage());
      }
    }

    // The matching discriminants still parse.
    final byte[] mintData = new byte[MINT_ACCOUNT_TYPE_OFFSET + 1];
    mintData[45] = 1; // Mint.is_initialized
    mintData[MINT_ACCOUNT_TYPE_OFFSET] = (byte) AccountType.Mint.ordinal();
    assertEquals(AccountType.Mint, Token2022.read(key(1), mintData).accountType());

    final byte[] accountData = new byte[TokenAccount.BYTES + 1];
    accountData[TokenAccount.STATE_OFFSET] = (byte) AccountState.Initialized.ordinal();
    accountData[TokenAccount.BYTES] = (byte) AccountType.Account.ordinal();
    assertEquals(AccountType.Account, Token2022Account.read(key(1), accountData).type());
  }

  /// Nothing is written after an `Uninitialized` TLV entry — Rust treats the type-zero word
  /// as the start of trailing padding and every reader stops there. A caller-built set that
  /// happens to iterate `Uninitialized` first must therefore still serialize its initialized
  /// entries; emitting the padding word first silently truncates the account.
  @Test
  void uninitializedIsWrittenAfterEveryInitializedExtension() {
    final var pausableConfig = new PausableConfig(key(70), true);
    final var extensions = new LinkedHashSet<TokenExtension>();
    extensions.add(Uninitialized.INSTANCE);
    extensions.add(pausableConfig);

    final var address = key(1);
    final var token2022 = new Token2022(mint(address), AccountType.Mint, extensions);
    final byte[] mintData = new byte[token2022.l()];
    assertEquals(mintData.length, token2022.write(mintData, 0));
    assertEquals(Set.of(pausableConfig), Token2022.read(address, mintData).tokenExtensions());

    final var cpiGuard = new CpiGuard(true);
    final var accountExtensions = new LinkedHashSet<TokenExtension>();
    accountExtensions.add(Uninitialized.INSTANCE);
    accountExtensions.add(cpiGuard);

    final var account = new Token2022Account(tokenAccount(address), AccountType.Account, accountExtensions);
    final byte[] accountData = new byte[account.l()];
    assertEquals(accountData.length, account.write(accountData, 0));
    assertEquals(Set.of(cpiGuard), Token2022Account.read(address, accountData).tokenExtensions());
  }

  /// The state byte is a `u8` on the wire and only three discriminants exist. Anything else
  /// is corrupt account data, and the top half of the range reads as a negative array index.
  @Test
  void tokenAccountStateByteIsBoundsChecked() {
    for (final var state : AccountState.values()) {
      final byte[] data = new byte[TokenAccount.BYTES];
      data[TokenAccount.STATE_OFFSET] = (byte) state.ordinal();
      assertEquals(state, TokenAccount.read(key(1), data).state());
    }

    for (final int invalid : new int[]{AccountState.values().length, 0x80, 0xFF}) {
      final byte[] data = new byte[TokenAccount.BYTES];
      data[TokenAccount.STATE_OFFSET] = (byte) invalid;
      final var error = assertThrows(
          IllegalArgumentException.class,
          () -> TokenAccount.read(key(1), data),
          () -> "state byte " + invalid + " must be rejected"
      );
      assertEquals("Invalid token account state: " + invalid, error.getMessage());
    }
  }
}
