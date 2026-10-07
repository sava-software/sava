package software.sava.core.accounts.pbkdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.vanity.FixedSeedSecureRandom;

import javax.crypto.AEADBadTagException;
import java.io.IOException;
import java.io.StringReader;
import java.util.Base64;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// The `.properties` form of an encrypted key file: what [EncryptionEnvelope] writes, and
/// what the properties-based [PBKDFEncryption] `decrypt` overloads read back.
///
/// The oracle for the written form is `java.util.Properties` itself plus the standard
/// Base64 encoding of the envelope's own fields; the oracle for the read side is that a
/// file this library wrote opens to the plaintext it was given. Every malformed-file case
/// fails before any key derivation, so those cost nothing; the round trips derive at the
/// cheapest parameters each KDF accepts.
final class EncryptionEnvelopePropertiesTests {

  /// Arbitrary fixed seed for the salt and IV draws; the oracle for those bytes is a second
  /// `java.util.Random` on the same seed.
  private static final long SEED = 8675309L;

  private static final PublicKey PUBLIC_KEY = PublicKey.fromBase58Encoded("4bcoVWVXfw6xKsEYdM6s7AeZQMgDG958kK5Uzhc2sw37");
  private static final byte[] AAD = PUBLIC_KEY.toByteArray();
  private static final Set<String> BASE_KEYS = Set.of("kdf", "iterations", "salt", "iv", "secret");
  private static final String PREFIX = "wallet.";

  private static KeyDerivation minPBKDF2() {
    return KeyDerivation.createPBKDF2WithHmacSHA512(PBKDF2WithHmacSHA512.MIN_ITERATIONS);
  }

  private static byte[] secret() {
    final var secret = new byte[64];
    new Random(42L).nextBytes(secret);
    return secret;
  }

  private static char[] password() {
    return "correct horse".toCharArray();
  }

  private static EncryptionEnvelope encrypt(final KeyDerivation kdf, final byte[] aad) {
    return PBKDFEncryption.encrypt(
        password(), new FixedSeedSecureRandom(SEED), secret(), kdf, aad
    );
  }

  /// An envelope built directly, for the malformed-file cases that must fail before any
  /// derivation runs.
  private static EncryptionEnvelope fixedEnvelope(final KeyDerivation kdf) {
    return new EncryptionEnvelope(kdf, null, new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, new byte[]{7, 8, 9});
  }

  private static Properties load(final String text) throws IOException {
    final var properties = new Properties();
    properties.load(new StringReader(text));
    return properties;
  }

  private static Properties prefixed(final Properties properties) {
    final var prefixed = new Properties();
    for (final var name : properties.stringPropertyNames()) {
      prefixed.setProperty(PREFIX + name, properties.getProperty(name));
    }
    return prefixed;
  }

  private static void assertEnvelopeFields(final EncryptionEnvelope envelope, final Properties properties) {
    final var encoder = Base64.getEncoder();
    assertEquals(encoder.encodeToString(envelope.salt()), properties.getProperty("salt"));
    assertEquals(encoder.encodeToString(envelope.iv()), properties.getProperty("iv"));
    assertEquals(encoder.encodeToString(envelope.cipherText()), properties.getProperty("secret"));
  }

  /// Without AAD or a prefix line, the properties text carries exactly the KDF parameters
  /// and the three encoded byte fields, and it opens with the 3-argument `decrypt`.
  @Test
  void propertiesStringWithoutAadRoundTrips() throws IOException {
    final var envelope = encrypt(minPBKDF2(), null);
    final var properties = load(envelope.toPropertiesString());

    assertEquals(BASE_KEYS, properties.stringPropertyNames());
    assertEquals("PBKDF2WithHmacSHA512", properties.getProperty("kdf"));
    assertEquals(Integer.toString(PBKDF2WithHmacSHA512.MIN_ITERATIONS), properties.getProperty("iterations"));
    assertEnvelopeFields(envelope, properties);

    assertArrayEquals(secret(), PBKDFEncryption.decrypt("", properties, password()));
  }

