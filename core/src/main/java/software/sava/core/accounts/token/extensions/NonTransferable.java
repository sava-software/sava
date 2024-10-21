package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.token.ExtensionType;

public record NonTransferable() implements TokenExtension {

  public static final NonTransferable INSTANCE = new NonTransferable();

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.NonTransferable;
  }

  @Override
  public int l() {
    return 0;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    return 0;
  }
}
