package software.sava.core.borsh;

record Borshed(byte[] data) implements Borsh {

  @Override
  public int l() {
    return data.length;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    System.arraycopy(this.data, 0, data, offset, this.data.length);
    return this.data.length;
  }
}
