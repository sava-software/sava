package software.sava.core.accounts.token;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.extensions.*;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

import java.util.*;
import java.util.function.BiFunction;

public record Token2022(Mint mint,
                        AccountType accountType,
                        Set<TokenExtension> tokenExtensions) implements Serializable {

  private static final int PADDING_AFTER_MINT = 83;

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
    for (int i = offset; i < data.length; ) {
      final int extensionType = ByteUtil.getUInt16LE(data, i);
      if (extensionType == 0) {
        // Trailing zeroed padding, e.g. re-allocated but not yet initialized extension space.
        return extensions.isEmpty() ? Set.of(Uninitialized.INSTANCE) : extensions;
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

  static AccountType parseAccountType(final byte[] data, final int offset) {
    final var accountTypes = AccountType.values();
    final int ordinal = data[offset] & 0xFF;
    return ordinal < accountTypes.length ? accountTypes[ordinal] : null;
  }

  public static Token2022 read(final PublicKey address, final byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var mint = Mint.read(address, data);
    int i = Mint.BYTES + PADDING_AFTER_MINT;
    final var accountType = parseAccountType(data, i);
    ++i;
    return new Token2022(mint, accountType, parseExtensions(data, i));
  }

  @Override
  public int l() {
    int l = Mint.BYTES + PADDING_AFTER_MINT + 1 + (tokenExtensions.size() * Integer.BYTES);
    for (final var extension : tokenExtensions) {
      l += extension.l();
    }
    return l;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset + mint.write(data, offset) + PADDING_AFTER_MINT;
    data[i] = (byte) accountType.ordinal();
    ++i;
    for (final var extension : tokenExtensions) {
      i += TokenExtension.write(extension, data, i);
    }
    return i - offset;
  }
}