  /// The public-key overload writes the address as `pubKey` and the AAD as `aad`, and the
  /// 3-argument `decrypt` picks the AAD up from the file on its own.
  @Test
  void propertiesStringWithPublicKeyCarriesAddressAndAad() throws IOException {
    final var envelope = encrypt(minPBKDF2(), AAD);
    final var properties = load(envelope.toPropertiesString(PUBLIC_KEY));

    final var expectedKeys = new HashSet<>(BASE_KEYS);
    expectedKeys.add("pubKey");
    expectedKeys.add("aad");
    assertEquals(expectedKeys, properties.stringPropertyNames());
    assertEquals(PUBLIC_KEY.toBase58(), properties.getProperty("pubKey"));
    assertEquals(Base64.getEncoder().encodeToString(AAD), properties.getProperty("aad"));
    assertEnvelopeFields(envelope, properties);

    assertArrayEquals(secret(), PBKDFEncryption.decrypt("", properties, password()));
  }

  /// A null or blank prefix adds nothing; a non-blank prefix is written stripped, as its
  /// own line. An empty AAD is the same as no AAD and adds no `aad` entry.
  @Test
  void prefixLineAndEmptyAadAreWrittenOnlyWhenPresent() throws IOException {
    final var envelope = fixedEnvelope(minPBKDF2());
    assertEquals(BASE_KEYS, load(envelope.toPropertiesString((String) null)).stringPropertyNames());
    assertEquals(BASE_KEYS, load(envelope.toPropertiesString(" \t ")).stringPropertyNames());

    final var labelled = load(envelope.toPropertiesString("  label=hot  "));
    final var expectedKeys = new HashSet<>(BASE_KEYS);
    expectedKeys.add("label");
    assertEquals(expectedKeys, labelled.stringPropertyNames());
    assertEquals("hot", labelled.getProperty("label"));

    final var emptyAad = new EncryptionEnvelope(minPBKDF2(), new byte[0], new byte[]{1}, new byte[]{2}, new byte[]{3});
    assertEquals(BASE_KEYS, load(emptyAad.toPropertiesString()).stringPropertyNames());
  }

  /// The properties text, byte for byte: the prefix line when there is one (an empty line
  /// when there is none, as the template leaves it), the KDF's own lines, then the encoded
  /// fields. A blank prefix writes the same text as none, which is also the oracle for accepting
  /// the mutant that routes a blank prefix through `strip()` instead of the blank check.
  @Test
  void propertiesTextIsExactlyThePrefixLineTheKdfLinesAndTheEncodedFields() {
    final var envelope = fixedEnvelope(KeyDerivation.createPBKDF2WithHmacSHA512(543_210));
    final var withoutPrefix = """

        kdf=PBKDF2WithHmacSHA512
        iterations=543210
        salt=AQID
        iv=BAUG
        secret=BwgJ
        """;
    assertEquals(withoutPrefix, envelope.toPropertiesString());
    assertEquals(withoutPrefix, envelope.toPropertiesString((String) null));
    assertEquals(withoutPrefix, envelope.toPropertiesString(" \t "));
    assertEquals("label=hot" + withoutPrefix, envelope.toPropertiesString("  label=hot  "));
  }

