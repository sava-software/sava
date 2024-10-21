package software.sava.core.accounts.token.extensions;

public record DefaultAccountState(int state) implements TokenExtension {

  public static final int BYTES = 1;

  public static DefaultAccountState read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    return new DefaultAccountState(data[offset] & 0xFF);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.DefaultAccountState;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    data[offset] = (byte) state;
    return BYTES;
  }
}