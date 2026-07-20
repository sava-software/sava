package software.sava.core.accounts.vanity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

final class SubsequenceTests {

  private static SubsequenceRecord create(final String find,
                                          final boolean caseSensitive,
                                          final boolean _1337Numbers,
                                          final boolean _1337Letters) {
    return (SubsequenceRecord) Subsequence.create(find, caseSensitive, _1337Numbers, _1337Letters);
  }

  private static boolean contains(final Subsequence subsequence, final String candidate) {
    return subsequence.contains(candidate.toCharArray(), 0);
  }

  /// Rebuilds the packed mask the way contains() reads it: last character in the
  /// low byte, one byte per character going up.
  private static long packed(final String s) {
    long mask = 0;
    for (int i = 0; i < s.length(); ++i) {
      mask = (mask << Byte.SIZE) | s.charAt(i);
    }
    return mask;
  }

  @Test
  void blankReturnsNull() {
    assertNull(Subsequence.create("", true, false, false));
    assertNull(Subsequence.create("   ", true, false, false));
    assertNull(Subsequence.create("\t\n", true, false, false));
  }

  @Test
  void rejectsNonBase58Characters() {
    // 0, O, I and l are the four base58 exclusions
    for (final String bad : new String[]{"0", "O", "I", "l", "abc0", "0abc", "ab0c"}) {
      final var ex = assertThrows(IllegalArgumentException.class,
          () -> Subsequence.create(bad, true, false, false), bad + " should be rejected");
      assertTrue(ex.getMessage().contains("not a base58 character"), ex.getMessage());
    }
    // and non-alphanumerics generally
    assertThrows(IllegalArgumentException.class, () -> Subsequence.create("a-b", true, false, false));
  }

  /// contains() binary-searches the mask array, so create() must leave it sorted.
  @Test
  void masksAreSorted() {
    final var subsequence = create("sava", false, true, true);
    final long[] masks = subsequence.masks();
    final long[] sorted = masks.clone();
    Arrays.sort(sorted);
    assertArrayEquals(sorted, masks);
  }

  @Test
  void masksAreDistinct() {
    final var subsequence = create("sava", false, true, true);
    final long[] masks = subsequence.masks();
    final var distinct = new HashSet<Long>();
    for (final long mask : masks) {
      assertTrue(distinct.add(mask), "duplicate mask " + Long.toHexString(mask));
    }
    assertEquals(masks.length, subsequence.numCombinations());
  }

  @Test
  void caseSensitiveHasOneCombinationPerCharacter() {
    assertEquals(1, create("a", true, false, false).numCombinations());
    assertEquals(1, create("sava", true, false, false).numCombinations());
    assertEquals(1, create("abcdefgh", true, false, false).numCombinations());
  }

  /// Each alphabetic character contributes both cases when they are both base58.
  @Test
  void caseInsensitiveDoublesPerAlphabeticCharacter() {
    assertEquals(2, create("a", false, false, false).numCombinations());
    assertEquals(4, create("ab", false, false, false).numCombinations());
    assertEquals(16, create("sava", false, false, false).numCombinations());
  }

  /// The base58 exclusions cut the case expansion back to one option: 'i' has no
  /// usable upper case (I), and 'L' has no usable lower case (l).
  @Test
  void caseExpansionSkipsNonBase58Cases() {
    assertEquals(1, create("i", false, false, false).numCombinations());
    assertEquals(1, create("L", false, false, false).numCombinations());
    // 'o' keeps lower case only, since 'O' is excluded
    assertEquals(1, create("o", false, false, false).numCombinations());
  }

