package software.sava.core.accounts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.interfaces.EdECPublicKey;

import static org.junit.jupiter.api.Assertions.*;

/// Pins `PublicKey.toJavaPublicKey`'s decompression of a 32-byte Ed25519 key into a
/// JDK `EdECPoint`, against **fixed** keys.
///
/// `SignerTest.javaSigVerify` already exercises this path, but it generates a fresh
/// random key pair each run, so whether a mutated sign-bit mask changes the derived
/// point depends on the bits that run happened to draw — the conversion's mutants were
/// killed on some runs and not others, which is the wandering-unkilled-count defect the
/// process contract says to chase rather than re-ratchet past. These cases fix the
/// input, so the arithmetic is asserted rather than sampled.
///
/// The conversion is: reverse the little-endian key to big-endian, take the top byte's
/// high bit as the x-coordinate's parity, then **clear** that bit before reading the
/// remainder as the y coordinate. Clearing it is `& Byte.MAX_VALUE`; anything that
/// instead sets bits (`|`) corrupts y for every input, and anything that skips the mask
/// leaves y a 256-bit number outside the field.
final class JavaPublicKeyConversionTests {

  private static EdECPublicKey javaKeyOf(final String base58) {
    final var converted = PublicKey.toJavaPublicKey(PublicKey.fromBase58Encoded(base58).toByteArray());
    return assertInstanceOf(EdECPublicKey.class, converted);
  }

  /// y is the big-endian key with the top bit cleared, so it must always be below 2^255.
  /// The mask is the only thing keeping it there.
  @Test
  void yCoordinateStaysInsideTheFieldForAKeyWithTheSignBitSet() {
    final var key = javaKeyOf("CiDwVBFgWV9E5MvXWoLgnEgn2hK7rJikbvfWavzAQz3");
    final var y = key.getPoint().getY();

    assertTrue(y.signum() >= 0, "y is unsigned");
    assertTrue(y.bitLength() <= 255, "the sign bit must be cleared, not carried into y — was " + y.bitLength() + " bits");
  }

  /// The exact expected point for a fixed key, recomputed here from the encoded bytes by
  /// the same definition the JDK uses — reversed big-endian, high bit stripped — so a
  /// mutated mask disagrees numerically rather than merely "looks wrong".
  @Test
  void pointMatchesTheDefinitionForAFixedKey() {
    final var base58 = "CiDwVBFgWV9E5MvXWoLgnEgn2hK7rJikbvfWavzAQz3";
    final byte[] encoded = PublicKey.fromBase58Encoded(base58).toByteArray();

    final byte[] reversed = new byte[encoded.length];
    for (int i = 0; i < encoded.length; ++i) {
      reversed[i] = encoded[encoded.length - 1 - i];
    }
    final int top = reversed[0] & 0xFF;
    final boolean expectedXOdd = (top & 0b1000_0000) != 0;
    reversed[0] = (byte) (top & 0b0111_1111);
    final var expectedY = new BigInteger(1, reversed);

    final var point = javaKeyOf(base58).getPoint();
    assertEquals(expectedY, point.getY(), "y must be the big-endian key with only the sign bit removed");
    assertEquals(expectedXOdd, point.isXOdd(), "the stripped high bit is the x parity");
  }

  /// The all-zero system-program key: y is 0 and x is even, so any mask that sets bits
  /// rather than clearing them moves y away from zero and is caught without relying on
  /// which bits a random key happened to have.
  @Test
  void theAllZeroKeyConvertsToTheZeroPoint() {
    final var point = javaKeyOf("11111111111111111111111111111111").getPoint();

    assertEquals(BigInteger.ZERO, point.getY(), "an all-zero key has y = 0; a mask that sets bits cannot produce this");
    assertFalse(point.isXOdd());
  }
}
