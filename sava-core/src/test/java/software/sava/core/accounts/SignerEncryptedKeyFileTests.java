package software.sava.core.accounts;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.accounts.pbkdf.PBKDFEncryption;
import software.sava.core.accounts.vanity.FixedSeedSecureRandom;
import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.Signer.KEY_LENGTH;

/// Writes and reads encrypted key files through [Signer#encryptKey] and
/// [Signer#fromProperties]. Each file is a PBKDF envelope whose associated data is the
/// key's public key, and which declares that key as `pubKey` (base58) or `aad` (base64).
///
/// The keys are the RFC 8032 section 7.1 TEST 1 and TEST 2 vectors, so the expected public
/// keys and signatures come from the RFC, not from this library. Files are sealed with
/// [PBKDFEncryption#encrypt] at the cheapest PBKDF2 cost the reader accepts, with salt and
/// IV from a fixed seed; only the `encryptKey` tests draw from the library's own random
/// source, which has no seam, and nothing they assert depends on the values drawn.
final class SignerEncryptedKeyFileTests {

  private static final HexFormat HEX = HexFormat.of();
  private static final String PASSWORD = "correct horse battery staple";
  // the lowest count the reader accepts: these tests exercise the file handling, not the
  // work factor
  private static final int MIN_PBKDF2_ITERATIONS = 500_000;

  /// RFC 8032 TEST 1 secret key.
  private static byte[] secretA() {
    return HEX.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
  }

  /// RFC 8032 TEST 1 public key.
  private static byte[] publicA() {
    return HEX.parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
  }

  /// RFC 8032 TEST 1 signature, over the empty message.
  private static byte[] signatureA() {
    return HEX.parseHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
        + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
  }

  /// The 64-byte Solana key pair of TEST 1: private half, then public half.
  private static byte[] pairA() {
    return concat(secretA(), publicA());
  }

  /// RFC 8032 TEST 2 secret key.
  private static byte[] secretB() {
    return HEX.parseHex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
  }

  /// RFC 8032 TEST 2 public key.
  private static byte[] publicB() {
    return HEX.parseHex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
  }

  private static byte[] pairB() {
    return concat(secretB(), publicB());
  }

