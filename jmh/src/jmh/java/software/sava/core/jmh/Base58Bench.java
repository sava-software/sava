package software.sava.core.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import software.sava.core.encoding.Base58;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Prices the Base58 decode rewrite — limb-based multiply-add over 32-bit
/// limbs, five base-58 digits folded per pass — against the implementation it
/// replaced, which ran one `divMod` pass over the remaining digits per output
/// byte. Both lengths Solana actually decodes are measured:
///
/// - `decodedLength=32` — public keys and block hashes (~44 chars), the shape
///   behind `PublicKey.fromBase58Encoded` and every account key in a parsed
///   RPC response.
/// - `decodedLength=64` — transaction signatures (~88 chars).
///
/// Rows, for each of the String and char[] entry points:
///
/// - `*_old` — the previous implementation, copied verbatim below: codepoint
///   iteration, exception-driven charset validation, and the per-byte divMod.
/// - `*_new` — `Base58.decode` as shipped.
/// - `*_into` — the fixed-size `decode(input, out)` overloads that skip the
///   final right-size-and-copy allocation. `string_into` vs `string_old` is
///   exactly the `PublicKey.fromBase58Encoded` before/after, since that method
///   now decodes directly into the 32-byte key array. `chars_into` is the
///   RPC-parse shape: `applyChars` hands the parser's buffer straight to
///   `decode(char[], from, len, out)`.
///
/// The `bytes_*` rows decode base58 ASCII held in a `byte[]` — the shape a
/// json-iterator byte-span value hook would deliver, skipping the parser's
/// byte-to-char widening copy. `bytes_into` vs `chars_into` bounds what that
/// hook is worth on the decode side.
///
/// Inputs are 1024 uniform random values per length (so ~4 of the 32-byte
/// keys start with a zero byte, matching the real distribution); `@Setup`
/// cross-checks that every variant decodes every input identically. Scores
/// are per decode.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(Base58Bench.INPUTS)
public class Base58Bench {

  static final int INPUTS = 1_024;

  @Param({"32", "64"})
  private int decodedLength;

  private String[] strings;
  private char[][] chars;
  private byte[][] bytes;
  private byte[] out;

  @Setup
  public void setup() {
    final var random = new Random(0x5aba58L + decodedLength);
    strings = new String[INPUTS];
    chars = new char[INPUTS][];
    bytes = new byte[INPUTS][];
    out = new byte[decodedLength];
    final byte[] value = new byte[decodedLength];
    for (int i = 0; i < INPUTS; ++i) {
      random.nextBytes(value);
      final var encoded = Base58.encode(value);
      strings[i] = encoded;
      chars[i] = encoded.toCharArray();
      bytes[i] = encoded.getBytes(StandardCharsets.US_ASCII);
      check(value, oldDecode(encoded), encoded);
      check(value, oldDecode(chars[i], 0, chars[i].length), encoded);
      check(value, Base58.decode(encoded), encoded);
      check(value, Base58.decode(chars[i]), encoded);
      check(value, Base58.decode(bytes[i], 0, bytes[i].length), encoded);
      Arrays.fill(out, (byte) 0);
      Base58.decode(encoded, out);
      check(value, out, encoded);
      Arrays.fill(out, (byte) 0);
      Base58.decode(chars[i], 0, chars[i].length, out);
      check(value, out, encoded);
      Arrays.fill(out, (byte) 0);
      Base58.decode(bytes[i], 0, bytes[i].length, out);
      check(value, out, encoded);
    }
  }

  private static void check(final byte[] expected, final byte[] actual, final String encoded) {
    if (!Arrays.equals(expected, actual)) {
      throw new IllegalStateException("decode variants disagree on " + encoded);
    }
  }

  @Benchmark
  public long string_old() {
    long sum = 0;
    for (final var encoded : strings) {
      sum += oldDecode(encoded)[0];
    }
    return sum;
  }

  @Benchmark
  public long string_new() {
    long sum = 0;
    for (final var encoded : strings) {
      sum += Base58.decode(encoded)[0];
    }
    return sum;
  }

  @Benchmark
  public long string_into() {
    long sum = 0;
    final byte[] out = this.out;
    for (final var encoded : strings) {
      Base58.decode(encoded, out);
      sum += out[0];
    }
    return sum;
  }

  @Benchmark
  public long chars_old() {
    long sum = 0;
    for (final var encoded : chars) {
      sum += oldDecode(encoded, 0, encoded.length)[0];
    }
    return sum;
  }

