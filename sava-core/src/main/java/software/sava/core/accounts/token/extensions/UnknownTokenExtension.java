package software.sava.core.accounts.token.extensions;

import java.util.Arrays;

/// An extension released after this library was last synced with the SPL Token-2022
/// program. Exposes the on-chain extension type value via [#type()] and the raw extension
/// data for the user to handle as they see fit.
public record UnknownTokenExtension(int type, byte[] data) implements TokenExtension {

  /// Returns null, no [ExtensionType] constant exists yet for this extension, see
  /// [#type()] for the on-chain extension type value.
  @Override
  public ExtensionType extensionType() {
    return null;
  }

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