  private static byte[] concat(final byte[] first, final byte[] second) {
    final byte[] joined = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, joined, first.length, second.length);
    return joined;
  }

  private static KeyDerivation cheapestPbkdf2() {
    return KeyDerivation.createPBKDF2WithHmacSHA512(MIN_PBKDF2_ITERATIONS);
  }

  /// Seals `secret` under `aad` as a key file stores it, every field written under
  /// `prefix`. The salt and IV come from a seed taken from the file's own content, so two
  /// different fixtures never share them.
  private static Properties keyFile(final String prefix, final byte[] secret, final byte[] aad) {
    final var random = new FixedSeedSecureRandom(31L * Arrays.hashCode(secret) + Arrays.hashCode(aad));
    final var envelope = PBKDFEncryption.encrypt(PASSWORD.toCharArray(), random, secret, cheapestPbkdf2(), aad);
    final var fields = new Properties();
    envelope.addProperties(fields);
    final var file = new Properties();
    for (final var name : fields.stringPropertyNames()) {
      file.setProperty(prefix + name, fields.getProperty(name));
    }
    return file;
  }

  private static Properties copyOf(final Properties file) {
    final var copy = new Properties();
    copy.putAll(file);
    return copy;
  }

  private static Signer read(final String prefix, final Properties file) {
    return Signer.fromProperties(prefix, file, PASSWORD.toCharArray());
  }

  /// A 32-byte private key is sealed under the public key it derives, which the envelope
  /// carries as its associated data. The envelope keeps the derivation it was given and
  /// opens to the private key.
  @Test
  void encryptKeySealsAPrivateKeyUnderTheKeyItDerives() {
    final var kdf = cheapestPbkdf2();
    final var envelope = Signer.encryptKey(secretA(), PASSWORD.toCharArray(), kdf);
    assertArrayEquals(publicA(), envelope.aad());
    assertSame(kdf, envelope.keyDerivation());
    assertArrayEquals(secretA(), envelope.decrypt(PASSWORD.toCharArray()));
  }

  /// A 64-byte key pair is sealed under its own public half.
  @Test
  void encryptKeySealsAKeyPairUnderItsPublicHalf() {
    final var envelope = Signer.encryptKey(pairA(), PASSWORD.toCharArray(), cheapestPbkdf2());
    assertArrayEquals(publicA(), envelope.aad());
    assertArrayEquals(pairA(), envelope.decrypt(PASSWORD.toCharArray()));
  }

  /// Only a 32-byte private key or a 64-byte key pair is a key, and every other length is
  /// refused before anything is derived. The lengths include 33, 48 and 63 bytes, whose
  /// public half a lenient copy would pad with zeros, and 96 bytes whose first 64 are a
  /// valid pair.
  @Test
  void encryptKeyRefusesEveryOtherLength() {
    for (final int length : new int[]{0, 16, 31, 33, 48, 63, 65, 96}) {
      final byte[] key = Arrays.copyOf(pairA(), length);
      final var failure = assertThrowsExactly(
          IllegalArgumentException.class,
          () -> Signer.encryptKey(key, PASSWORD.toCharArray(), cheapestPbkdf2()),
          "length " + length
      );
      assertEquals("Invalid secret key or key pair length", failure.getMessage());
    }
  }

  /// One properties object can hold several key files, each under its own prefix. A prefix
  /// read with or without its trailing dot finds the file written under `prefix.`, and a
  /// blank prefix finds the unprefixed one: here key B unprefixed and key A under
  /// `signer.`.
  @Test
  void aPrefixSelectsTheKeyFileWrittenUnderIt() {
    final var file = keyFile("", pairB(), publicB());
    file.putAll(keyFile("signer.", pairA(), publicA()));

    assertArrayEquals(publicA(), read("signer", file).publicKey().toByteArray());
    assertArrayEquals(publicA(), read("signer.", file).publicKey().toByteArray());
    assertArrayEquals(publicB(), read(" \t", file).publicKey().toByteArray());
  }

  /// The password is checked before the file is read: an empty or a missing one is refused
  /// with the same message, even for a complete, correctly sealed file.
  @Test
  void anEmptyOrMissingPasswordIsRefusedBeforeTheFileIsRead() {
    final var file = keyFile("", pairA(), publicA());
    for (final char[] password : new char[][]{new char[0], null}) {
      final var failure = assertThrowsExactly(
          IllegalArgumentException.class,
          () -> Signer.fromProperties(file, password)
      );
      assertEquals("A password is required to decrypt an encrypted key file.", failure.getMessage());
    }
  }

  /// The file declares its key by `pubKey`, or else by `aad`. `pubKey` alone suffices, a
  /// blank `pubKey` defers to `aad`, and when both are present `pubKey` decides: here `aad`
  /// names key B, and since the declared key is the associated data the secret was sealed
  /// under, reading key B's name would fail authentication.
  @Test
  void theDeclaredKeyComesFromPubKeyAndOtherwiseFromAad() {
    final var sealed = keyFile("", pairA(), publicA());

    final var pubKeyOnly = copyOf(sealed);
    pubKeyOnly.remove("aad");
    pubKeyOnly.setProperty("pubKey", Base58.encode(publicA()));
    assertArrayEquals(publicA(), read("", pubKeyOnly).publicKey().toByteArray());

    final var blankPubKey = copyOf(sealed);
    blankPubKey.setProperty("pubKey", " \t");
    assertArrayEquals(publicA(), read("", blankPubKey).publicKey().toByteArray());

    final var both = copyOf(sealed);
    both.setProperty("pubKey", Base58.encode(publicA()));
    both.setProperty("aad", Base64.getEncoder().encodeToString(publicB()));
    assertArrayEquals(publicA(), read("", both).publicKey().toByteArray());
  }

  /// Whitespace around the declared key is ignored in either entry. `Properties.load` keeps
  /// trailing whitespace in a value, and a properties object built in code can carry both.
  @Test
  void whitespaceAroundTheDeclaredKeyIsIgnored() {
    final var sealed = keyFile("", pairA(), publicA());

    final var paddedAad = copyOf(sealed);
    paddedAad.setProperty("aad", " \t" + Base64.getEncoder().encodeToString(publicA()) + " \t");
    assertArrayEquals(publicA(), read("", paddedAad).publicKey().toByteArray());

    final var paddedPubKey = copyOf(sealed);
    paddedPubKey.remove("aad");
    paddedPubKey.setProperty("pubKey", " \t" + Base58.encode(publicA()) + " \t");
    assertArrayEquals(publicA(), read("", paddedPubKey).publicKey().toByteArray());
  }

  /// A file that declares no key, with no `pubKey` and a blank `aad`, is refused by a
  /// message naming both entries under the prefix; the blank is not decoded as a key.
  @Test
  void aFileThatDeclaresNoKeyIsRefusedByName() {
    final var file = keyFile("signer.", pairA(), publicA());
    file.setProperty("signer.aad", " \t");
    final var failure = assertThrowsExactly(IllegalArgumentException.class, () -> read("signer", file));
    assertEquals("The public key must be provided by signer.pubKey or signer.aad", failure.getMessage());
  }

  /// A file may hold the 32-byte private key alone. The signer is the one it derives: the
  /// RFC 8032 TEST 1 public key, signing the empty message to the TEST 1 signature.
  @Test
  void aFileHoldingOnlyThePrivateKeyReadsAsTheSignerItDerives() {
    final var signer = read("", keyFile("", secretA(), publicA()));
    assertArrayEquals(publicA(), signer.publicKey().toByteArray());
    assertArrayEquals(signatureA(), signer.sign(new byte[0]));
  }

  /// A 64-byte secret is read as a key pair and must be one: a pair whose public half is
  /// not the key its private half derives is refused, even when the file declares the
  /// derived key, so a corrupted file never loads silently.
  @Test
  void aKeyPairWhoseHalvesDisagreeIsRefused() {
    final byte[] corrupted = pairA();
    corrupted[KEY_LENGTH] ^= 1;
    final var file = keyFile("", corrupted, publicA());
    final var failure = assertThrowsExactly(IllegalStateException.class, () -> read("", file));
    assertTrue(failure.getMessage().endsWith(" <> " + Base58.encode(publicA())), failure.getMessage());
  }

  /// A secret that is neither a 32-byte key nor a 64-byte pair is refused, including one
  /// whose first 64 bytes are a valid pair for the declared key, followed by 32 more.
  @Test
  void aSecretOfAnyOtherLengthIsRefused() {
    final byte[] trailing = new byte[KEY_LENGTH];
    Arrays.fill(trailing, (byte) 0x5A);
    for (final byte[] secret : new byte[][]{concat(pairA(), trailing), Arrays.copyOf(pairA(), 33)}) {
      final var file = keyFile("", secret, publicA());
      final var failure = assertThrowsExactly(
          IllegalArgumentException.class,
          () -> read("", file),
          secret.length + " bytes"
      );
      assertEquals("Invalid private key or key pair length", failure.getMessage());
    }
  }

  /// A file can be sealed under, and declare, a key other than the one its secret derives.
  /// Reading it must refuse it rather than return a signer for an address the file does not
  /// name. Both secret forms of key A are sealed under, and declare, key B.
  @Test
  void aFileDeclaringAnotherKeyThanItsSecretIsRefused() {
    final var expected = String.format(
        "[expected=%s] != [derived=%s]", Base58.encode(publicB()), Base58.encode(publicA())
    );
    for (final byte[] secret : new byte[][]{secretA(), pairA()}) {
      final var file = keyFile("", secret, publicB());
      final var failure = assertThrowsExactly(
          IllegalStateException.class,
          () -> read("", file),
          secret.length + " bytes"
      );
      assertEquals(expected, failure.getMessage());
    }
  }
}
