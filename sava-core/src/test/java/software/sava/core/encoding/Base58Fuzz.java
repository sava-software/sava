package software.sava.core.encoding;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Jazzer entry point exercising both directions of the Base58 codec.
///
/// Deliberately has no Jazzer imports so it compiles with the regular test sources;
/// the raw `byte[]` signature is all the driver needs.
///
/// Run with `./gradlew :sava-core:fuzzBase58 [-PmaxFuzzTime=<seconds>]`.
public final class Base58Fuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
    // decode direction: any input either decodes and re-encodes canonically, or throws on a non base58 character.
    final var candidate = new String(data, StandardCharsets.ISO_8859_1);
    try {
      final byte[] decoded = Base58.decode(candidate);
      if (!Base58.isBase58(candidate)) {
        throw new IllegalStateException("decode accepted non base58 input: " + candidate);
      }
      final var reEncoded = Base58.encode(decoded);
      if (!reEncoded.equals(candidate)) {
        throw new IllegalStateException(String.format("decode/encode not canonical: %s -> %s", candidate, reEncoded));
      }
      final byte[] out = new byte[decoded.length];
      Base58.decode(candidate, out);
      if (!Arrays.equals(decoded, out)) {
        throw new IllegalStateException("decode variants disagree for: " + candidate);
      }
    } catch (final IllegalArgumentException expected) {
      if (Base58.isBase58(candidate)) {
        throw new IllegalStateException("decode rejected valid base58 input: " + candidate, expected);
      }
    }

    // encode direction: encode must not mutate its input and must round trip.
    final byte[] copy = data.clone();
    final var encoded = Base58.encode(data);
    if (!Arrays.equals(copy, data)) {
      throw new IllegalStateException("encode mutated its input");
    }
    if (!Arrays.equals(copy, Base58.decode(encoded))) {
      throw new IllegalStateException("round trip failed for: " + encoded);
    }
  }

  private Base58Fuzz() {
  }
}
