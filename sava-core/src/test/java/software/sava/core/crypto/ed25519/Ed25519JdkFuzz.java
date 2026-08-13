package software.sava.core.crypto.ed25519;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.HexFormat;

/// Jazzer differential entry point for Sava's Ed25519 public-key derivation.
/// This is first-party defensive testing: every 32-byte input is an RFC 8032
/// private-key seed, and [Ed25519Util#generatePublicKey] must produce exactly the
/// same compressed public key as the JDK's SunEC provider.
///
/// SunEC's key-pair generator normally obtains an Ed25519 seed by requesting 32
/// random bytes. [ExactSeedRandom] supplies the fuzz input for that one request,
/// and the generated [EdECPrivateKey] is read back and compared before its public
/// key is trusted as the oracle. If the provider ever changes that consumption
/// contract, the harness fails explicitly instead of comparing against an
/// unrelated key.
///
/// This target deliberately does not use JDK point parsing as an oracle for
/// [Ed25519Util#isNotOnCurve]: SunEC enforces RFC 8032 canonical encodings, while
/// Solana PDA derivation uses curve25519-dalek decompression semantics. That
/// predicate remains owned by [Ed25519Fuzz]'s BigInteger reference.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :sava-core:fuzzEd25519Jdk [-PmaxFuzzTime=<seconds>]`.
public final class Ed25519JdkFuzz {

  private static final int SEED_LENGTH = 32;
  private static final HexFormat HEX = HexFormat.of();
  private static final KeyPairGenerator SUN_EC_KEYGEN = createSunEcKeygen();

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < SEED_LENGTH) {
      return;
    }
    final byte[] seed = Arrays.copyOf(data, SEED_LENGTH);

    final byte[] expected = generateWithSunEc(seed);
    final byte[] actual = new byte[SEED_LENGTH];
    Ed25519Util.generatePublicKey(seed, actual);
    if (!Arrays.equals(expected, actual)) {
      throw new AssertionError("keygen disagrees with SunEC for seed " + HEX.formatHex(seed)
          + ": expected " + HEX.formatHex(expected) + ", actual " + HEX.formatHex(actual));
    }
  }

  private static byte[] generateWithSunEc(final byte[] seed) {
    final var random = new ExactSeedRandom(seed);
    final KeyPair keyPair;
    // Jazzer currently invokes a target serially, as does the generated seed replay,
    // but keep the reusable JCA engine safe if either caller becomes concurrent.
    synchronized (SUN_EC_KEYGEN) {
      try {
        SUN_EC_KEYGEN.initialize(NamedParameterSpec.ED25519, random);
      } catch (final InvalidAlgorithmParameterException e) {
        throw new AssertionError("SunEC rejected the Ed25519 parameter", e);
      }
      keyPair = SUN_EC_KEYGEN.generateKeyPair();
    }
    random.assertConsumedExactlyOnce();

    if (!(keyPair.getPrivate() instanceof EdECPrivateKey privateKey)) {
      throw new AssertionError("SunEC returned a non-EdEC private key: "
          + keyPair.getPrivate().getClass().getName());
    }
    final byte[] generatedSeed = privateKey.getBytes().orElseThrow(
        () -> new AssertionError("SunEC EdEC private key did not expose its seed"));
    if (!Arrays.equals(seed, generatedSeed)) {
      throw new AssertionError("SunEC did not use the supplied seed " + HEX.formatHex(seed)
          + ": private key contains " + HEX.formatHex(generatedSeed));
    }

    if (!(keyPair.getPublic() instanceof EdECPublicKey publicKey)) {
      throw new AssertionError("SunEC returned a non-EdEC public key: "
          + keyPair.getPublic().getClass().getName());
    }
    return encode(publicKey.getPoint());
  }

  private static byte[] encode(final EdECPoint point) {
    final BigInteger y = point.getY();
    if (y.signum() < 0 || y.bitLength() > 255) {
      throw new AssertionError("SunEC returned an invalid Ed25519 y coordinate: " + y);
    }

    final byte[] encoded = new byte[SEED_LENGTH];
    for (int i = 0; i < encoded.length; ++i) {
      encoded[i] = y.shiftRight(i << 3).byteValue();
    }
    if (point.isXOdd()) {
      encoded[encoded.length - 1] |= (byte) 0x80;
    }
    return encoded;
  }

  private static KeyPairGenerator createSunEcKeygen() {
    try {
      return KeyPairGenerator.getInstance("Ed25519", "SunEC");
    } catch (final NoSuchAlgorithmException | NoSuchProviderException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final class ExactSeedRandom extends SecureRandom {

    private final byte[] seed;
    private int requests;

    private ExactSeedRandom(final byte[] seed) {
      this.seed = seed.clone();
    }

    @Override
    public void nextBytes(final byte[] bytes) {
      ++requests;
      if (requests != 1 || bytes.length != seed.length) {
        throw new AssertionError("SunEC seed contract changed: request " + requests
            + " asked for " + bytes.length + " bytes, expected one " + seed.length
            + "-byte request");
      }
      System.arraycopy(seed, 0, bytes, 0, seed.length);
    }

    @Override
    public byte[] generateSeed(final int numBytes) {
      throw new AssertionError("SunEC seed contract changed: generateSeed(" + numBytes + ')');
    }

    private void assertConsumedExactlyOnce() {
      if (requests != 1) {
        throw new AssertionError("SunEC seed contract changed: expected one request, got "
            + requests);
      }
    }
  }

  private Ed25519JdkFuzz() {
  }
}
