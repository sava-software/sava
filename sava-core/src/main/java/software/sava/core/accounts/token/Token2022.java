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

  public static final BiFunction<PublicKey, byte[], Token2022> FACTORY = Token2022::read;

  public static Set<TokenExtension> parseExtensions(final byte[] data, final int offset) {
    final var extensions = new LinkedHashSet<TokenExtension>();
    final var extensionTypes = ExtensionType.values();
    for (int i = offset; i < data.length; ) {
      final int extensionType = ByteUtil.getInt16LE(data, i);
      if (extensionType == 0) {
        // Trailing zeroed padding, e.g. re-allocated but not yet initialized extension space.
        return extensions.isEmpty() ? Set.of(Uninitialized.INSTANCE) : extensions;
      }
      i += Short.BYTES;
      final int length = ByteUtil.getInt16LE(data, i);
      i += Short.BYTES;
      if (extensionType >= extensionTypes.length) {
        // Extension released after ExtensionType was last synced, pass the raw data back
        // to the user.
        extensions.add(new UnknownTokenExtension(extensionType, Arrays.copyOfRange(data, i, i + length)));
        i += length;
        continue;
      }
      final var type = extensionTypes[extensionType];
      final var extensionData = switch (type) {
        case Uninitialized -> Uninitialized.INSTANCE;
        case TransferFeeConfig -> TransferFeeConfig.read(data, i);
        case TransferFeeAmount -> TransferFeeAmount.read(data, i);
        case MintCloseAuthority -> MintCloseAuthority.read(data, i);
        case ConfidentialTransferMint -> ConfidentialTransferMint.read(data, i);
        case ConfidentialTransferAccount -> ConfidentialTransferAccount.read(data, i);
        case DefaultAccountState -> DefaultAccountState.read(data, i);
        case ImmutableOwner -> ImmutableOwner.INSTANCE;
        case MemoTransfer -> MemoTransfer.read(data, i);
        case NonTransferable -> NonTransferable.INSTANCE;
        case InterestBearingConfig -> InterestBearingConfig.read(data, i);
        case CpiGuard -> CpiGuard.read(data, i);
        case PermanentDelegate -> PermanentDelegate.read(data, i);
        case NonTransferableAccount -> NonTransferableAccount.INSTANCE;
        case TransferHook -> TransferHook.read(data, i);
        case TransferHookAccount -> TransferHookAccount.read(data, i);
        case ConfidentialTransferFeeConfig -> ConfidentialTransferFeeConfig.read(data, i, i + length);
        case ConfidentialTransferFeeAmount -> ConfidentialTransferFeeAmount.read(data, i, i + length);
        case MetadataPointer -> MetadataPointer.read(data, i);
        case TokenMetadata -> TokenMetadata.read(data, i);
        case GroupPointer -> GroupPointer.read(data, i);
        case TokenGroup -> TokenGroup.read(data, i);
        case GroupMemberPointer -> GroupMemberPointer.read(data, i);
        case TokenGroupMember -> TokenGroupMember.read(data, i);
        case ConfidentialMintBurn -> ConfidentialMintBurn.read(data, i);
        case ScaledUiAmount -> ScaledUiAmountConfig.read(data, i);
        case Pausable -> PausableConfig.read(data, i);
        case PausableAccount -> PausableAccount.INSTANCE;
        case PermissionedBurn -> PermissionedBurnConfig.read(data, i);
      };
      if (extensionData != null) {
        extensions.add(extensionData);
      }
      i += length;
    }
    return extensions;
  }

  /// Deprecated with [ExtensionType], use [#parseExtensions] which includes
  /// [UnknownTokenExtension] entries for extensions released after [ExtensionType] was
  /// last synced. Unknown extensions are dropped here as they cannot be keyed by
  /// [ExtensionType].
  @Deprecated(forRemoval = true)
  static Map<ExtensionType, TokenExtension> parseExtensionsMap(final Set<TokenExtension> extensions) {
    final var extensionMap = new EnumMap<ExtensionType, TokenExtension>(ExtensionType.class);
    for (final var extension : extensions) {
      if (!(extension instanceof UnknownTokenExtension)) {
        extensionMap.put(extension.extensionType(), extension);
      }
    }
    return extensionMap;
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

  /// Deprecated with [ExtensionType], use [#tokenExtensions()] and switch on the sealed
  /// [TokenExtension] type. Built dynamically on each call, [UnknownTokenExtension]
  /// entries are dropped since they cannot be keyed by [ExtensionType].
  @Deprecated(forRemoval = true)
  public Map<ExtensionType, TokenExtension> extensions() {
    return parseExtensionsMap(tokenExtensions);
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
