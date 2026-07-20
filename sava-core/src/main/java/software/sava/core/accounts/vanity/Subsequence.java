package software.sava.core.accounts.vanity;

import software.sava.core.encoding.Base58;

import java.util.Arrays;

public interface Subsequence {

  /// Masks pack one byte per character into a single long, so this is the widest
  /// subsequence that can be represented without characters colliding.
  int MAX_LENGTH = Long.BYTES;

  /// Most alternatives a single character can expand to: itself, its opposite
  /// case, and one leet substitution.
  int MAX_OPTIONS = 3;

  /// @return null if `subsequence` is blank, otherwise a matcher over every
  /// case and leet variant of it.
  /// @throws IllegalArgumentException if `subsequence` contains a non-base58
  /// character — it could never match an encoded address — or is longer than
  /// [#MAX_LENGTH]. Past that width the leading character shifts by 64, which
  /// java evaluates as a shift by zero, so it would collide into the low byte
  /// with the trailing character and match addresses that do not contain the
  /// subsequence at all.
  static Subsequence create(final String subsequence,
                            final boolean caseSensitive,
                            final boolean _1337Numbers,
                            final boolean _1337Letters) {
    if (subsequence.isBlank()) {
      return null;
    }
    final int noneBase58Character = Base58.nonBase58(subsequence);
    if (noneBase58Character >= 0) {
      throw new IllegalArgumentException(String.format("'%c' is not a base58 character.", subsequence.charAt(noneBase58Character)));
    }
    if (subsequence.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(String.format(
          "'%s' is %d characters, the maximum searchable length is %d.",
          subsequence, subsequence.length(), MAX_LENGTH
      ));
    }
    final char[][] charOptions = SubsequenceRecord.generateCharOptions(subsequence, caseSensitive, _1337Numbers, _1337Letters);
    final long[] masks = SubsequenceRecord.generateMasks(charOptions);
    Arrays.sort(masks);

    return new SubsequenceRecord(
        subsequence,
        subsequence.length(),
        caseSensitive,
        _1337Numbers,
        _1337Letters,
        masks
    );
  }

  /// Renders the substitutions each position will accept, one row per
  /// alternative, `_` where a position has fewer alternatives than the widest:
  ///
  /// ```
  ///   s a v a
  ///   S A V A
  ///   5 4 _ 4
  /// ```
  ///
  /// Reporting only — callers that want to show a user what a search will match.
  /// Returned rather than printed so it stays a pure function; this used to be
  /// written straight to `System.out` from [#create], which made it both a
  /// surprise side effect of a factory method and impossible to assert.
  ///
  /// @return [#MAX_OPTIONS] rows separated by newlines, no trailing newline.
  default String charOptionsTable() {
    return SubsequenceRecord.formatCharOptions(
        SubsequenceRecord.generateCharOptions(subsequence(), caseSensitive(), _1337Numbers(), _1337Letters())
    );
  }

  boolean contains(final char[] encoded, final int from);

  String subsequence();

  int length();

  boolean caseSensitive();

  boolean _1337Numbers();

  boolean _1337Letters();

  int numCombinations();
}
