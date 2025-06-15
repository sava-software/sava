package software.sava.core.rpc;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;

public sealed interface Filter permits DataSizeFilter, MemCmpFilter {

  int MAX_MEM_COMP_LENGTH = 128;

  static Filter createDataSizeFilter(final int dataSize) {
    return new DataSizeFilter(dataSize);
  }

  static Filter createMemCompFilter(final int offset, final byte[] data) {
    if (data.length > MAX_MEM_COMP_LENGTH) {
      throw new IllegalStateException("Maximum memory compare filter size is 128 bytes.");
    }
    return new MemCmpFilter(offset, Base58.encode(data));
  }

  static Filter createMemCompFilter(final int offset, final PublicKey publicKey) {
    return new MemCmpFilter(offset, publicKey.toBase58());
  }

  static Filter createMemCompFilter(final int offset, final PublicKey publicKey, final PublicKey publicKey2) {
    final byte[] data = new byte[PublicKey.PUBLIC_KEY_LENGTH << 1];
    publicKey.write(data, 0);
    publicKey2.write(data, PublicKey.PUBLIC_KEY_LENGTH);
    return createMemCompFilter(offset, data);
  }

  String toJson();
}
