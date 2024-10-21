package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.token.ExtensionType;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

public sealed interface TokenExtension extends Serializable permits ConfidentialTransferAccount, ConfidentialTransferFeeAmount, ConfidentialTransferFeeConfig, ConfidentialTransferMint, CpiGuard, DefaultAccountState, GroupMemberPointer, GroupPointer, ImmutableOwner, InterestBearingConfig, MemoTransfer, MetadataPointer, MintCloseAuthority, NonTransferable, NonTransferableAccount, PermanentDelegate, TokenGroup, TokenGroupMember, TokenMetadata, TransferFeeAmount, TransferFeeConfig, TransferHook, TransferHookAccount, Uninitialized {

  ExtensionType extensionType();

  default int ordinal() {
    return extensionType().ordinal();
  }

  static int write(final TokenExtension extension, final byte[] data, final int offset) {
    ByteUtil.putInt16LE(data, offset, extension.ordinal());
    final int length = extension.write(data, offset + 4);
    ByteUtil.putInt16LE(data, offset + 2, length);
    return 4 + length;
  }
}
