package software.sava.core.accounts.vanity;

import software.sava.core.encoding.Base58;

import java.util.Arrays;

import static java.lang.Character.toLowerCase;
import static java.lang.Character.toUpperCase;

record SubsequenceRecord(String subsequence,
                         int length,
                         boolean caseSensitive,
                         boolean _1337Numbers,
                         boolean _1337Letters,
                         long[] masks) implements Subsequence {

  static long[] generateMasks(final char[][] findChars) {
    int numMasks = 0;
    final char[] base = findChars[0];
    if (findChars.length == 1) {
      numMasks = base.length;
    } else {
      for (final int _ : base) {
        numMasks += countMasks(findChars, 1);
      }
    }
    final char[] vertical = new char[findChars.length];
    for (int i = 0; i < vertical.length; ++i) {
      vertical[i] = findChars[i][0];
    }

    System.out.format("""
            
            Searching against %d Base58 character combinations of '%s'...
            
            """,
        numMasks, new String(vertical)
    );

    final long[] masks = new long[numMasks];
    numMasks = 0;
    if (findChars.length == 1) {
      for (final long c : base) {
        masks[numMasks++] = c;
      }
    } else {
      final int shift = (findChars.length - 1) * Byte.SIZE;
      for (final long c : base) {
        numMasks += createMasks(findChars, 1, c << shift, numMasks, masks);
      }
    }
    return masks;
  }

  static int countMasks(final char[][] findChars, final int depth) {
    final int nextDepth = depth + 1;
    if (nextDepth == findChars.length) {
      return findChars[depth].length;
    } else {
      int numMasks = 0;
      for (final int _ : findChars[depth]) {
        numMasks += countMasks(findChars, nextDepth);
      }
      return numMasks;
    }
  }

  static int createMasks(final char[][] findChars,
                         final int depth,
                         final long parentMask,
                         int i,
                         long[] masks) {
    final int nextDepth = depth + 1;
    if (nextDepth == findChars.length) {
      final char[] options = findChars[depth];
      for (final long c : options) {
        masks[i++] = parentMask | c;
      }
      return options.length;
    } else {
      final int shift = (findChars.length - nextDepth) * Byte.SIZE;
      int numMasks = 0;
      int nm;
      for (final long c : findChars[depth]) {
        nm = createMasks(findChars, nextDepth, parentMask | (c << shift), i, masks);
        i += nm;
        numMasks += nm;
      }
      return numMasks;
    }
  }

  static char[][] generateCharOptions(final String find,
                                      final boolean caseSensitive,
                                      final boolean _1337Numbers,
                                      final boolean _1337Letters) {
    final char[][] findChars = new char[find.length()][];
    final char[] storage = new char[3];
    for (int i = 0; i < findChars.length; ++i) {
      findChars[i] = getCharOptions(storage, find.charAt(i), caseSensitive, _1337Numbers, _1337Letters);
    }
    return findChars;
  }

  private static char[] getCharOptions(final char[] storage,
                                       final char c,
                                       final boolean caseSensitive,
                                       final boolean _1337Numbers,
                                       final boolean _1337Letters) {
    int i = 0;
    if (Character.isAlphabetic(c)) {
      if (caseSensitive) {
        storage[i++] = c;
      } else {
        final char lower = toLowerCase(c);
        if (Base58.isBase58(lower)) {
          storage[i++] = lower;
        }
        final char upper = toUpperCase(c);
        if (Base58.isBase58(upper)) {
          storage[i++] = upper;
        }
      }
      // 123456789
      // ABCDEFGHJKLMNPQRSTUVWXYZ
      // abcdefghijkmnopqrstuvwxyz
      if (_1337Letters) {
        switch (c) {
          case 'A', 'a' -> storage[i++] = '4';
          case 'B', 'b' -> storage[i++] = '8';
          case 'E', 'e' -> storage[i++] = '3';
          case 'G' -> storage[i++] = '6';
          case 'g' -> storage[i++] = '9';
          case 'L', 'i' -> storage[i++] = '1';
          case 'S', 's' -> storage[i++] = '5';
          case 'T' -> storage[i++] = '7';
          case 'Z', 'z' -> storage[i++] = '2';
        }
      }
    } else {
      storage[i++] = c;
      if (_1337Numbers) {
        // 123456789
        // ABCDEFGHJKLMNPQRSTUVWXYZ
        // abcdefghijkmnopqrstuvwxyz
        switch (c) {
          case '1' -> storage[i++] = 'L';
          case '2' -> storage[i++] = 'Z';
          case '3' -> storage[i++] = 'E';
          case '4' -> storage[i++] = 'A';
          case '5' -> storage[i++] = 'S';
          case '6' -> storage[i++] = 'G';
          case '7' -> storage[i++] = 'T';
          case '8' -> storage[i++] = 'B';
          case '9' -> storage[i++] = 'g';
        }
      }
    }
    return Arrays.copyOfRange(storage, 0, i);
  }

  @Override
  public boolean contains(final char[] encoded, final int from) {
    int i = from + length;
    long mask = encoded[--i];
    for (int shift = Byte.SIZE; i > from; shift += Byte.SIZE) {
      mask |= ((long) encoded[--i]) << shift;
    }
    return Arrays.binarySearch(masks, mask) >= 0;
  }
}