  /// The JSON text, byte for byte: the prefix members indented into the object (every line of
  /// a multi-line prefix, which is what the indent call is for), the KDF object nested two
  /// deeper, then the encoded fields; the first line after the brace is empty when there is no
  /// prefix, as the template leaves it.
  @Test
  void jsonTextIsExactlyTheIndentedMembers() {
    final var pbkdf2 = fixedEnvelope(KeyDerivation.createPBKDF2WithHmacSHA512(543_210));
    assertEquals("""
        {

          "kdf": {
            "kdf": "PBKDF2WithHmacSHA512",
            "iterations": 543210
          },
          "salt": "AQID",
          "iv": "BAUG",
          "secret": "BwgJ"
        }""", pbkdf2.toJson());

    final var argon2id = new EncryptionEnvelope(
        KeyDerivation.createArgon2id(Argon2id.MIN_MEMORY_KB, 2, Argon2id.MIN_ITERATIONS),
        AAD, new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, new byte[]{7, 8, 9}
    );
    assertEquals("""
        {
        "pubKey": "4bcoVWVXfw6xKsEYdM6s7AeZQMgDG958kK5Uzhc2sw37",
          "kdf": {
            "kdf": "Argon2id",
            "iterations": 1,
            "memoryKB": 19456,
            "parallelism": 2
          },
          "aad": "%s",
          "salt": "AQID",
          "iv": "BAUG",
          "secret": "BwgJ"
        }""".formatted(Base64.getEncoder().encodeToString(AAD)), argon2id.toJson(PUBLIC_KEY));

    assertEquals("""
        {
        "label": "hot",
          "wallet": 7,
          "kdf": {
            "kdf": "PBKDF2WithHmacSHA512",
            "iterations": 543210
          },
          "salt": "AQID",
          "iv": "BAUG",
          "secret": "BwgJ"
        }""", pbkdf2.toJson("\"label\": \"hot\",\n\"wallet\": 7,\n"));
  }

  /// `addProperties` writes the same entries the text form does, under the same names,
  /// and adds `aad` only when there is AAD to bind.
  @Test
  void addPropertiesWritesTheSameEntriesAsTheTextForm() throws IOException {
    for (final byte[] aad : new byte[][]{null, new byte[0], AAD}) {
      final var envelope = new EncryptionEnvelope(minPBKDF2(), aad, new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, new byte[]{7, 8, 9});
      final var added = new Properties();
      envelope.addProperties(added);
      assertEquals(load(envelope.toPropertiesString()), added);
      if (aad == AAD) {
        assertEquals(Base64.getEncoder().encodeToString(AAD), added.getProperty("aad"));
      } else {
        assertEquals(BASE_KEYS, added.stringPropertyNames());
      }
    }
  }

  /// Entries written by `addProperties` and nested under a key prefix open with the same
  /// prefix passed to `decrypt`.
  @Test
  void addPropertiesRoundTripsUnderAKeyPrefix() {
    final var envelope = encrypt(minPBKDF2(), AAD);
    final var properties = new Properties();
    envelope.addProperties(properties);

    assertArrayEquals(secret(), PBKDFEncryption.decrypt(PREFIX, prefixed(properties), password()));
  }

  /// The AAD read from the file is actually bound into the decryption: the same file with
  /// its `aad` entry removed fails authentication, and supplying the AAD explicitly through
  /// the 4-argument overload opens it again.
  @Test
  void aadFromTheFileIsBoundIntoDecryption() {
    final var envelope = encrypt(minPBKDF2(), AAD);
    final var properties = new Properties();
    envelope.addProperties(properties);
    properties.remove("aad");

    final var ex = assertThrows(IllegalStateException.class, () -> PBKDFEncryption.decrypt("", properties, password()));
    assertInstanceOf(AEADBadTagException.class, ex.getCause());
    assertArrayEquals(secret(), PBKDFEncryption.decrypt("", properties, password(), AAD));
  }

  /// A blank `aad` entry means no AAD rather than a Base64 value to decode.
  @Test
  void blankAadEntryMeansNoAad() {
    final var envelope = encrypt(minPBKDF2(), null);
    final var properties = new Properties();
    envelope.addProperties(properties);
    properties.setProperty("aad", "   ");

    assertArrayEquals(secret(), PBKDFEncryption.decrypt("", properties, password()));
  }

  /// The `kdf` name is matched without regard to case, and surrounding whitespace on any
  /// required value is ignored.
  @Test
  void kdfNameIsCaseInsensitiveAndValuesAreStripped() {
    final var envelope = encrypt(minPBKDF2(), null);
    final var properties = new Properties();
    envelope.addProperties(properties);
    properties.setProperty("kdf", "pbkdf2withhmacsha512");
    properties.setProperty("iterations", " " + PBKDF2WithHmacSHA512.MIN_ITERATIONS + " ");
    properties.setProperty("salt", properties.getProperty("salt") + "  ");

    assertArrayEquals(secret(), PBKDFEncryption.decrypt("", properties, password()));
  }

