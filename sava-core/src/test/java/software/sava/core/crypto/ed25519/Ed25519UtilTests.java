package software.sava.core.crypto.ed25519;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.Test;
import software.sava.core.crypto.Hash;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class Ed25519UtilTests {

  private static final HexFormat HEX = HexFormat.of();

  private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
  // d = -121665/121666 mod p
  private static final BigInteger D = BigInteger.valueOf(-121665)
      .multiply(BigInteger.valueOf(121666).modInverse(P))
      .mod(P);

  private static final String BASE_POINT =
      "5866666666666666666666666666666666666666666666666666666666666666";

  // The eight torsion points, i.e. curve25519-dalek's EIGHT_TORSION_COMPRESSED. All of them
  // decompress, so Solana (and therefore isNotOnCurve) treats them as on-curve, while
  // BouncyCastle's public key validation rejects every one of them up front.
  private static final String[] SMALL_ORDER_POINTS = {
      "0100000000000000000000000000000000000000000000000000000000000000",
      "0000000000000000000000000000000000000000000000000000000000000000",
      "0000000000000000000000000000000000000000000000000000000000000080",
      "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
      "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
      "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa",
      "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
      "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85"
  };

  // https://datatracker.ietf.org/doc/html/rfc8032#section-7.1
  private static final String[][] RFC_8032_VECTORS = {
      {"9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
          "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"},
      {"4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
          "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"},
      {"c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
          "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"},
      {"f5e5767cf153319517630f226876b86c8160cc583bc013744c6bf255f5cc0ee5",
          "278117fc144c72340f67d0f2316e8386ceffbf2b2428c9c51fef7c597f1d426e"},
      {"833fe62409237b9d62ec77587520911e9a759cec1d19755b7da901b96dca3d42",
          "ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf"}
  };

  // Decompression semantics shared by TweetNaCl, curve25519-dalek, and therefore Solana's
  // PDA off-curve check: mask the sign bit, reduce mod p, and decompress y without any
  // canonicity or small-order rejection.
  private static BigInteger decodeY(final byte[] p) {
    final byte[] be = new byte[32];
    for (int i = 0; i < 32; ++i) {
      be[i] = p[31 - i];
    }
    be[0] &= 0x7f;
    return new BigInteger(1, be).mod(P);
  }

  private static boolean referenceOnCurve(final byte[] p) {
    final var y = decodeY(p);
    final var yy = y.multiply(y).mod(P);
    final var u = yy.subtract(BigInteger.ONE).mod(P);
    // d*y^2 + 1 is never 0 mod p because -1/d is not a square, so the inverse always exists
    final var v = D.multiply(yy).add(BigInteger.ONE).mod(P);
    final var xx = u.multiply(v.modInverse(P)).mod(P);
    // Euler's criterion: x^2 is recoverable iff it is 0 or a quadratic residue
    return xx.signum() == 0 || xx.modPow(P.shiftRight(1), P).equals(BigInteger.ONE);
  }

  private static byte[] encodeY(final BigInteger y, final boolean signBit) {
    final byte[] p = new byte[32];
    for (int i = 0; i < 32; ++i) {
      p[i] = (byte) y.shiftRight(i << 3).intValue();
    }
    if (signBit) {
      p[31] |= (byte) 0x80;
    }
    return p;
  }

  @Test
  void isNotOnCurveMatchesReferenceForRandomEncodings() {
    final var random = new Random(0xED25519);
    final byte[] p = new byte[32];
    for (int i = 0; i < 2_048; ++i) {
      random.nextBytes(p);
      assertEquals(!referenceOnCurve(p), Ed25519Util.isNotOnCurve(p), () -> HEX.formatHex(p));
    }
  }

  @Test
  void isNotOnCurveMatchesBouncyCastleForCanonicalEncodings() {
    // BouncyCastle rejects these y values before attempting decompression, so they carry no
    // decompression verdict to compare against; smallOrderPointsAreOnCurve covers them.
    final var rejectedByBouncyCastle = Set.of(
        BigInteger.ZERO,
        BigInteger.ONE,
        P.subtract(BigInteger.ONE),
        decodeY(HEX.parseHex(SMALL_ORDER_POINTS[4])),
        decodeY(HEX.parseHex(SMALL_ORDER_POINTS[6]))
    );
    final var random = new Random(0xB0C);
    final byte[] p = new byte[32];
    for (int i = 0; i < 1_024; ) {
      random.nextBytes(p);
      p[31] &= 0x7f;
      final var candidate = decodeYWithoutReduction(p);
      if (candidate.compareTo(P) >= 0 || rejectedByBouncyCastle.contains(candidate)) {
        continue;
      }
      if ((i & 1) == 1) {
        p[31] |= (byte) 0x80;
      }
      assertEquals(
          Ed25519.validatePublicKeyPartial(p, 0),
          !Ed25519Util.isNotOnCurve(p),
          () -> HEX.formatHex(p)
      );
      ++i;
    }
  }

  private static BigInteger decodeYWithoutReduction(final byte[] p) {
    final byte[] be = new byte[32];
    for (int i = 0; i < 32; ++i) {
      be[i] = p[31 - i];
    }
    be[0] &= 0x7f;
    return new BigInteger(1, be);
  }

  @Test
  void smallOrderPointsAreOnCurve() {
    for (final var hex : SMALL_ORDER_POINTS) {
      final byte[] p = HEX.parseHex(hex);
      assertFalse(Ed25519Util.isNotOnCurve(p), hex);
      assertTrue(referenceOnCurve(p), hex);
      // documents the intentional divergence from BouncyCastle's stricter key validation
      assertFalse(Ed25519.validatePublicKeyPartial(p, 0), hex);
    }
  }

  @Test
  void basePointIsOnCurve() {
    final byte[] p = HEX.parseHex(BASE_POINT);
    assertFalse(Ed25519Util.isNotOnCurve(p));
    assertTrue(Ed25519.validatePublicKeyPartial(p, 0));
  }

  @Test
  void nonCanonicalEncodingsMatchTheirReducedForm() {
    // the 19 encodings in [p, 2^255) decompress as y - p; BouncyCastle rejects all of them
    for (int k = 0; k < 19; ++k) {
      final var reduced = encodeY(BigInteger.valueOf(k), false);
      final boolean expected = Ed25519Util.isNotOnCurve(reduced);
      assertEquals(!referenceOnCurve(reduced), expected);
      for (final boolean signBit : new boolean[]{false, true}) {
        final byte[] nonCanonical = encodeY(P.add(BigInteger.valueOf(k)), signBit);
        assertEquals(expected, Ed25519Util.isNotOnCurve(nonCanonical), () -> HEX.formatHex(nonCanonical));
        assertFalse(Ed25519.validatePublicKeyPartial(nonCanonical, 0));
      }
    }
  }

  @Test
  void signBitDoesNotAffectTheVerdict() {
    final var random = new Random(0x516);
    final byte[] p = new byte[32];
    for (int i = 0; i < 1_024; ++i) {
      random.nextBytes(p);
      p[31] &= 0x7f;
      final boolean verdict = Ed25519Util.isNotOnCurve(p);
      p[31] |= (byte) 0x80;
      assertEquals(verdict, Ed25519Util.isNotOnCurve(p), () -> HEX.formatHex(p));
    }
  }

  @Test
  void boundaryYValuesMatchReference() {
    for (int k = 0; k < 64; ++k) {
      assertMatchesReference(BigInteger.valueOf(k));
      assertMatchesReference(P.subtract(BigInteger.valueOf(k + 1)));
    }
    // walk a single set bit across every y limb boundary
    for (int bit = 0; bit < 255; ++bit) {
      assertMatchesReference(BigInteger.ONE.shiftLeft(bit));
    }
  }

  private static void assertMatchesReference(final BigInteger y) {
    final byte[] p = encodeY(y, false);
    assertEquals(!referenceOnCurve(p), Ed25519Util.isNotOnCurve(p), () -> "y=" + y.toString(16));
  }

  @Test
  void generatePublicKeyMatchesRfc8032TestVectors() {
    final byte[] publicKey = new byte[32];
    for (final var vector : RFC_8032_VECTORS) {
      Ed25519Util.generatePublicKey(HEX.parseHex(vector[0]), publicKey);
      assertArrayEquals(HEX.parseHex(vector[1]), publicKey, vector[0]);
    }
  }

  @Test
  void generatePublicKeyMatchesBouncyCastleForRandomSeeds() {
    final var random = new Random(0x5EED);
    final byte[] seed = new byte[32];
    final byte[] expected = new byte[32];
    final byte[] actual = new byte[32];
    for (int i = 0; i < 512; ++i) {
      random.nextBytes(seed);
      Ed25519.generatePublicKey(seed, 0, expected, 0);
      Ed25519Util.generatePublicKey(seed, actual);
      assertArrayEquals(expected, actual, () -> HEX.formatHex(seed));
      assertFalse(Ed25519Util.isNotOnCurve(actual));
    }
  }

  @Test
  void generatePublicKeyMatchesBouncyCastleForStructuredSeeds() {
    final byte[] seed = new byte[32];
    assertKeygenMatchesBouncyCastle(seed);
    Arrays.fill(seed, (byte) 0xff);
    assertKeygenMatchesBouncyCastle(seed);
    for (int bit = 0; bit < 256; ++bit) {
      Arrays.fill(seed, (byte) 0);
      seed[bit >> 3] = (byte) (1 << (bit & 7));
      assertKeygenMatchesBouncyCastle(seed);
    }
  }

  private static void assertKeygenMatchesBouncyCastle(final byte[] seed) {
    final byte[] expected = new byte[32];
    final byte[] actual = new byte[32];
    Ed25519.generatePublicKey(seed, 0, expected, 0);
    Ed25519Util.generatePublicKey(seed, actual);
    assertArrayEquals(expected, actual, () -> HEX.formatHex(seed));
  }

  @Test
  void generatePublicKeyHonorsOffsets() {
    final var random = new Random(0x0FF5E7);
    final byte[] seed = new byte[32];
    random.nextBytes(seed);
    final byte[] expected = new byte[32];
    Ed25519.generatePublicKey(seed, 0, expected, 0);

    final byte[] in = new byte[7 + 32 + 5];
    random.nextBytes(in);
    System.arraycopy(seed, 0, in, 7, 32);
    final byte[] inBefore = in.clone();

    final byte[] out = new byte[3 + 32 + 9];
    random.nextBytes(out);
    final byte[] outBefore = out.clone();

    Ed25519Util.generatePublicKey(in, 7, out, 3);

    assertArrayEquals(expected, Arrays.copyOfRange(out, 3, 3 + 32));
    assertArrayEquals(inBefore, in);
    assertArrayEquals(Arrays.copyOfRange(outBefore, 0, 3), Arrays.copyOfRange(out, 0, 3));
    assertArrayEquals(
        Arrays.copyOfRange(outBefore, 3 + 32, out.length),
        Arrays.copyOfRange(out, 3 + 32, out.length)
    );
  }

  @Test
  void generatePublicKeyReusesDigestAndScratchBuffers() {
    final var digest = Hash.sha512Digest();
    final byte[] mutablePublicKey = new byte[32];
    final byte[] mutableKeyPair = new byte[64];
    final var random = new Random(0xD16E57);
    final byte[] seed = new byte[11 + 32];
    final byte[] expected = new byte[32];
    final byte[] out = new byte[2 + 32];
    for (int i = 0; i < 64; ++i) {
      random.nextBytes(seed);
      Ed25519.generatePublicKey(seed, 11, expected, 0);
      Ed25519Util.generatePublicKey(digest, seed, 11, out, 2, mutablePublicKey, mutableKeyPair);
      assertArrayEquals(expected, Arrays.copyOfRange(out, 2, 2 + 32), () -> HEX.formatHex(seed));
    }
  }
}
