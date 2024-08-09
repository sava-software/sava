package software.sava.core.crypto.ed25519;

import org.bouncycastle.math.raw.Nat;
/**
 * LICENSE
 * Copyright (c) 2000 - 2023 The Legion of the Bouncy Castle Inc. (<a href="https://www.bouncycastle.org">...</a>)
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
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

