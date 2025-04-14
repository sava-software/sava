package software.sava.core.accounts.token.extensions;

import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

public sealed interface TokenExtension extends Serializable permits AccountTokenExtension, MintTokenExtension {

  ExtensionType extensionType();

  default int ordinal() {
    return extensionType().ordinal();
  }

  static int write(TokenExtension extension, byte[] data, int offset) {
    ByteUtil.putInt16LE(data, offset, extension.ordinal());
    final int length = extension.write(data, offset + 4);
    ByteUtil.putInt16LE(data, offset + 2, length);
    return 4 + length;
  }
}
