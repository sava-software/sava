package software.sava.core.accounts;

import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

final class PublicKeyBytes implements PublicKey {

  static final byte[] PDA_BYTES = "ProgramDerivedAddress".getBytes();

  private final byte[] publicKey;
  private String base58PublicKey;
  private int hashCode;

  PublicKeyBytes(final byte[] publicKey) {
    this.publicKey = publicKey;
  }

  static byte[] createBuffer(final List<byte[]> seeds,
                             final boolean nonce,
                             final PublicKey programId) {
    if (seeds.size() > PublicKey.MAX_SEEDS) {
      throw new IllegalArgumentException(String.format(
          "Maximum number of seeds [%d] exceeded. Given [%d].",
          PublicKey.MAX_SEEDS, seeds.size()
      ));
    }
    int bufLength = nonce ? 1 : 0;
    for (final var seed : seeds) {
      final int len = seed.length;
      if (len > MAX_SEED_LENGTH) {
        throw new IllegalArgumentException(String.format(
            "Seed [%s] exceeds maximum length of [%d].",
            new String(seed), MAX_SEED_LENGTH
        ));
      }
      bufLength += len;
    }

    final byte[] buffer = new byte[bufLength + PUBLIC_KEY_LENGTH + PDA_BYTES.length];

    int from = 0;
    for (final byte[] seed : seeds) {
      System.arraycopy(seed, 0, buffer, from, seed.length);
      from += seed.length;
    }
    from = bufLength;
    from += programId.write(buffer, from);

    System.arraycopy(PDA_BYTES, 0, buffer, from, PDA_BYTES.length);

    return buffer;
  }

  @Override
  public byte[] toByteArray() {
    return this.publicKey;
  }

  @Override
  public byte[] copyByteArray() {
    return Arrays.copyOf(publicKey, publicKey.length);
  }

  @Override
  public int write(final byte[] out, final int off) {
    System.arraycopy(publicKey, 0, out, off, PUBLIC_KEY_LENGTH);
    return PUBLIC_KEY_LENGTH;
  }

  @Override
  public String toBase58() {
    if (this.base58PublicKey == null) {
      this.base58PublicKey = Base58.encode(this.publicKey);
    }
    return this.base58PublicKey;
  }

  @Override
  public String toBase64() {
    return Base64.getEncoder().encodeToString(this.publicKey);
  }

  @Override
  public String toString() {
    return toBase58();
  }

  @Override
  public int compareTo(final PublicKey o) {
    if (o instanceof PublicKeyBytes publicKeyBytes) {
      return Arrays.compare(this.publicKey, 0, PUBLIC_KEY_LENGTH, publicKeyBytes.publicKey, 0, PUBLIC_KEY_LENGTH);
    } else {
      return -o.compareTo(this);
    }
  }

  @Override
  public int hashCode() {
    if (this.hashCode == 0) {
      this.hashCode = Arrays.hashCode(this.publicKey);
    }
    return this.hashCode;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o instanceof PublicKeyBytes _publicKey) {
      return Arrays.equals(this.publicKey, _publicKey.publicKey);
    } else {
      return o != null && o.equals(this);
    }
  }
}