  /// Every entry in the letter table, both cases where the table maps them the
  /// same and the two that diverge (G to 6, g to 9). Case sensitive so each
  /// character contributes only itself plus its substitution.
  @Test
  void leetLettersAddDigitSubstitutions() {
    final String[][] table = {
        {"A", "4"}, {"a", "4"},
        {"B", "8"}, {"b", "8"},
        {"E", "3"}, {"e", "3"},
        {"G", "6"}, {"g", "9"},
        {"L", "1"}, {"i", "1"},
        {"S", "5"}, {"s", "5"},
        {"T", "7"},
        {"Z", "2"}, {"z", "2"},
    };
    for (final String[] entry : table) {
      final var subsequence = create(entry[0], true, false, true);
      assertEquals(2, subsequence.numCombinations(), entry[0]);
      assertTrue(contains(subsequence, entry[0]), entry[0] + " should still match itself");
      assertTrue(contains(subsequence, entry[1]), entry[0] + " should match " + entry[1]);
    }
    // G and g diverge, so neither may accept the other's digit
    assertFalse(contains(create("G", true, false, true), "9"));
    assertFalse(contains(create("g", true, false, true), "6"));
    // characters absent from the table gain nothing
    assertEquals(1, create("c", true, false, true).numCombinations());
    assertEquals(1, create("h", true, false, true).numCombinations());
  }

  /// Every entry in the digit table.
  @Test
  void leetNumbersAddLetterSubstitutions() {
    final String[][] table = {
        {"1", "L"}, {"2", "Z"}, {"3", "E"}, {"4", "A"}, {"5", "S"},
        {"6", "G"}, {"7", "T"}, {"8", "B"}, {"9", "g"},
    };
    for (final String[] entry : table) {
      final var subsequence = create(entry[0], true, true, false);
      assertEquals(2, subsequence.numCombinations(), entry[0]);
      assertTrue(contains(subsequence, entry[0]), entry[0] + " should still match itself");
      assertTrue(contains(subsequence, entry[1]), entry[0] + " should match " + entry[1]);
      // and must not pick up a neighbouring row
      assertFalse(contains(subsequence, "9".equals(entry[0]) ? "L" : "g"), entry[0]);
    }
    // without the flag the digit only matches itself
    assertEquals(1, create("1", true, false, false).numCombinations());
    assertFalse(contains(create("1", true, false, false), "L"));
  }

  /// Leet substitution is one-directional per flag: _1337Letters rewrites letters
  /// to digits, _1337Numbers rewrites digits to letters. Neither implies the other.
  @Test
  void leetFlagsAreIndependent() {
    assertFalse(contains(create("a", true, true, false), "4"));
    assertFalse(contains(create("4", true, false, true), "A"));
  }

  /// Every combination the mask table encodes must be matched by contains(), and
  /// the count must be the product of the per-character option counts.
  @Test
  void everyGeneratedCombinationMatches() {
    final char[][] options = SubsequenceRecord.generateCharOptions("sav", false, true, true);
    int expected = 1;
    for (final char[] option : options) {
      expected *= option.length;
    }
    final var subsequence = create("sav", false, true, true);
    assertEquals(expected, subsequence.numCombinations());

    int checked = 0;
    for (final char a : options[0]) {
      for (final char b : options[1]) {
        for (final char c : options[2]) {
          final var candidate = new String(new char[]{a, b, c});
          assertTrue(contains(subsequence, candidate), "should match " + candidate);
          ++checked;
        }
      }
    }
    assertEquals(expected, checked);
  }

  @Test
  void rejectsNonMatchingCandidates() {
    final var subsequence = create("sava", true, false, false);
    assertTrue(contains(subsequence, "sava"));
    assertFalse(contains(subsequence, "SAVA"));
    assertFalse(contains(subsequence, "sav1"));
    assertFalse(contains(subsequence, "xava"));
  }

  /// contains() reads a window of exactly length() characters starting at `from`,
  /// ignoring everything either side.
  @Test
  void matchesAtAnOffsetWindow() {
    final var subsequence = create("sav", true, false, false);
    final char[] encoded = "xxsavxx".toCharArray();
    assertTrue(subsequence.contains(encoded, 2));
    assertFalse(subsequence.contains(encoded, 0));
    assertFalse(subsequence.contains(encoded, 1));
    assertFalse(subsequence.contains(encoded, 3));
  }

