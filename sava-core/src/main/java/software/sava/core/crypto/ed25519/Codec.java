package software.sava.core.crypto.ed25519;

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
final class Codec {

  static int decode32(final byte[] bs, int off) {
    int var2 = bs[off] & 255;
    ++off;
    var2 |= (bs[off] & 255) << 8;
    ++off;
    var2 |= (bs[off] & 255) << 16;
    ++off;
    return var2 | bs[off] << 24;
  }

  static void decode32(final byte[] bs,
                       final int bsOff,
                       final int[] n,
                       final int nOff,
                       final int nLen) {
    for (int var5 = 0; var5 < nLen; ++var5) {
      n[nOff + var5] = decode32(bs, bsOff + var5 * 4);
    }
  }

  private Codec() {
  }
}