  @Benchmark
  public long chars_new() {
    long sum = 0;
    for (final var encoded : chars) {
      sum += Base58.decode(encoded)[0];
    }
    return sum;
  }

  @Benchmark
  public long chars_into() {
    long sum = 0;
    final byte[] out = this.out;
    for (final var encoded : chars) {
      Base58.decode(encoded, 0, encoded.length, out);
      sum += out[0];
    }
    return sum;
  }

  @Benchmark
  public long bytes_new() {
    long sum = 0;
    for (final var encoded : bytes) {
      sum += Base58.decode(encoded, 0, encoded.length)[0];
    }
    return sum;
  }

  @Benchmark
  public long bytes_into() {
    long sum = 0;
    final byte[] out = this.out;
    for (final var encoded : bytes) {
      Base58.decode(encoded, 0, encoded.length, out);
      sum += out[0];
    }
    return sum;
  }

  // ---------------------------------------------------------------------
  // The decode implementation this rewrite replaced, copied verbatim from
  // software.sava.core.encoding.Base58 as of commit f874746.
  // ---------------------------------------------------------------------

  private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
  private static final int[] INDEXES = new int[123];

  static {
    Arrays.fill(INDEXES, -1);
    for (int i = 0; i < ALPHABET.length; i++) {
      INDEXES[ALPHABET[i]] = i;
    }
  }

  private static byte divMod(final byte[] number, final int firstDigit, final int base, final int divisor) {
    int remainder = 0;
    for (int i = firstDigit, num; i < number.length; i++) {
      num = remainder * base + ((int) number[i] & 0xFF);
      number[i] = (byte) (num / divisor);
      remainder = num % divisor;
    }
    return (byte) remainder;
  }

  private static byte[] oldDecode(final char[] input, final int from, final int len) {
    if (len == 0) {
      return new byte[0];
    }

    final byte[] input58 = new byte[len];
    for (int i = from, i58 = 0; i58 < len; ++i, ++i58) {
      final int c = Character.codePointAt(input, i);
      try {
        final int digit = INDEXES[c];
        if (digit >= 0) {
          input58[i58] = (byte) digit;
          continue;
        }
      } catch (final ArrayIndexOutOfBoundsException ex) {
        // throw below
      }
      throw new IllegalArgumentException("Illegal character " + c + " at position " + i);
    }

    int zeros = 0;
    while (input58[zeros] == 0) {
      if (++zeros == len) {
        return input58;
      }
    }

    final byte[] decoded = new byte[len];
    int outputStart = len;
    for (int inputStart = zeros; ; ) {
      decoded[--outputStart] = divMod(input58, inputStart, 58, 256);
      if (input58[inputStart] == 0) {
        if (++inputStart == len) {
          break;
        }
      }
    }

    while (outputStart < len && decoded[outputStart] == 0) {
      ++outputStart;
    }

    final int start = outputStart - zeros;
    final byte[] zeroPadded = new byte[len - start];
    System.arraycopy(decoded, start, zeroPadded, 0, zeroPadded.length);
    return zeroPadded;
  }

  private static byte[] oldDecode(final String input) {
    final int len = input.length();
    if (len == 0) {
      return new byte[0];
    }

    final byte[] input58 = new byte[len];
    final var codePoints = input.codePoints().iterator();
    for (int i = 0; i < len; ++i) {
      final int c = codePoints.next();
      try {
        final int digit = INDEXES[c];
        if (digit >= 0) {
          input58[i] = (byte) digit;
          continue;
        }
      } catch (final ArrayIndexOutOfBoundsException ex) {
        // throw below
      }
      throw new IllegalArgumentException("Illegal character " + c + " at position " + i);
    }

    int zeros = 0;
    while (input58[zeros] == 0) {
      if (++zeros == len) {
        return input58;
      }
    }

    final byte[] decoded = new byte[len];
    int outputStart = len;
    for (int inputStart = zeros; ; ) {
      decoded[--outputStart] = divMod(input58, inputStart, 58, 256);
      if (input58[inputStart] == 0) {
        if (++inputStart == len) {
          break;
        }
      }
    }

    while (outputStart < len && decoded[outputStart] == 0) {
      ++outputStart;
    }

    final int start = outputStart - zeros;
    final byte[] zeroPadded = new byte[len - start];
    System.arraycopy(decoded, start, zeroPadded, 0, zeroPadded.length);
    return zeroPadded;
  }
}
