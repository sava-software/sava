package software.sava.core.crypto;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.junit.jupiter.api.Test;
import software.sava.core.encoding.Jex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/// [Hash#sha256Twice] and [Hash#h160] have no caller in this repository, so
/// nothing else would notice if they broke. Both are pinned to published vectors
/// and differentially checked against a naive implementation.
///
/// The differential matters most for `sha256Twice`, which reuses a single
/// [MessageDigest] across both rounds and relies on `digest()` resetting the
/// instance. The naive version below uses two separate instances, so the two
/// agreeing is real evidence rather than the same code twice.
final class HashTests {

  private static byte[] ascii(final String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] naiveSha256Twice(final byte[] input) throws NoSuchAlgorithmException {
    final byte[] once = MessageDigest.getInstance("SHA-256").digest(input);
    return MessageDigest.getInstance("SHA-256").digest(once);
  }

  private static byte[] naiveH160(final byte[] input) throws NoSuchAlgorithmException {
    final byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(input);
    final var ripemd = new RIPEMD160Digest();
    ripemd.update(sha256, 0, sha256.length);
    final byte[] out = new byte[20];
    ripemd.doFinal(out, 0);
    return out;
  }

  /// The canonical FIPS 180-4 vector, so the underlying provider is what we think.
  @Test
  void sha256MatchesTheFipsVector() {
    assertArrayEquals(
        Jex.decode("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
        Hash.sha256(ascii("abc")));
  }

  @Test
  void sha256TwiceMatchesPublishedVectors() {
    assertArrayEquals(
        Jex.decode("5df6e0e2761359d30a8275058e299fcc0381534545f55cf43e41983f5d4c9456"),
        Hash.sha256Twice(new byte[0]));
    assertArrayEquals(
        Jex.decode("4f8b42c22dd3729b519ba6f68d2da7cc5b2d606d05daed5ad5128cc03e6c6358"),
        Hash.sha256Twice(ascii("abc")));
    assertArrayEquals(
        Jex.decode("9595c9df90075148eb06860365df33584b75bff782a510c6cd4883a419833d50"),
        Hash.sha256Twice(ascii("hello")));
  }

  @Test
  void h160MatchesPublishedVectors() {
    assertArrayEquals(
        Jex.decode("b472a266d0bd89c13706a4132ccfb16f7c3b9fcb"),
        Hash.h160(new byte[0]));
    assertArrayEquals(
        Jex.decode("bb1be98c142444d7a56aa3981c3942a978e4dc33"),
        Hash.h160(ascii("abc")));
    assertArrayEquals(
        Jex.decode("b6a9c8c230722b7c748331a8b450f05566dc7d0f"),
        Hash.h160(ascii("hello")));
  }

  /// Digest reuse is the risky part of both implementations, so drive them over a
  /// spread of lengths that crosses the 64 byte block and 55 byte padding
  /// boundaries.
  @Test
  void agreeWithANaiveImplementation() throws NoSuchAlgorithmException {
    final var random = new Random(42);
    for (int length = 0; length <= 200; ++length) {
      final byte[] input = new byte[length];
      random.nextBytes(input);
      assertArrayEquals(naiveSha256Twice(input), Hash.sha256Twice(input), "sha256Twice at length " + length);
      assertArrayEquals(naiveH160(input), Hash.h160(input), "h160 at length " + length);
    }
  }

  @Test
  void h160IsAlwaysTwentyBytes() {
    assertEquals(20, Hash.h160(new byte[0]).length);
    assertEquals(20, Hash.h160(ascii("abc")).length);
    assertEquals(20, Hash.h160(new byte[1024]).length);
  }

  @Test
  void sha256TwiceIsThirtyTwoBytes() {
    assertEquals(32, Hash.sha256Twice(new byte[0]).length);
    assertEquals(32, Hash.sha256Twice(new byte[1024]).length);
  }

  /// The windowed overload must hash exactly the window — bytes either side of it
  /// cannot contribute, or an offset read would silently mix in neighbouring data.
  @Test
  void windowedSha256TwiceHashesOnlyTheWindow() {
    final byte[] payload = ascii("hello");
    final byte[] expected = Hash.sha256Twice(payload);

    for (int offset = 0; offset <= 8; ++offset) {
      final byte[] framed = new byte[payload.length + offset + 7];
      Arrays.fill(framed, (byte) 0x5A);
      System.arraycopy(payload, 0, framed, offset, payload.length);
      assertArrayEquals(expected, Hash.sha256Twice(framed, offset, payload.length), "offset " + offset);
    }

    // different surrounding bytes, same window, same hash
    final byte[] other = new byte[payload.length + 4];
    Arrays.fill(other, (byte) 0xFF);
    System.arraycopy(payload, 0, other, 2, payload.length);
    assertArrayEquals(expected, Hash.sha256Twice(other, 2, payload.length));
  }

  @Test
  void windowedSha256TwiceMatchesTheWholeArrayOverload() {
    final byte[] input = ascii("the quick brown fox");
    assertArrayEquals(Hash.sha256Twice(input), Hash.sha256Twice(input, 0, input.length));
    // a zero length window is the hash of nothing
    assertArrayEquals(Hash.sha256Twice(new byte[0]), Hash.sha256Twice(input, 3, 0));
  }

  /// Each call must hand back a fresh digest; a shared instance would let one
  /// caller's partial update corrupt another's hash.
  @Test
  void digestFactoriesReturnFreshInstances() {
    final var first = Hash.sha256Digest();
    final var second = Hash.sha256Digest();
    assertNotSame(first, second);
    first.update(ascii("poison"));
    assertArrayEquals(Hash.sha256(ascii("abc")), second.digest(ascii("abc")));

    assertNotSame(Hash.sha512Digest(), Hash.sha512Digest());
    assertEquals(64, Hash.sha512Digest().digest(new byte[0]).length);
    assertEquals(32, Hash.sha256Digest().digest(new byte[0]).length);
  }

  /// SHA-512 is not decoration here: [software.sava.core.crypto.ed25519.Ed25519Util]
  /// derives public keys through this digest, and `BaseMaskWorker` generates vanity
  /// keys with it. A wrong SHA-512 produces addresses nobody holds the key to, so it
  /// gets known-answer vectors rather than a length check.
  ///
  /// FIPS 180-4 one-block, empty, and the 448-bit multi-block case.
  @Test
  void sha512MatchesTheFipsVectors() {
    assertArrayEquals(
        Jex.decode("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
            + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"),
        Hash.sha512Digest().digest(ascii("abc")));

    assertArrayEquals(
        Jex.decode("cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
            + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"),
        Hash.sha512Digest().digest(new byte[0]));

    assertArrayEquals(
        Jex.decode("204a8fc6dda82f0a0ced7beb8e08a41657c16ef468b228a8279be331a703c335"
            + "96fd15c13b1b07f9aa1d3bea57789ca031ad85c7a71dd70354ec631238ca3445"),
        Hash.sha512Digest().digest(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")));
  }

  /// The digests are requested from a specific [java.security.Provider] instance
  /// rather than by name, so a provider installed ahead of SUN cannot supply the
  /// implementation. That is a deliberate choice and worth asserting.
  @Test
  void digestsComeFromThePinnedSunProvider() {
    assertSame(SunCrypto.SUN_SECURITY_PROVIDER, Hash.sha256Digest().getProvider());
    assertSame(SunCrypto.SUN_SECURITY_PROVIDER, Hash.sha512Digest().getProvider());
    assertEquals("SHA-256", Hash.sha256Digest().getAlgorithm());
    assertEquals("SHA-512", Hash.sha512Digest().getAlgorithm());
  }

  /// Digest length is part of the contract for callers sizing buffers.
  @Test
  void digestLengthsAreFixed() {
    assertEquals(32, Hash.sha256Digest().getDigestLength());
    assertEquals(64, Hash.sha512Digest().getDigestLength());
    assertEquals(32, Hash.sha256(new byte[0]).length);
  }
}
