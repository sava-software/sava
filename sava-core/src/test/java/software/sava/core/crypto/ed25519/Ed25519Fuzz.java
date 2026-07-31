package software.sava.core.crypto.ed25519;

import org.bouncycastle.math.ec.rfc8032.Ed25519;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/// Jazzer entry point for the ed25519 primitives behind PDA derivation and key
/// generation. The limb arithmetic in [Ed25519Util] is branch-poor, so coverage
/// guidance contributes little here; the target's value is volume — a rare-carry or
/// signed-digit-recoding bug has no branch signature and lives at measures no fixture
/// or seeded property loop reaches. Everything is differential ("when one thing has
/// two representations, fuzz the differential"): a wrong verdict from
/// [Ed25519Util#isNotOnCurve] silently changes derived PDAs, so crash-only fuzzing
/// would see nothing.
///
/// The first 32 bytes are one point encoding and one keygen seed at once:
/// - curve check: [Ed25519Util#isNotOnCurve] against BouncyCastle's partial key
///   validation where BouncyCastle has a verdict, and against a BigInteger
///   decompression reference on the inputs BouncyCastle rejects up front
///   (non-canonical y and its small-order reject set) — combined, the oracle is
///   total over the input space, and the sign bit must never affect the verdict.
/// - keygen: [Ed25519Util#generatePublicKey] against BouncyCastle's, which drives
///   [Scalar25519]'s signed-digit recoding and [Codec] under the same agreement
///   oracle; the produced key must itself decompress.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :sava-core:fuzzEd25519 [-PmaxFuzzTime=<seconds>]`.
public final class Ed25519Fuzz {

  private static final HexFormat HEX = HexFormat.of();

  private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
  // d = -121665/121666 mod p
  private static final BigInteger D = BigInteger.valueOf(-121665)
      .multiply(BigInteger.valueOf(121666).modInverse(P))
      .mod(P);

  // BouncyCastle's validatePublicKeyPartial rejects these y values before attempting
  // decompression, so they carry no verdict to compare against; the BigInteger
  // reference below owns them instead. The last two are the order-8 torsion y's.
  private static final Set<BigInteger> REJECTED_BY_BOUNCY_CASTLE = Set.of(
      BigInteger.ZERO,
      BigInteger.ONE,
      P.subtract(BigInteger.ONE),
      decodeYWithoutReduction(HEX.parseHex("c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a")),
      decodeYWithoutReduction(HEX.parseHex("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05"))
  );

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 32) {
      return;
    }
    final byte[] p = Arrays.copyOf(data, 32);
    fuzzCurveCheck(p);
    fuzzKeygen(p);
  }

  private static void fuzzCurveCheck(final byte[] p) {
    final boolean onCurve = !Ed25519Util.isNotOnCurve(p);

    // dalek semantics mask the sign bit before decompressing y, so it can never
    // change the verdict
    final byte[] flipped = p.clone();
    flipped[31] ^= (byte) 0x80;
    if (Ed25519Util.isNotOnCurve(flipped) == onCurve) {
      throw new AssertionError("sign bit changed the verdict for " + HEX.formatHex(p));
    }

    final var y = decodeYWithoutReduction(p);
    if (y.compareTo(P) < 0 && !REJECTED_BY_BOUNCY_CASTLE.contains(y)) {
      if (Ed25519.validatePublicKeyPartial(p, 0) != onCurve) {
        throw new AssertionError("disagrees with BouncyCastle for " + HEX.formatHex(p));
      }
    } else if (referenceOnCurve(y.mod(P)) != onCurve) {
      // non-canonical encodings decompress as y - p; small-order points are on-curve
      throw new AssertionError("disagrees with decompression reference for " + HEX.formatHex(p));
    }
  }

  private static void fuzzKeygen(final byte[] seed) {
    final byte[] expected = new byte[32];
    Ed25519.generatePublicKey(seed, 0, expected, 0);
    final byte[] actual = new byte[32];
    Ed25519Util.generatePublicKey(seed, actual);
    if (!Arrays.equals(expected, actual)) {
      throw new AssertionError("keygen disagrees with BouncyCastle for seed " + HEX.formatHex(seed));
    }
    if (Ed25519Util.isNotOnCurve(actual)) {
      throw new AssertionError("generated key not on curve for seed " + HEX.formatHex(seed));
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

  private static boolean referenceOnCurve(final BigInteger y) {
    final var yy = y.multiply(y).mod(P);
    final var u = yy.subtract(BigInteger.ONE).mod(P);
    // d*y^2 + 1 is never 0 mod p because -1/d is not a square, so the inverse always exists
    final var v = D.multiply(yy).add(BigInteger.ONE).mod(P);
    final var xx = u.multiply(v.modInverse(P)).mod(P);
    // Euler's criterion: x^2 is recoverable iff it is 0 or a quadratic residue
    return xx.signum() == 0 || xx.modPow(P.shiftRight(1), P).equals(BigInteger.ONE);
  }

  private Ed25519Fuzz() {
  }
}
