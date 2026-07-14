package software.sava.core.accounts.token.extensions;

import java.util.Map;

/// Typed access to the token extensions of a Token-2022 mint or account, each accessor returns null
/// if the extension is not present.
public interface TokenExtensions {

  Map<ExtensionType, TokenExtension> extensions();

  default boolean hasExtension(final ExtensionType type) {
    return extensions().containsKey(type);
  }

  default <T extends TokenExtension> T extension(final ExtensionType type, final Class<T> extensionClass) {
    return extensionClass.cast(extensions().get(type));
  }

  default TransferFeeConfig transferFeeConfig() {
    return extension(ExtensionType.TransferFeeConfig, TransferFeeConfig.class);
  }

  default TransferFeeAmount transferFeeAmount() {
    return extension(ExtensionType.TransferFeeAmount, TransferFeeAmount.class);
  }

  default MintCloseAuthority mintCloseAuthority() {
    return extension(ExtensionType.MintCloseAuthority, MintCloseAuthority.class);
  }

  default ConfidentialTransferMint confidentialTransferMint() {
    return extension(ExtensionType.ConfidentialTransferMint, ConfidentialTransferMint.class);
  }

  default ConfidentialTransferAccount confidentialTransferAccount() {
    return extension(ExtensionType.ConfidentialTransferAccount, ConfidentialTransferAccount.class);
  }

  default DefaultAccountState defaultAccountState() {
    return extension(ExtensionType.DefaultAccountState, DefaultAccountState.class);
  }

  default ImmutableOwner immutableOwner() {
    return extension(ExtensionType.ImmutableOwner, ImmutableOwner.class);
  }

  default MemoTransfer memoTransfer() {
    return extension(ExtensionType.MemoTransfer, MemoTransfer.class);
  }

  default NonTransferable nonTransferable() {
    return extension(ExtensionType.NonTransferable, NonTransferable.class);
  }

  default InterestBearingConfig interestBearingConfig() {
    return extension(ExtensionType.InterestBearingConfig, InterestBearingConfig.class);
  }

  default CpiGuard cpiGuard() {
    return extension(ExtensionType.CpiGuard, CpiGuard.class);
  }

  default PermanentDelegate permanentDelegate() {
    return extension(ExtensionType.PermanentDelegate, PermanentDelegate.class);
  }

  default NonTransferableAccount nonTransferableAccount() {
    return extension(ExtensionType.NonTransferableAccount, NonTransferableAccount.class);
  }

  default TransferHook transferHook() {
    return extension(ExtensionType.TransferHook, TransferHook.class);
  }

  default TransferHookAccount transferHookAccount() {
    return extension(ExtensionType.TransferHookAccount, TransferHookAccount.class);
  }

  default ConfidentialTransferFeeConfig confidentialTransferFeeConfig() {
    return extension(ExtensionType.ConfidentialTransferFeeConfig, ConfidentialTransferFeeConfig.class);
  }

  default ConfidentialTransferFeeAmount confidentialTransferFeeAmount() {
    return extension(ExtensionType.ConfidentialTransferFeeAmount, ConfidentialTransferFeeAmount.class);
  }

  default MetadataPointer metadataPointer() {
    return extension(ExtensionType.MetadataPointer, MetadataPointer.class);
  }

  default TokenMetadata tokenMetadata() {
    return extension(ExtensionType.TokenMetadata, TokenMetadata.class);
  }

  default GroupPointer groupPointer() {
    return extension(ExtensionType.GroupPointer, GroupPointer.class);
  }

  default TokenGroup tokenGroup() {
    return extension(ExtensionType.TokenGroup, TokenGroup.class);
  }

  default GroupMemberPointer groupMemberPointer() {
    return extension(ExtensionType.GroupMemberPointer, GroupMemberPointer.class);
  }

  default TokenGroupMember tokenGroupMember() {
    return extension(ExtensionType.TokenGroupMember, TokenGroupMember.class);
  }

  default ConfidentialMintBurn confidentialMintBurn() {
    return extension(ExtensionType.ConfidentialMintBurn, ConfidentialMintBurn.class);
  }

  default ScaledUiAmountConfig scaledUiAmountConfig() {
    return extension(ExtensionType.ScaledUiAmount, ScaledUiAmountConfig.class);
  }

  default PausableConfig pausableConfig() {
    return extension(ExtensionType.Pausable, PausableConfig.class);
  }

  default PausableAccount pausableAccount() {
    return extension(ExtensionType.PausableAccount, PausableAccount.class);
  }

  default PermissionedBurnConfig permissionedBurnConfig() {
    return extension(ExtensionType.PermissionedBurn, PermissionedBurnConfig.class);
  }
}
