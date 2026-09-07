package software.sava.core.accounts.token;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.extensions.AccountType;
import software.sava.core.accounts.token.extensions.ImmutableOwner;
import software.sava.core.accounts.token.extensions.MetadataPointer;
import software.sava.core.accounts.token.extensions.TransferFee;
import software.sava.core.accounts.token.extensions.TransferFeeConfig;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Defensive regression tests for client-side decoding during Token-2022 initialization.
/// These wire buffers were independently reproduced with the official interface at
/// `solana-program/token-2022@f92ca12943bbdb9f6037d9694567d90573118bec`, using
/// `PodStateWithExtensionsMut::unpack_uninitialized` and `init_extension`.
/// MetadataPointer and ImmutableOwner initialization leave the base state and account-type
/// byte uninitialized; `init_account_type` runs when the base is initialized later.
///
/// The readers apply Rust's two-sided rule: `unpack` demands the matching discriminant from an
/// initialized base, while `unpack_uninitialized` demands `AccountType::Uninitialized` from an
/// uninitialized one. Both halves are asserted here, the second including its negative — a
/// zeroed base labelled `Mint` or `Account` is refused. Agave's initialized-only `jsonParsed`
/// decoder cannot supply an oracle for this part of the lifecycle, so these buffers are
/// committed as fuzz seeds instead, and `preInitializationBuffersAreCommittedFuzzSeeds` keeps
/// the two copies from drifting.
final class Token2022PreInitializationTests {

  private static byte[] metadataPointerMintBeforeBaseInitialization() {
    // 82-byte zeroed base, 83-byte padding, zero account type, then MetadataPointer TLV.
    final byte[] data = new byte[234];
    data[166] = 18;
    data[168] = 64;
    Arrays.fill(data, 170, 202, (byte) 1); // Some(authority); metadata address is None.
    return data;
  }

  private static byte[] immutableOwnerAccountBeforeBaseInitialization() {
    // 165-byte zeroed base, zero account type, then ImmutableOwner's empty TLV value.
    final byte[] data = new byte[170];
    data[166] = 7;
    return data;
  }

  /// The same lifecycle stage with two initializers already run: `InitializeTransferFeeConfig`
  /// then `InitializeMetadataPointer`, both before `InitializeMint`.
  private static byte[] transferFeeAndPointerMintBeforeBaseInitialization() {
    // 82-byte zeroed base, 83-byte padding, zero account type, then two TLV entries.
    final byte[] data = new byte[346];
    data[166] = 1; // TransferFeeConfig
    data[168] = (byte) TransferFeeConfig.BYTES;
    Arrays.fill(data, 170, 202, (byte) 2); // Some(config authority); withdraw authority is None.
    data[278] = 18; // MetadataPointer
    data[280] = 64;
    Arrays.fill(data, 282, 314, (byte) 1); // Some(authority); metadata address is None.
    return data;
  }

  private static TransferFeeConfig transferFeeConfig() {
    final byte[] authority = new byte[32];
    Arrays.fill(authority, (byte) 2);
    return new TransferFeeConfig(
        PublicKey.createPubKey(authority),
        PublicKey.NONE,
        0L,
        new TransferFee(0L, 0L, 0),
        new TransferFee(0L, 0L, 0)
    );
  }

  private static byte[] seed(final String name) throws IOException {
    try (final var input = Token2022PreInitializationTests.class
        .getResourceAsStream("/fuzz/token2022/" + name)) {
      assertNotNull(input, "missing Token-2022 fuzz seed " + name);
      return input.readAllBytes();
    }
  }

  private static MetadataPointer metadataPointer() {
    final byte[] authority = new byte[32];
    Arrays.fill(authority, (byte) 1);
    return new MetadataPointer(PublicKey.createPubKey(authority), PublicKey.NONE);
  }

  @Test
  void metadataPointerMintDecodesBeforeBaseInitialization() {
    final byte[] data = metadataPointerMintBeforeBaseInitialization();
    final var expectedExtensions = Set.of(metadataPointer());
    assertFalse(Mint.read(PublicKey.NONE, data).initialized());
    assertEquals(expectedExtensions, Token2022.parseExtensions(data, 166));

    final var parsed = assertDoesNotThrow(
        () -> Token2022.read(PublicKey.NONE, data),
        "MetadataPointer initialization produces a valid mint with an uninitialized base and type"
    );
    assertFalse(parsed.mint().initialized());
    assertEquals(AccountType.Uninitialized, parsed.accountType());
    assertEquals(expectedExtensions, parsed.tokenExtensions());
    assertEquals(data.length, parsed.l());
    final byte[] written = new byte[parsed.l()];
    assertEquals(data.length, parsed.write(written, 0));
    assertArrayEquals(data, written, "decoding must preserve the pre-initialization wire state");
  }

