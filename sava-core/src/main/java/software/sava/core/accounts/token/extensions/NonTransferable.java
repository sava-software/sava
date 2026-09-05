package software.sava.core.accounts.token.extensions;

public record NonTransferable() implements MintTokenExtension {

  public static final NonTransferable INSTANCE = new NonTransferable();

  @Override
  public int ordinal() {
    return 9;
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
