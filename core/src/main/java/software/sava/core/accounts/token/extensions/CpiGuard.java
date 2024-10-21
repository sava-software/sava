package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.token.ExtensionType;

public record CpiGuard(boolean lockCPI) implements TokenExtension {

  public static final int BYTES = 1;

  public static CpiGuard read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    return new CpiGuard(data[offset] == 1);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.CpiGuard;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    data[offset] = (byte) (lockCPI ? 1 : 0);
    return BYTES;
  }
}