package software.sava.core.accounts.token.extensions;

import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

public sealed interface TokenExtension extends Serializable permits
    AccountTokenExtension,
    MintTokenExtension,
    UnknownTokenExtension {

  /// Deprecated with [ExtensionType], switch on the sealed [TokenExtension] type instead.
  /// [#ordinal()] provides the on-chain extension type value.
  @Deprecated(forRemoval = true)
  ExtensionType extensionType();

  /// The on-chain extension type value.
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
