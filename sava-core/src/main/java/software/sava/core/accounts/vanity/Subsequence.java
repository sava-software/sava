package software.sava.core.accounts.vanity;

import software.sava.core.encoding.Base58;

import java.util.Arrays;

public interface Subsequence {

  /// Longest searchable subsequence: masks pack one character per byte of a `long`.
  int MAX_LENGTH = Long.BYTES;

  /// Most alternatives one character expands to: itself, its other case, and one leet
  /// substitution.
  int MAX_OPTIONS = 3;

  /// Creates a matcher for `subsequence` and the case and leet variants the flags enable.
  ///
  /// @return `null` if `subsequence` is blank
  /// @throws IllegalArgumentException if `subsequence` contains a non-base58 character or is
  ///                                  longer than [#MAX_LENGTH]
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

  /// Renders the alternatives each position accepts, one row per alternative, with `_` where a
  /// position has fewer than [#MAX_OPTIONS]:
  ///
  /// ```
  ///   s a v a
  ///   S A V A
  ///   5 4 _ 4
  /// ```
  ///
  /// @return [#MAX_OPTIONS] rows separated by newlines, no trailing newline
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
