package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;

import java.util.Arrays;

public record AccountIndexLookupTableEntry(byte[] publicKey, int index) implements PublicKey {

  public static int lookupAccountIndex(final AccountIndexLookupTableEntry[] lookupTable, final PublicKey publicKey) {
    final int index = Arrays.binarySearch(lookupTable, publicKey);
    if (index < 0) {
      return Integer.MIN_VALUE;
    } else {
      return lookupTable[index].index;
    }
  }

  public static byte lookupAccountIndexOrThrow(final AccountIndexLookupTableEntry[] lookupTable, final PublicKey publicKey) {
    final int index = Arrays.binarySearch(lookupTable, publicKey);
    if (index < 0) {
      throw new IllegalStateException(String.format("Could not find %s in lookup table.", publicKey.toBase58()));
    } else {
      return (byte) lookupTable[index].index;
    }
  }

  @Override
  public byte[] toByteArray() {
    return publicKey;
  }

  @Override
  public int compareTo(final PublicKey o) {
    return Arrays.compare(this.publicKey, o.toByteArray());
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
  public String toString() {
    return String.format("AccountIndexLookupTableEntry[publicKey=[%s, index=%d]", Base58.encode(publicKey), index);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o instanceof PublicKey _publicKey) {
      return Arrays.equals(this.publicKey, _publicKey.toByteArray());
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(publicKey);
  }
}
