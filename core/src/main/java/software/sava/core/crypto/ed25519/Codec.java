package software.sava.core.crypto.ed25519;

class Codec {

  static int decode32(final byte[] var0, int var1) {
    int var2 = var0[var1] & 255;
    ++var1;
    var2 |= (var0[var1] & 255) << 8;
    ++var1;
    var2 |= (var0[var1] & 255) << 16;
    ++var1;
    return var2 | var0[var1] << 24;
  }

  static void decode32(final byte[] var0,
                       final int var1,
                       final int[] var2,
                       final int var3,
                       final int var4) {
    for (int var5 = 0; var5 < var4; ++var5) {
      var2[var3 + var5] = decode32(var0, var1 + var5 * 4);
    }
  }

  private Codec() {
  }
}

