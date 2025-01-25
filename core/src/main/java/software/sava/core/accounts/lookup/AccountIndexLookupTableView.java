package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.Base64;

public record AccountIndexLookupTableView(byte[] lookupTable,
                                          int offset,
                                          int index) implements PublicKey {

  @Override
  public byte[] toByteArray() {
    return Arrays.copyOfRange(lookupTable, offset, offset + PUBLIC_KEY_LENGTH);
  }

  @Override
  public byte[] copyByteArray() {
    return toByteArray();
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
  public int write(final byte[] out, final int off) {
    System.arraycopy(lookupTable, offset, out, off, PUBLIC_KEY_LENGTH);
    return PUBLIC_KEY_LENGTH;
  }

  @Override
  public String toBase58() {
    return Base58.encode(lookupTable, offset, offset + PUBLIC_KEY_LENGTH);
  }

  @Override
  public String toBase64() {
    return Base64.getEncoder().encodeToString(toByteArray());
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
