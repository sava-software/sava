package software.sava.core.accounts.token.extensions;

import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

/// A parsed Token-2022 TLV extension entry.
///
/// **Zeroable-option authorities.** Every optional address inside an extension is a
/// `MaybeNull<Address>` on the wire (`OptionalNonZeroPubkey` in older revisions, and
/// `zeroableOption` in the Anchor IDL): absence is encoded as 32 zero bytes in a field that
/// is always present, never as a discriminant or a shorter record. sava decodes that
/// faithfully, so such a component is **never `null`** — an absent authority arrives as a
/// non-null key equal to [software.sava.core.accounts.PublicKey#NONE]. Testing one for
/// `null` is always false; compare it with `PublicKey.NONE` instead. The affected components
/// name the convention individually, and `CONVENTIONS.md` lists it beside the other three
/// ways this library represents absence.
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