  /// Each required entry, missing or blank, is refused with a message naming the full
  /// prefixed key, so a user can find the line to fix.
  @Test
  void missingOrBlankRequiredPropertyIsNamed() {
    final var written = new Properties();
    fixedEnvelope(minPBKDF2()).addProperties(written);
    for (final var name : BASE_KEYS) {
      for (final var blank : new String[]{null, "", "  "}) {
        final var properties = prefixed(written);
        if (blank == null) {
          properties.remove(PREFIX + name);
        } else {
          properties.setProperty(PREFIX + name, blank);
        }
        final var ex = assertThrows(
            IllegalArgumentException.class,
            () -> PBKDFEncryption.decrypt(PREFIX, properties, password()),
            name
        );
        assertEquals("Missing required property: " + PREFIX + name, ex.getMessage());
      }
    }
  }

  /// The Argon2id-only parameters are required when the file names Argon2id.
  @Test
  void argon2idRequiresMemoryAndParallelism() {
    final var written = new Properties();
    fixedEnvelope(KeyDerivation.createArgon2id(Argon2id.MIN_MEMORY_KB, 2, Argon2id.MIN_ITERATIONS)).addProperties(written);
    for (final var name : new String[]{"memoryKB", "parallelism"}) {
      final var properties = prefixed(written);
      properties.remove(PREFIX + name);
      final var ex = assertThrows(IllegalArgumentException.class, () -> PBKDFEncryption.decrypt(PREFIX, properties, password()));
      assertEquals("Missing required property: " + PREFIX + name, ex.getMessage());
    }
  }

  /// A non-integer numeric parameter is refused with the key and the stripped value.
  @Test
  void nonIntegerIterationsIsRefusedWithTheValue() {
    final var written = new Properties();
    fixedEnvelope(minPBKDF2()).addProperties(written);
    final var properties = prefixed(written);
    properties.setProperty(PREFIX + "iterations", "  many ");

    final var ex = assertThrows(IllegalArgumentException.class, () -> PBKDFEncryption.decrypt(PREFIX, properties, password()));
    assertEquals("Property wallet.iterations must be an integer but was 'many'", ex.getMessage());
    assertInstanceOf(NumberFormatException.class, ex.getCause());
  }

  /// A KDF name the library does not implement is refused by name rather than guessed at.
  @Test
  void unsupportedKdfIsRefusedByName() {
    final var written = new Properties();
    fixedEnvelope(minPBKDF2()).addProperties(written);
    final var properties = prefixed(written);
    properties.setProperty(PREFIX + "kdf", "scrypt");

    final var ex = assertThrows(IllegalArgumentException.class, () -> PBKDFEncryption.decrypt(PREFIX, properties, password()));
    assertEquals("Unsupported key derivation function: scrypt", ex.getMessage());
  }

  /// An Argon2id envelope written as properties text opens from it, the KDF parameters
  /// come back under their own names, and the `kdf` name is matched without regard to
  /// case. The parameters are the cheapest the bounds admit, with a parallelism distinct
  /// from the iteration count so a swapped read derives a different key.
  @Test
  @ResourceLock("argon2id")
  void argon2idPropertiesStringRoundTrips() throws IOException {
    final var envelope = encrypt(KeyDerivation.createArgon2id(Argon2id.MIN_MEMORY_KB, 2, Argon2id.MIN_ITERATIONS), AAD);
    final var properties = load(envelope.toPropertiesString());

    assertEquals("Argon2id", properties.getProperty("kdf"));
    assertEquals(Integer.toString(Argon2id.MIN_MEMORY_KB), properties.getProperty("memoryKB"));
    assertEquals("2", properties.getProperty("parallelism"));
    assertEquals(Integer.toString(Argon2id.MIN_ITERATIONS), properties.getProperty("iterations"));
    assertEnvelopeFields(envelope, properties);
    assertArrayEquals(secret(), PBKDFEncryption.decrypt("", properties, password()));

    properties.setProperty("kdf", "ARGON2ID");
    assertArrayEquals(secret(), PBKDFEncryption.decrypt("", properties, password()));
  }
}
