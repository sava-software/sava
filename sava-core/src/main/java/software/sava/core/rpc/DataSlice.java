package software.sava.core.rpc;

import java.util.Locale;

/// A window of account data to return in place of the whole account: Agave's `dataSlice`
/// option on `getAccountInfo`, `getMultipleAccounts` and `getProgramAccounts`, and from
/// Agave 4.4 on `accountSubscribe` and `programSubscribe`.
///
/// The node returns `data[offset, offset + length)` clamped to the account's size; everything
/// else in the response, including `space`, still describes the whole account. A zero length
/// returns empty data, which is how to watch an account's lamports or owner without its bytes;
/// to receive the whole account use the overloads that take no slice, or pass a `null` slice where
/// a client method takes one, written `(DataSlice) null` where a bare literal would be ambiguous
/// with another overload. Empty slices canonicalize
/// to offset zero, as Agave does when it deduplicates subscriptions, so two empty slices are
/// equal whatever offsets they were created with.
///
/// @param offset byte offset into the account data
/// @param length number of bytes to return
public record DataSlice(int offset, int length) {

  /// Empty data: the account's metadata without its bytes.
  public static final DataSlice EMPTY = new DataSlice(0, 0);

  /// @throws IllegalArgumentException if offset or length is negative
  public DataSlice {
    if (offset < 0) {
      throw new IllegalArgumentException("dataSlice offset must not be negative: " + offset);
    }
    if (length < 0) {
      throw new IllegalArgumentException("dataSlice length must not be negative: " + length);
    }
    if (length == 0) {
      offset = 0;
    }
  }

  /// The JSON object Agave expects as the `dataSlice` member, offset first like its
  /// `UiDataSliceConfig`.
  public String toJson() {
    return String.format(Locale.ROOT, """
        {"offset":%d,"length":%d}""", offset, length);
  }
}
