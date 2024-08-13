package software.sava.core.encoding;

public final class CompactU16Encoding {

  private static final int SIGNED_BYTE_MASK = 0b1000_0000;

  private static final int MAX_TWO_BYTE_LEN = 0x3fff; // 16,383, 2^14-1
  private static final int MAX_VALUE = 0x3ffff; // 262,143

  public static boolean signedByte(final int bite) {
    return (bite & SIGNED_BYTE_MASK) == SIGNED_BYTE_MASK;
  }

  public static byte[] encodeLength(final int len) {
    if (len <= Byte.MAX_VALUE) {
      return new byte[]{(byte) (len & 0x7F)};
    } else if (len <= MAX_TWO_BYTE_LEN) {
      final byte[] out = new byte[2];
      out[0] = (byte) ((len & 0x7F) | SIGNED_BYTE_MASK);
      out[1] = (byte) ((len >> 7) & 0x7F);
      return out;
    } else if (len <= MAX_VALUE) {
      final byte[] out = new byte[3];
      out[0] = (byte) ((len & 0x7F) | SIGNED_BYTE_MASK);
      out[1] = (byte) (((len >> 7) & 0x7F) | SIGNED_BYTE_MASK);
      out[2] = (byte) ((len >> 14) & 0x03);
      return out;
    } else {
      throw new IllegalArgumentException("Max value of an Compact-U16, received " + len);
    }
  }

  public static int encodeLength(final byte[] out, final int off, final int len) {
    if (len <= Byte.MAX_VALUE) {
      out[off] = (byte) (len & 0x7F);
      return 1;
    } else if (len <= MAX_TWO_BYTE_LEN) {
      out[off] = (byte) ((len & 0x7F) | SIGNED_BYTE_MASK);
      out[off + 1] = (byte) ((len >> 7) & 0x7F);
      return 2;
    } else if (len <= MAX_VALUE) {
      out[off] = (byte) ((len & 0x7F) | SIGNED_BYTE_MASK);
      out[off + 1] = (byte) (((len >> 7) & 0x7F) | SIGNED_BYTE_MASK);
      out[off + 2] = (byte) ((len >> 14) & 0x03);
      return 3;
    } else {
      throw new IllegalArgumentException("Max value of an Compact-U16, received " + len);
    }
  }

  public static int getByteLen(final byte[] data, final int offset) {
    if ((data[offset] & SIGNED_BYTE_MASK) == SIGNED_BYTE_MASK) {
      return (data[offset + 1] & SIGNED_BYTE_MASK) == SIGNED_BYTE_MASK ? 3 : 2;
    } else {
      return 1;
    }
  }

  public static int getByteLen(final int len) {
    if (len <= Byte.MAX_VALUE) {
      return 1;
    } else if (len <= MAX_TWO_BYTE_LEN) {
      return 2;
    } else if (len <= MAX_VALUE) {
      return 3;
    } else {
      throw new IllegalArgumentException("Max value of an Compact-U16, received " + len);
    }
  }

  public static int decode(final byte[] out, final int off) {
    final byte b0 = out[off];
    if ((b0 & SIGNED_BYTE_MASK) == SIGNED_BYTE_MASK) {
      final byte b1 = out[off + 1];
      if ((b1 & SIGNED_BYTE_MASK) == SIGNED_BYTE_MASK) {
        return (out[off + 2] << 14) | ((b1 & 0x7F) << 7) | (b0 & 0x7F);
      } else {
        return (b1 << 7) | (b0 & 0x7F);
      }
    } else {
      return b0;
    }
  }

  private CompactU16Encoding() {
  }
}
