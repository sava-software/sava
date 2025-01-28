package software.sava.core.accounts.vanity;

import software.sava.core.encoding.Base58;

import java.util.Arrays;

public interface Subsequence {

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
    final char[][] charOptions = SubsequenceRecord.generateCharOptions(subsequence, caseSensitive, _1337Numbers, _1337Letters);
    System.out.println("Character options:");
    for (int level = 0; level < 3; ++level) {
      System.out.print("  ");
      for (final char[] options : charOptions) {
        if (options.length > level) {
          System.out.print(options[level]);
        } else {
          System.out.print('_');
        }
        System.out.print(' ');
      }
      System.out.println();
    }

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

  boolean contains(final char[] encoded, final int from);

  String subsequence();

  int length();

  boolean caseSensitive();

  boolean _1337Numbers();

  boolean _1337Letters();

  int numCombinations();
}