  /// The packing is one byte per character, last character in the low byte.
  @Test
  void maskPackingIsOneBytePerCharacterBigEndian() {
    assertEquals(packed("sava"), create("sava", true, false, false).masks()[0]);
    assertEquals(packed("a"), create("a", true, false, false).masks()[0]);
    assertEquals(packed("abcdefgh"), create("abcdefgh", true, false, false).masks()[0]);
  }

  /// Eight characters is the last width that fits a long at one byte each.
  @Test
  void eightCharactersStillRoundTrips() {
    final var subsequence = create("abcdefgh", true, false, false);
    assertTrue(contains(subsequence, "abcdefgh"));
    assertFalse(contains(subsequence, "hbcdefga"));
  }

  /// The reporting table, which used to be printed to stdout from create() and so
  /// could not be asserted at all.
  @Test
  void charOptionsTableRendersEveryAlternative() {
    // 'v' has no leet substitution, so its third row pads
    assertEquals(
        "  s a v a\n"
            + "  S A V A\n"
            + "  5 4 _ 4",
        create("sava", false, false, true).charOptionsTable());
  }

  @Test
  void charOptionsTablePadsRowsWithNoAlternatives() {
    // case sensitive with no leet: only the first row has content
    assertEquals(
        "  a b\n"
            + "  _ _\n"
            + "  _ _",
        create("ab", true, false, false).charOptionsTable());
  }

  @Test
  void charOptionsTableAlwaysRendersMaxOptionRows() {
    assertEquals(3, Subsequence.MAX_OPTIONS);
    for (final var subsequence : new Subsequence[]{
        create("a", true, false, false),
        create("sava", false, true, true),
        create("abcdefgh", false, false, false)}) {
      final var rows = subsequence.charOptionsTable().split("\n", -1);
      assertEquals(Subsequence.MAX_OPTIONS, rows.length, subsequence.subsequence());
      for (final var row : rows) {
        assertTrue(row.startsWith("  "), "row not indented: '" + row + '\'');
        // one character per position, single spaced, after the indent
        assertEquals(subsequence.length() * 2 - 1, row.length() - 2, "row width: '" + row + '\'');
      }
    }
  }

  /// The table reports the same alternatives the matcher accepts — it is derived
  /// from the same generator, not a second description that could drift.
  @Test
  void charOptionsTableAgreesWithWhatMatches() {
    final var subsequence = create("sa", false, true, true);
    final var rows = subsequence.charOptionsTable().split("\n");
    for (final var row : rows) {
      final var cells = row.strip().split(" ");
      if (cells[0].equals("_")) {
        continue;
      }
      final var candidate = String.join("", cells);
      if (candidate.indexOf('_') < 0) {
        assertTrue(contains(subsequence, candidate), "table row should match: " + candidate);
      }
    }
  }

  /// Nine characters would overflow the mask: the leading character shifts by 64,
  /// which java evaluates as a shift by zero, colliding into the low byte with the
  /// trailing one. generateMasks and contains() did it identically, so they agreed
  /// with each other on the wrong answer — "ibcdefgha" matched "abcdefghi" because
  /// 'a' | 'i' == 'i', and both are legitimate base58 strings. Rejected up front
  /// rather than silently reported as a hit.
  @Test
  void rejectsSubsequencesWiderThanTheMask() {
    assertEquals(8, Subsequence.MAX_LENGTH);
    final var ex = assertThrows(IllegalArgumentException.class,
        () -> Subsequence.create("abcdefghi", true, false, false));
    assertTrue(ex.getMessage().contains("maximum searchable length is 8"), ex.getMessage());

    // the boundary itself stays valid
    assertNotNull(Subsequence.create("abcdefgh", true, false, false));
    assertThrows(IllegalArgumentException.class,
        () -> Subsequence.create("abcdefghij", false, true, true));
  }
}
