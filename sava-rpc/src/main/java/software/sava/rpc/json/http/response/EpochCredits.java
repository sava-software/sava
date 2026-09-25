package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.JsonException;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

/// One entry of a vote account's credit history, as `getVoteAccounts` returns it.
///
/// Each field is a Solana `u64` carried as its raw bits in a `long` (CONVENTIONS.md, "Unsigned
/// longs and sentinel zeros"): compare and print them unsigned. Agave's Alpenglow migration
/// marker, `AG_MIGRATION_EPOCH_CREDIT` = `(Epoch::MAX, u64::MAX, u64::MAX)` in
/// `agave:votor-messages/src/migration.rs`, therefore reads as three `-1L` and is kept in the
/// history rather than dropped; the signed reader this replaced threw on it.
public record EpochCredits(long epoch, long credits, long previousCredits) {

  static List<EpochCredits> parse(final JsonIterator ji) {
    final var creditHistory = new ArrayList<EpochCredits>();
    while (ji.readArray()) {
      final long epoch = readU64(ji.openArray(), "epoch");
      final long credits = readU64(ji.continueArray(), "credits");
      creditHistory.add(new EpochCredits(epoch, credits, readU64(ji.continueArray(), "previousCredits")));
      ji.closeArray();
    }
    return creditHistory;
  }

  private static final long U64_MAX_DIV_10 = Long.divideUnsigned(-1L, 10L);
  private static final int U64_MAX_MOD_10 = (int) Long.remainderUnsigned(-1L, 10L);

  /// Reads one credit field as the bits of its `u64`, without allocating. The grammar is the one
  /// the signed `readLong` accepted — an optional `-`, ASCII digits with no leading zero, plain or
  /// inside a JSON string — and only the range differs: up to 2^64 − 1 folds into the long's
  /// bits, and a negative parses down to `Long.MIN_VALUE`. A quoted value is decoded as a JSON
  /// string first and must then be the bare digits; the old reader also tolerated whitespace
  /// inside the quotes, which no node writes and which is not kept.
  ///
  /// @throws JsonException for anything else, naming the field and the offending text
  private static long readU64(final JsonIterator ji, final String field) {
    return switch (ji.whatIsNext()) {
      case NUMBER -> ji.applyNumberCharsAsLong(field, EpochCredits::fold);
      case STRING -> ji.applyCharsAsLong(field, EpochCredits::fold);
      default -> throw new JsonException("EpochCredits " + field + " must be an integer: " + ji.currentBuffer());
    };
  }

  private static long fold(final String field, final char[] buf, final int offset, final int len) {
    if (len == 0) {
      throw invalid(field, buf, offset, len, "is empty");
    }
    final int end = offset + len;
    int i = offset;
    final boolean negative = buf[i] == '-';
    if (negative && ++i == end) {
      throw invalid(field, buf, offset, len, "must be an integer");
    }
    if (buf[i] == '0' && end - i > 1) {
      throw invalid(field, buf, offset, len, "has a leading zero");
    }
    long value = 0L;
    for (; i < end; ++i) {
      final int digit = buf[i] - '0';
      if (digit < 0 || digit > 9) {
        throw invalid(field, buf, offset, len, "must be an integer");
      }
      if (Long.compareUnsigned(value, U64_MAX_DIV_10) > 0 || (value == U64_MAX_DIV_10 && digit > U64_MAX_MOD_10)) {
        throw invalid(field, buf, offset, len, "is past 2^64 - 1");
      }
      value = value * 10L + digit;
    }
    if (negative) {
      if (Long.compareUnsigned(value, Long.MIN_VALUE) > 0) {
        throw invalid(field, buf, offset, len, "is below -2^63");
      }
      return -value;
    }
    return value;
  }

  private static JsonException invalid(final String field,
                                       final char[] buf,
                                       final int offset,
                                       final int len,
                                       final String why) {
    return new JsonException("EpochCredits " + field + " '" + new String(buf, offset, len) + "' " + why);
  }
}
