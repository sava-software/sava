package software.sava.core.accounts.token;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.extensions.*;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

import java.util.*;
import java.util.function.BiFunction;

/// A Token-2022 mint: the [Mint] base state, the account-type discriminant that follows it,
/// and the TLV extensions after that.
///
/// @param mint            the 82-byte base mint state.
/// @param accountType     `null` when the account carries no discriminant — either because
///                        the account is exactly [Mint#BYTES] long with no remainder at all,
///                        the shape of a mint that never had extension space allocated, or
///                        because the byte on the wire is an [AccountType] released after
///                        this library was last synced. With no extensions either, both
///                        re-serialize as the base state alone. [AccountType#Uninitialized]
///                        alongside an uninitialized [Mint] is not a defect: extension
///                        initializers run before `InitializeMint`, so that is what a mint
///                        looks like between the two instructions.
/// @param tokenExtensions the parsed TLV entries, empty when there are none.
public record Token2022(Mint mint,
                        AccountType accountType,
                        Set<TokenExtension> tokenExtensions) implements Serializable {

  private static final int PADDING_AFTER_MINT = 83;
  /// `Multisig::LEN` — interface/src/state.rs. A multisig is never extensible, so a buffer of
  /// exactly this length cannot be told apart from an extended mint or token account.
  static final int MULTISIG_BYTES = 355;

  // Wire names retained for the parser's existing diagnostic messages.
  private static final String[] EXTENSION_NAMES = {
      "Uninitialized",
      "TransferFeeConfig",
      "TransferFeeAmount",
      "MintCloseAuthority",
      "ConfidentialTransferMint",
      "ConfidentialTransferAccount",
      "DefaultAccountState",
      "ImmutableOwner",
      "MemoTransfer",
      "NonTransferable",
      "InterestBearingConfig",
      "CpiGuard",
      "PermanentDelegate",
      "NonTransferableAccount",
      "TransferHook",
      "TransferHookAccount",
      "ConfidentialTransferFeeConfig",
      "ConfidentialTransferFeeAmount",
      "MetadataPointer",
      "TokenMetadata",
      "GroupPointer",
      "TokenGroup",
      "GroupMemberPointer",
      "TokenGroupMember",
      "ConfidentialMintBurn",
      "ScaledUiAmount",
      "Pausable",
      "PausableAccount",
      "PermissionedBurn"
  };

  public static final BiFunction<PublicKey, byte[], Token2022> FACTORY = Token2022::read;

  public static Set<TokenExtension> parseExtensions(final byte[] data, final int offset) {
    final var extensions = new LinkedHashSet<TokenExtension>();
    final var seenExtensionTypes = new HashSet<Integer>();
    // Too few bytes left for a type word ends the walk rather than failing it: "the last byte
    // could be used during a realloc" — try_for_each_tlv_extension_type.
    for (int i = offset; data.length - i >= Short.BYTES; ) {
      final int remaining = data.length - i;
      final int extensionType = ByteUtil.getUInt16LE(data, i);
      if (extensionType == 0) {
        // Trailing zeroed padding, e.g. re-allocated but not yet initialized extension space.
        return extensions.isEmpty() ? Set.of(Uninitialized.INSTANCE) : extensions;
      }
      if (remaining < Integer.BYTES) {
        throw new IllegalArgumentException(String.format(
            "Extension %d has no length: only %d of the %d header bytes remain.",
            extensionType, remaining, Integer.BYTES
        ));
      }
      if (!seenExtensionTypes.add(extensionType)) {
        throw new IllegalArgumentException("Duplicate extension type: " + extensionType);
      }
      i += Short.BYTES;
      final int length = ByteUtil.getUInt16LE(data, i);
      i += Short.BYTES;
      if (i + length > data.length) {
        throw new IndexOutOfBoundsException(String.format(
            "Extension %d claims %d bytes, but only %d remain.",
            extensionType, length, data.length - i
        ));
      }
      final var extensionData = switch (extensionType) {
        case 1 -> TransferFeeConfig.read(data, i);
        case 2 -> TransferFeeAmount.read(data, i);
        case 3 -> MintCloseAuthority.read(data, i);
        case 4 -> ConfidentialTransferMint.read(data, i);
        case 5 -> ConfidentialTransferAccount.read(data, i);
        case 6 -> DefaultAccountState.read(data, i);
        case 7 -> ImmutableOwner.INSTANCE;
        case 8 -> MemoTransfer.read(data, i);
        case 9 -> NonTransferable.INSTANCE;
        case 10 -> InterestBearingConfig.read(data, i);
        case 11 -> CpiGuard.read(data, i);
        case 12 -> PermanentDelegate.read(data, i);
        case 13 -> NonTransferableAccount.INSTANCE;
        case 14 -> TransferHook.read(data, i);
        case 15 -> TransferHookAccount.read(data, i);
        case 16 -> {
          requireLength(extensionType, length, ConfidentialTransferFeeConfig.BYTES);
          yield ConfidentialTransferFeeConfig.read(data, i, i + length);
        }
        case 17 -> {
          requireLength(extensionType, length, ConfidentialTransferFeeAmount.BYTES);
          yield ConfidentialTransferFeeAmount.read(data, i, i + length);
        }
        case 18 -> MetadataPointer.read(data, i);
        // Bound the variable-length Borsh reader to the declared TLV value. Without the
        // slice, malformed string/count fields can consume bytes from the next extension.
        case 19 -> TokenMetadata.read(Arrays.copyOfRange(data, i, i + length), 0);
        case 20 -> GroupPointer.read(data, i);
        case 21 -> TokenGroup.read(data, i);
        case 22 -> GroupMemberPointer.read(data, i);
        case 23 -> TokenGroupMember.read(data, i);
        case 24 -> ConfidentialMintBurn.read(data, i);
        case 25 -> ScaledUiAmountConfig.read(data, i);
        case 26 -> PausableConfig.read(data, i);
        case 27 -> PausableAccount.INSTANCE;
        case 28 -> PermissionedBurnConfig.read(data, i);
        default -> new UnknownTokenExtension(extensionType, Arrays.copyOfRange(data, i, i + length));
      };
      if (extensionData == null) {
        throw new IllegalArgumentException(String.format(
            "Extension %s claims %d bytes, but contains no value.", EXTENSION_NAMES[extensionType], length
        ));
      }
      final int parsedLength = extensionData.l();
      if (!(extensionData instanceof TokenMetadata) && parsedLength != length) {
        throw new IllegalArgumentException(String.format(
            "Extension %s claims %d bytes, expected %d.",
            EXTENSION_NAMES[extensionType], length, parsedLength
        ));
      }
      extensions.add(extensionData);
      i += length;
    }
    return extensions;
  }

  private static void requireLength(final int extensionType,
                                    final int actual,
                                    final int expected) {
    if (actual != expected) {
      throw new IllegalArgumentException(String.format(
          "Extension %s claims %d bytes, expected %d.", EXTENSION_NAMES[extensionType], actual, expected
      ));
    }
  }

  /// Serializes every entry, always emitting the [Uninitialized] padding word last however
  /// the set happens to iterate. A type-zero word is where the TLV walk stops, so an entry
  /// written after it is unreachable to every reader.
  static int writeExtensions(final Set<TokenExtension> extensions,
                             final byte[] data,
                             final int offset) {
    int i = offset;
    boolean uninitialized = false;
    for (final var extension : extensions) {
      if (extension instanceof Uninitialized) {
        uninitialized = true;
      } else {
        i += TokenExtension.write(extension, data, i);
      }
    }
    if (uninitialized) {
      i += TokenExtension.write(Uninitialized.INSTANCE, data, i);
    }
    return i - offset;
  }

  static AccountType parseAccountType(final byte[] data, final int offset) {
    final var accountTypes = AccountType.values();
    final int ordinal = data[offset] & 0xFF;
    return ordinal < accountTypes.length ? accountTypes[ordinal] : null;
  }

  public static Token2022 read(final PublicKey address, final byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }
    rejectMultisigLength(data.length);
    final var mint = Mint.read(address, data);
    int i = Mint.BYTES + PADDING_AFTER_MINT;
    if (data.length == Mint.BYTES) {
      // No remainder after the base state: an extension-free mint, which carries no
      // account-type byte at all.
      return new Token2022(mint, null, Set.of());
    }
    if (data.length <= i) {
      // A non-empty remainder must be long enough for the padding and the account-type byte.
      throw new IllegalArgumentException(String.format(
          "A mint of %d bytes is malformed: an extension-free mint is %d bytes,"
              + " and a mint with extensions is at least %d.",
          data.length, Mint.BYTES, i + 1
      ));
    }
    final var accountType = parseAccountType(data, i);
    requireAccountType(AccountType.Mint, accountType, mint.initialized());
    ++i;
    return new Token2022(mint, accountType, parseExtensions(data, i));
  }

  static void rejectMultisigLength(final int length) {
    if (length == MULTISIG_BYTES) {
      throw new IllegalArgumentException(String.format(
          "Account data of %d bytes is the length of a Multisig, which is never extensible.",
          MULTISIG_BYTES
      ));
    }
  }

  /// `adjust_len_for_multisig` — interface/src/extension/mod.rs. An account whose extensions
  /// would land it exactly on the multisig length is allocated one extra, empty type word,
  /// because a buffer of that length is not readable as an extended account at all. Readers
  /// stop at the zero word, so the two bytes cost nothing but the space.
  static int adjustLengthForMultisig(final int length) {
    return length == MULTISIG_BYTES ? length + Short.BYTES : length;
  }

  /// Writes the empty type word `adjustLengthForMultisig` reserves, when the bytes written so
  /// far land on the multisig length. Returns the total including it.
  static int padLengthForMultisig(final byte[] data, final int offset, final int written) {
    if (written != MULTISIG_BYTES) {
      return written;
    }
    ByteUtil.putInt16LE(data, offset + written, 0);
    return written + Short.BYTES;
  }

  /// The account-type rule has two sides, because extension initializers run before
  /// `InitializeMint` / `InitializeAccount` — interface/src/extension/mod.rs. `unpack` applies
  /// `check_account_type` to an initialized base, so the discriminant must be the one the
  /// reader was asked for. `unpack_uninitialized` requires an uninitialized base and, through
  /// `unpack_uninitialized_type_and_tlv_data`, an [AccountType#Uninitialized] discriminant, so
  /// a zeroed base carrying `Mint` or `Account` is refused. An account type this library does
  /// not know is carried as `null` and is a mismatch on neither side.
  static void requireAccountType(final AccountType expected,
                                 final AccountType parsed,
                                 final boolean initialized) {
    if (parsed == null) {
      return;
    }
    final var required = initialized ? expected : AccountType.Uninitialized;
    if (parsed != required) {
      throw new IllegalArgumentException(String.format(
          "Expected account type %s, but found %s.", required, parsed
      ));
    }
  }

  private boolean baseStateOnly() {
    return accountType == null && tokenExtensions.isEmpty();
  }

  @Override
  public int l() {
    if (baseStateOnly()) {
      return Mint.BYTES;
    }
    int l = Mint.BYTES + PADDING_AFTER_MINT + 1 + (tokenExtensions.size() * Integer.BYTES);
    for (final var extension : tokenExtensions) {
      l += extension.l();
    }
    return adjustLengthForMultisig(l);
  }

  @Override
  public int write(final byte[] data, final int offset) {
    final int mintLength = mint.write(data, offset);
    if (baseStateOnly()) {
      return mintLength;
    }
    int i = offset + mintLength + PADDING_AFTER_MINT;
    data[i] = (byte) accountType.ordinal();
    ++i;
    final int written = (i - offset) + writeExtensions(tokenExtensions, data, i);
    return padLengthForMultisig(data, offset, written);
  }
}
