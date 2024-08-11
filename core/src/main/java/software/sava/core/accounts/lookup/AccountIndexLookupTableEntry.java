package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.Base64;

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
  public int write(final byte[] out, final int off) {
    System.arraycopy(publicKey, 0, out, off, PUBLIC_KEY_LENGTH);
    return PUBLIC_KEY_LENGTH;
  }

  @Override
  public String toBase58() {
    return Base58.encode(publicKey);
  }

  @Override
  public String toBase64() {
    return Base64.getEncoder().encodeToString(publicKey);
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
