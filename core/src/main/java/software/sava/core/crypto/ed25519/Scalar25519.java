package software.sava.core.crypto.ed25519;

import org.bouncycastle.math.raw.Nat;

final class Scalar25519 {

  private static final int[] L = new int[]{1559614445, 1477600026, -1560830762, 350157278, 0, 0, 0, 268435456};

  static void decode(final byte[] var0, final int[] var1) {
    Codec.decode32(var0, 0, var1, 0, 8);
  }

  static void toSignedDigits(final int[] var1) {
    Nat.caddTo(8, ~var1[0] & 1, L, var1);
    Nat.shiftDownBit(8, var1, 1);
  }

  private Scalar25519() {
  }
}

