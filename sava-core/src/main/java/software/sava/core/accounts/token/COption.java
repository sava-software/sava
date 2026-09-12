package software.sava.core.accounts.token;

import software.sava.core.encoding.ByteUtil;

/// The four-byte presence tag ahead of a `COption` value in the SPL Token base states.
///
/// In an account a token program owns the tag is `[0, 0, 0, 0]` or `[1, 0, 0, 0]` and nothing
/// else: the data was all zero when the program received it, and every write either program makes
/// to the slot lands one of those two — through `PodCOption` and the `Pack` encoder in Token-2022
/// (interface/src/pod.rs, interface/src/state.rs), as a 0 or 1 into the first byte with the
/// padding untouched in p-token, the SPL Token program deployed under SIMD-0266
/// (pinocchio/interface/src/state). Refusing a third value enforces that encoding; it mirrors no
/// program check, because the decoders do not agree on one. The `Pack` decoder —
/// `unpack_coption_key` and `unpack_coption_u64` in interface/src/state.rs, which the validator's
/// parsed encoding and Token-2022's `Reallocate` use — returns `InvalidAccountData`; Token-2022's
/// other instructions read the base state zero-copy (`PodStateWithExtensions`) and take a third
/// value as absent where they test for `SOME`, as present where they `unwrap_or` the owner, and
/// as an error only in `WithdrawExcessLamports` on a mint; p-token reads the first byte alone.
/// The tag used to be compared against 1 and every other value read as absent, which decoded
/// bytes no program writes and, on a token account, wrote them back as if one had.
final class COption {

  /// Reads the tag at `offset`, refusing anything but 0 or 1.
  ///
  /// @throws IllegalArgumentException naming `field` and the tag, printed unsigned as the four
  ///                                  little-endian bytes spell it.
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
