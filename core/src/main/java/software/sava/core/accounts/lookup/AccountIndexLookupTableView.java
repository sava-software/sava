package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;

import java.util.Arrays;

public record AccountIndexLookupTableView(byte[] lookupTable,
                                          int offset,
                                          int index) implements PublicKey {

  @Override
  public byte[] toByteArray() {
    return Arrays.copyOfRange(lookupTable, offset, offset + PUBLIC_KEY_LENGTH);
  }

  @Override
  public int compareTo(final PublicKey o) {
    if (o instanceof AccountIndexLookupTableView view) {
      return Arrays.compare(lookupTable, offset, offset + PUBLIC_KEY_LENGTH, lookupTable, view.offset, view.offset + PUBLIC_KEY_LENGTH);
    } else {
      return Arrays.compare(lookupTable, offset, offset + PUBLIC_KEY_LENGTH, o.toByteArray(), 0, PUBLIC_KEY_LENGTH);
    }
  }

  @Override
  public String toBase58() {
    throw new IllegalStateException("Only to be used for account index lookups.");
  }

  @Override
  public int write(final byte[] out, final int off) {
    toBase58();
    return Integer.MIN_VALUE;
  }

  @Override
  public String toBase64() {
    toBase58();
    return null;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o instanceof PublicKey _publicKey) {
      return Arrays.equals(
          this.lookupTable, offset, offset + PUBLIC_KEY_LENGTH,
          _publicKey.toByteArray(), 0, PUBLIC_KEY_LENGTH
      );
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(toByteArray());
  }
}
