package software.sava.core.crypto.bip32.wallet;

import software.sava.core.crypto.Hash;

/**
 * Marshalling code for HDKeys to base58 representations.
 */
public final class HdKey {

  public static HdKey.Builder build() {
    return new HdKey.Builder();
  }

  private final byte[] version;
  private final int depth;
  private final byte[] fingerprint;
  private final byte[] childNumber;
  private final byte[] chainCode;
  private final byte[] keyData;
  private final byte[] key;

  private HdKey(final byte[] version,
                final int depth,
                final byte[] fingerprint,
                final byte[] childNumber,
                final byte[] chainCode,
                final byte[] keyData,
                final byte[] key) {
    this.version = version;
    this.depth = depth;
    this.fingerprint = fingerprint;
    this.childNumber = childNumber;
    this.chainCode = chainCode;
    this.keyData = keyData;
    this.key = key;
  }

  public byte[] getChainCode() {
    return chainCode;
  }

  public int getDepth() {
    return depth;
  }

  public byte[] getKeyData() {
    return keyData;
  }

  public byte[] getVersion() {
    return version;
  }

  public byte[] getRawKey() {
    return key;
  }

  /**
   * Get the full chain key.  This is not the public/private key for the address.
   *
   * @return full HD Key
   */
  public byte[] getKey() {
    final int len = version.length + 1 + fingerprint.length + childNumber.length + chainCode.length + keyData.length + 4;
    final byte[] key = new byte[len];
    int i = version.length;
    System.arraycopy(version, 0, key, 0, i);
    key[i] = (byte) depth;
    ++i;
    System.arraycopy(fingerprint, 0, key, i, fingerprint.length);
    i += fingerprint.length;
    System.arraycopy(childNumber, 0, key, i, childNumber.length);
    i += childNumber.length;
    System.arraycopy(chainCode, 0, key, i, chainCode.length);
    i += chainCode.length;
    System.arraycopy(keyData, 0, key, i, keyData.length);
    i += keyData.length;
    final byte[] checksum = Hash.sha256Twice(key);
    System.arraycopy(checksum, 0, key, i, 4);
    return key;
  }

  public static final class Builder {

    private byte[] version;
    private int depth;
    private byte[] fingerprint;
    private byte[] childNumber;
    private byte[] chainCode;
    private byte[] keyData;
    private byte[] key;

    private Builder() {
    }

    public HdKey createKey() {
      return new HdKey(version, depth, fingerprint, childNumber, chainCode, keyData, key);
    }

    public void setVersion(final byte[] version) {
      this.version = version;
    }

    public void setDepth(final int depth) {
      this.depth = depth;
    }

    public void setFingerprint(final byte[] fingerprint) {
      this.fingerprint = fingerprint;
    }

    public void setChildNumber(final byte[] childNumber) {
      this.childNumber = childNumber;
    }

    public void setChainCode(final byte[] chainCode) {
      this.chainCode = chainCode;
    }

    public void setKeyData(final byte[] keyData) {
      this.keyData = keyData;
    }


    public void setKey(final byte[] key) {
      this.key = key;
    }
  }
}
