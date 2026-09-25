package software.sava.rpc.json.http.response;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonException;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The credit fields are `u64` on the wire and their raw bits in a `long` here (CONVENTIONS.md,
/// "Unsigned longs and sentinel zeros"). Agave's Alpenglow migration marker,
/// `(Epoch::MAX, u64::MAX, u64::MAX)` from `agave:votor-messages/src/migration.rs`, is the value
/// that made the signed reader throw `value is too large for long` on every `getVoteAccounts`
/// answer carrying it, for as long as the marker sits inside the RPC's credit window.
///
/// The grammar the old reader accepted is pinned alongside the new range: signed values, quoted
/// bare digits, no leading zero. One tolerance was dropped on purpose and is pinned as a
/// rejection: whitespace inside the quotes.
final class EpochCreditsTests {

  private static final String U64_MAX = "18446744073709551615";

  private static List<EpochCredits> parse(final String json) {
    return EpochCredits.parse(JsonIterator.parse(json));
  }

  private static JsonException rejects(final String json) {
    return assertThrows(JsonException.class, () -> parse(json));
  }

  @Test
  void migrationMarkerParsesAsUnsignedMaximum() {
    final var history = parse("[[7,123,100],[" + U64_MAX + "," + U64_MAX + "," + U64_MAX + "],[8,125,123]]");
    assertEquals(List.of(
        new EpochCredits(7, 123, 100),
        new EpochCredits(-1L, -1L, -1L),
        new EpochCredits(8, 125, 123)
    ), history);
    assertEquals(U64_MAX, Long.toUnsignedString(history.get(1).credits()));
  }

  @Test
  void upperHalfOfU64KeepsItsBits() {
    // 2^63 exactly, the first value a signed reader refuses, is Long.MIN_VALUE as bits.
    final var history = parse("[[9223372036854775808,9223372036854775809,1]]");
    assertEquals(Long.MIN_VALUE, history.getFirst().epoch());
    assertEquals(Long.MIN_VALUE + 1, history.getFirst().credits());
    assertEquals(1, history.getFirst().previousCredits());
    // The last digit decides at the boundary: 18446744073709551610 through ...615 fit.
    assertEquals(-6L, parse("[[18446744073709551610,0,0]]").getFirst().epoch());
  }

  @Test
  void signedValuesStillParseAsBefore() {
    assertEquals(List.of(new EpochCredits(-1, -5, 0)), parse("[[-1,-5,0]]"));
    assertEquals(List.of(new EpochCredits(Long.MIN_VALUE, Long.MAX_VALUE, 0)),
        parse("[[-9223372036854775808,9223372036854775807,0]]"));
    assertEquals(List.of(new EpochCredits(0, 0, 0)), parse("[[-0,0,0]]"));
  }

  @Test
  void quotedBareDigitsStillParseAsBefore() {
    assertEquals(List.of(new EpochCredits(7, 8, 9)), parse("[[\"7\",\"8\",\"9\"]]"));
    assertEquals(List.of(new EpochCredits(-1L, -2, 3)), parse("[[\"" + U64_MAX + "\",\"-2\",3]]"));
    // A quoted value is a JSON string first, so an escaped ASCII digit is that digit.
    assertEquals(List.of(new EpochCredits(7, 8, 9)), parse("[[\"\\u0037\",8,9]]"));
  }

  @Test
  void emptyHistoryParses() {
    assertTrue(parse("[]").isEmpty());
  }

  @Test
  void valuesOutsideU64AndBelowMinLongAreRejected() {
    final var past = rejects("[[1,18446744073709551616,0]]");
    assertTrue(past.getMessage().contains("credits '18446744073709551616' is past"), past.getMessage());
    rejects("[[1,18446744073709551620,0]]");
    rejects("[[1,99999999999999999999,0]]");
    rejects("[[\"" + U64_MAX + "0\",0,0]]");
    final var below = rejects("[[1,0,-9223372036854775809]]");
    assertTrue(below.getMessage().contains("previousCredits '-9223372036854775809' is below"), below.getMessage());
    rejects("[[-18446744073709551615,0,0]]");
  }

  @Test
  void leadingZerosAreRejectedAsBefore() {
    rejects("[[07,8,9]]");
    rejects("[[7,-07,9]]");
    rejects("[[7,8,\"007\"]]");
    rejects("[[7,8,\"00\"]]");
    // A zero-padded marker is not the marker.
    final var padded = rejects("[[\"0" + U64_MAX + "\",0,0]]");
    assertTrue(padded.getMessage().contains("has a leading zero"), padded.getMessage());
    assertEquals(List.of(new EpochCredits(0, 0, 0)), parse("[[0,\"0\",\"-0\"]]"));
  }

  @Test
  void nonDigitsAreRejected() {
    // The Java parsers would accept a leading '+' and non-ASCII digits; the wire never carries them.
    rejects("[[\"+7\",8,9]]");
    rejects("[[+7,8,9]]");
    rejects("[[7,\"8a\",9]]");
    rejects("[[7,8,\"\\u0661\"]]");
    rejects("[[1.5,8,9]]");
    rejects("[[1e3,8,9]]");
    // The characters on either side of the ASCII digit range.
    rejects("[[\"1/2\",8,9]]");
    rejects("[[\"1:2\",8,9]]");
    final var empty = rejects("[[\"\",8,9]]");
    assertTrue(empty.getMessage().contains("epoch '' is empty"), empty.getMessage());
    final var bareSign = rejects("[[\"-\",8,9]]");
    assertTrue(bareSign.getMessage().contains("epoch '-' must be an integer"), bareSign.getMessage());
    // Dropped on purpose: whitespace inside the quotes.
    rejects("[[\" 7\",8,9]]");
    rejects("[[\"7 \",8,9]]");
  }

  @Test
  void nullAndNonNumericValuesAreRejectedNamingTheField() {
    final var epoch = rejects("[[null,8,9]]");
    assertTrue(epoch.getMessage().contains("EpochCredits epoch must be an integer"), epoch.getMessage());
    final var credits = rejects("[[7,true,9]]");
    assertTrue(credits.getMessage().contains("EpochCredits credits must be an integer"), credits.getMessage());
    final var previous = rejects("[[7,8,{}]]");
    assertTrue(previous.getMessage().contains("EpochCredits previousCredits must be an integer"), previous.getMessage());
  }
}