  @Test
  void immutableOwnerAccountDecodesBeforeBaseInitialization() {
    final byte[] data = immutableOwnerAccountBeforeBaseInitialization();
    final var expectedExtensions = Set.of(ImmutableOwner.INSTANCE);
    assertEquals(AccountState.Uninitialized, TokenAccount.read(PublicKey.NONE, data).state());
    assertEquals(expectedExtensions, Token2022.parseExtensions(data, 166));

    final var parsed = assertDoesNotThrow(
        () -> Token2022Account.read(PublicKey.NONE, data),
        "ImmutableOwner initialization produces a valid account with an uninitialized base and type"
    );
    assertEquals(AccountState.Uninitialized, parsed.tokenAccount().state());
    assertEquals(AccountType.Uninitialized, parsed.type());
    assertEquals(expectedExtensions, parsed.tokenExtensions());
    assertEquals(data.length, parsed.l());
    final byte[] written = new byte[parsed.l()];
    assertEquals(data.length, parsed.write(written, 0));
    assertArrayEquals(data, written, "decoding must preserve the pre-initialization wire state");
  }

  @Test
  void metadataPointerMintStillDecodesAfterBaseInitialization() {
    final byte[] data = metadataPointerMintBeforeBaseInitialization();
    data[45] = 1; // Mint.is_initialized
    data[165] = 1; // AccountType::Mint, written by init_account_type

    final var parsed = Token2022.read(PublicKey.NONE, data);
    assertTrue(parsed.mint().initialized());
    assertEquals(AccountType.Mint, parsed.accountType());
    assertEquals(Set.of(metadataPointer()), parsed.tokenExtensions());
  }

  @Test
  void immutableOwnerAccountStillDecodesAfterBaseInitializationAndFreezing() {
    for (final var state : new AccountState[]{AccountState.Initialized, AccountState.Frozen}) {
      final byte[] data = immutableOwnerAccountBeforeBaseInitialization();
      data[108] = (byte) state.ordinal();
      data[165] = 2; // AccountType::Account, written by init_account_type

      final var parsed = Token2022Account.read(PublicKey.NONE, data);
      assertEquals(state, parsed.tokenAccount().state());
      assertEquals(AccountType.Account, parsed.type());
      assertEquals(Set.of(ImmutableOwner.INSTANCE), parsed.tokenExtensions());
    }
  }
  /// `init_extension` writes one TLV entry per initializer, so more than one can land before
  /// `InitializeMint` does. The chain has to decode and re-serialize as it stands.
  @Test
  void aMintWithSeveralInitializedExtensionsDecodesBeforeBaseInitialization() {
    final byte[] data = transferFeeAndPointerMintBeforeBaseInitialization();
    final var expectedExtensions = new LinkedHashSet<>(
        List.of(transferFeeConfig(), metadataPointer())
    );

    final var parsed = Token2022.read(PublicKey.NONE, data);
    assertFalse(parsed.mint().initialized());
    assertEquals(AccountType.Uninitialized, parsed.accountType());
    assertEquals(expectedExtensions, parsed.tokenExtensions());
    assertEquals(data.length, parsed.l());
    final byte[] written = new byte[parsed.l()];
    assertEquals(data.length, parsed.write(written, 0));
    assertArrayEquals(data, written, "decoding must preserve the pre-initialization wire state");
  }

  /// The negative half of `unpack_uninitialized`: it refuses a base that is already
  /// initialized, and its type check refuses anything but `AccountType::Uninitialized`. Read
  /// from the other direction, a zeroed base carrying a real discriminant is a state no
  /// instruction sequence produces, and naming both types is what tells the two lifecycle
  /// stages apart in the message.
  @Test
  void anUninitializedBaseLabelledWithARealAccountTypeIsRejected() {
    final byte[] mintData = metadataPointerMintBeforeBaseInitialization();
    mintData[165] = (byte) AccountType.Mint.ordinal();
    final var mintError = assertThrows(
        IllegalArgumentException.class,
        () -> Token2022.read(PublicKey.NONE, mintData)
    );
    assertEquals("Expected account type Uninitialized, but found Mint.", mintError.getMessage());

    final byte[] accountData = immutableOwnerAccountBeforeBaseInitialization();
    accountData[165] = (byte) AccountType.Account.ordinal();
    final var accountError = assertThrows(
        IllegalArgumentException.class,
        () -> Token2022Account.read(PublicKey.NONE, accountData)
    );
    assertEquals(
        "Expected account type Uninitialized, but found Account.", accountError.getMessage()
    );

    // An account type released after the last sync stays tolerated on this side of the rule
    // too, exactly as it is on the initialized side.
    final byte[] future = metadataPointerMintBeforeBaseInitialization();
    future[165] = (byte) 200;
    assertNull(Token2022.read(PublicKey.NONE, future).accountType());
  }

  /// These synthetic lifecycle buffers are committed fuzz seeds. Extension initialization
  /// must precede base initialization, but the program does not require both to occur in one
  /// transaction. The current mainnet corpus has no such state, and the node's `jsonParsed`
  /// decoder excludes uninitialized bases. Comparing the buffers with their seeds prevents
  /// the two copies from drifting apart.
  @Test
  void preInitializationBuffersAreCommittedFuzzSeeds() throws IOException {
    assertArrayEquals(
        metadataPointerMintBeforeBaseInitialization(),
        seed("pre_init_metadata_pointer_mint")
    );
    assertArrayEquals(
        immutableOwnerAccountBeforeBaseInitialization(),
        seed("pre_init_immutable_owner_account")
    );
    assertArrayEquals(
        transferFeeAndPointerMintBeforeBaseInitialization(),
        seed("pre_init_transfer_fee_and_pointer_mint")
    );
  }
}
