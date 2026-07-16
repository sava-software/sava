package software.sava.core.encoding;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Jazzer entry point exercising the Base58 codec differentially: every decode and encode
/// variant must agree with the String-based reference path, and any input either decodes
/// and re-encodes canonically or is rejected by all of them.
///
/// Deliberately has no Jazzer imports so it compiles with the regular test sources;
/// the raw `byte[]` signature is all the driver needs.
///
/// Run with `./gradlew :sava-core:fuzzBase58 [-PmaxFuzzTime=<seconds>]`.
public final class Base58Fuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
    // decode direction: any input either decodes canonically through every variant, or
    // throws on a non base58 character from every variant.
    final var candidate = new String(data, StandardCharsets.ISO_8859_1);
    final char[] chars = candidate.toCharArray();
    byte[] decoded;
    try {
      decoded = Base58.decode(candidate);
    } catch (final IllegalArgumentException expected) {
      if (Base58.isBase58(candidate)) {
        throw new IllegalStateException("decode rejected valid base58 input: " + candidate, expected);
      }
      if (Base58.nonBase58(candidate) < 0) {
        throw new IllegalStateException("nonBase58 found no illegal character in rejected input: " + candidate);
      }
      expectIllegalArgument(() -> Base58.decode(chars), "decode(char[])", candidate);
      expectIllegalArgument(() -> Base58.decode(data, 0, data.length), "decode(byte[])", candidate);
      decoded = null;
    }
    if (decoded != null) {
      if (!Base58.isBase58(candidate)) {
        throw new IllegalStateException("decode accepted non base58 input: " + candidate);
      }
      if (Base58.nonBase58(candidate) >= 0) {
        throw new IllegalStateException("nonBase58 flagged accepted input: " + candidate);
      }
      final var reEncoded = Base58.encode(decoded);
      if (!reEncoded.equals(candidate)) {
        throw new IllegalStateException(String.format("decode/encode not canonical: %s -> %s", candidate, reEncoded));
      }
      if (!Arrays.equals(decoded, Base58.decode(chars))) {
        throw new IllegalStateException("decode(char[]) disagrees for: " + candidate);
      }
      if (!Arrays.equals(decoded, Base58.decode(data, 0, data.length))) {
        throw new IllegalStateException("decode(byte[]) disagrees for: " + candidate);
      }
      // decode-into variants must fully overwrite a dirty exact-fit buffer
      final byte[] out = new byte[decoded.length];
      Arrays.fill(out, (byte) 0x5A);
      Base58.decode(candidate, out);
      if (!Arrays.equals(decoded, out)) {
        throw new IllegalStateException("decode(String, out) disagrees for: " + candidate);
      }
      Arrays.fill(out, (byte) 0x5A);
      Base58.decode(chars, 0, chars.length, out);
      if (!Arrays.equals(decoded, out)) {
        throw new IllegalStateException("decode(char[], out) disagrees for: " + candidate);
      }
      Arrays.fill(out, (byte) 0x5A);
      Base58.decode(data, 0, data.length, out);
      if (!Arrays.equals(decoded, out)) {
        throw new IllegalStateException("decode(byte[], out) disagrees for: " + candidate);
      }
    }

    // encode direction: encode must not mutate its input, must round trip, and every
    // encode variant must agree.
    final byte[] copy = data.clone();
    final var encoded = Base58.encode(data);
    if (!Arrays.equals(copy, data)) {
      throw new IllegalStateException("encode mutated its input");
    }
    if (!Arrays.equals(copy, Base58.decode(encoded))) {
      throw new IllegalStateException("round trip failed for: " + encoded);
    }
    final byte[] shifted = new byte[data.length + 7];
    System.arraycopy(data, 0, shifted, 3, data.length);
    if (!encoded.equals(Base58.encode(shifted, 3, 3 + data.length))) {
      throw new IllegalStateException("slice encode disagrees for: " + encoded);
    }
    final char[] mutableOut = new char[data.length << 1];
    final int outputStart = Base58.mutableEncode(data.clone(), mutableOut);
    if (!encoded.equals(new String(mutableOut, outputStart, mutableOut.length - outputStart))) {
      throw new IllegalStateException("mutableEncode disagrees for: " + encoded);
    }
    if (data.length > 0) {
      // split encode with a fuzzer-chosen split point, including full consumption in begin
      final int maxLen = 1 + (data[0] & 0x1F);
      final char[] shortEncoded = new char[maxLen << 1];
      final byte[] mutable = data.clone();
      final long offsets = Base58.beginMutableEncode(mutable, maxLen, shortEncoded);
      final int shortStart = (int) offsets;
      final int shortLen = shortEncoded.length - shortStart;
      final char[] full = new char[data.length << 1];
      final int encodedStart = full.length - shortLen;
      final int keyStart = Base58.continueMutableEncode(
          mutable,
          (int) (offsets >>> 48),
          (int) (offsets >>> 32) & 0xFFFF,
          encodedStart,
          full
      );
      final var composed = new String(full, keyStart, encodedStart - keyStart) + new String(shortEncoded, shortStart, shortLen);
      if (!encoded.equals(composed)) {
        throw new IllegalStateException(String.format("begin/continueMutableEncode disagree: %s -> %s", encoded, composed));
      }
    }
  }

  private static void expectIllegalArgument(final Runnable decode, final String variant, final String candidate) {
    try {
      decode.run();
    } catch (final IllegalArgumentException expected) {
      return;
    }
    throw new IllegalStateException(variant + " accepted input rejected by decode(String): " + candidate);
  }

  private Base58Fuzz() {
  }
}
