package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.token.ExtensionType;
import software.sava.core.serial.Serializable;

public sealed interface TokenExtension extends Serializable permits
    ConfidentialTransferMint, MintCloseAuthority, TokenMetadata, TransferFeeAmount, TransferFeeConfig {

  ExtensionType extensionType();

  default int ordinal() {
    return extensionType().ordinal();
  }
}
