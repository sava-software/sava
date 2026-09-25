package software.sava.core.accounts.token.extensions;

import java.util.Arrays;

/// An extension type this library does not know: its wire type and raw value bytes, written
/// back unchanged.
public record UnknownTokenExtension(int type, byte[] data) implements TokenExtension {

  @Override
  public int ordinal() {
    return type;
  }

  @Override
  public int l() {
    return data.length;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    System.arraycopy(this.data, 0, data, offset, this.data.length);
    return this.data.length;
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof UnknownTokenExtension other
        && type == other.type
        && Arrays.equals(data, other.data);
  }

  @Override
  public int hashCode() {
    return 31 * type + Arrays.hashCode(data);
  }

  @Override
  public String toString() {
    return "UnknownTokenExtension[type=" + type + ']';
  }
}
