package software.sava.core.accounts.token.extensions;

import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

/// A parsed Token-2022 TLV extension entry.
///
/// An optional address inside an extension (`MaybeNull<Address>`, or `OptionalNonZeroPubkey`)
/// is always present on the wire, all zero when absent, so a parsed one is **never `null`**: an
/// absent one equals [software.sava.core.accounts.PublicKey#NONE]; compare with that instead.
public sealed interface TokenExtension extends Serializable permits
    AccountTokenExtension,
    MintTokenExtension,
    UnknownTokenExtension {

  /// The on-chain extension type value.
  int ordinal();

  static int write(final TokenExtension extension, final byte[] data, final int offset) {
    final int ordinal = extension.ordinal();
    requireUnsignedShort("Extension type", ordinal);
    final int expectedLength = extension.l();
    requireUnsignedShort("Extension value length", expectedLength);

    ByteUtil.putInt16LE(data, offset, ordinal);
    extension.write(data, offset + 4);
    ByteUtil.putInt16LE(data, offset + 2, expectedLength);
    return 4 + expectedLength;
  }

  private static void requireUnsignedShort(final String field, final int value) {
    if (value < 0 || value > 0xFFFF) {
      throw new IllegalArgumentException(field + " exceeds unsigned-short range: " + value);
    }
  }
}
