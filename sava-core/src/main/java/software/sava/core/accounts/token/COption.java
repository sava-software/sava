package software.sava.core.accounts.token;

import software.sava.core.encoding.ByteUtil;

/// The four-byte presence tag ahead of a `COption` value in the SPL Token base states.
///
/// Only little-endian 0 and 1 are valid: the slot starts zeroed, and Token-2022 (`Pack`,
/// `PodCOption`) and p-token (SIMD-0266) write nothing else. Other tags are refused, as the `Pack`
/// decoder behind the validator's parsed encoding refuses them, even where Token-2022's zero-copy
/// instructions and p-token tolerate them; reading them as absent would decode, and write back,
/// bytes no program writes.
final class COption {

  /// Reads the tag at `offset`.
  ///
  /// @throws IllegalArgumentException if the tag is neither 0 nor 1; the message names `field`.
  static int readTag(final byte[] data, final int offset, final String field) {
    final int tag = ByteUtil.getInt32LE(data, offset);
    if (tag != 0 && tag != 1) {
      throw new IllegalArgumentException(String.format(
          "Invalid %s tag %s: a COption tag is 0 or 1.", field, Integer.toUnsignedString(tag)
      ));
    }
    return tag;
  }

  private COption() {
  }
}
