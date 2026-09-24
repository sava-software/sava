package software.sava.core.accounts.vanity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.accounts.pbkdf.PBKDFEncryption;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/// A vanity key that cannot be read back is lost outright, so every encoding the
/// worker can write is round tripped through the reader that ships with it
/// ([PrivateKeyEncoding#parseSecret]) and checked against the address the worker
/// reported.
final class KeyFileRoundTripTests {

  /// A null `beginsWith` matches the first generated key, so each worker finds
  /// its one key immediately — the seed then fixes exactly which key that is.
  private static Result generateInto(final long seed,
                                     final Path keyPath,
                                     final char[] password,
                                     final PrivateKeyEncoding encoding,
                                     final KeyFileFormat format,
                                     final KeyDerivation keyDerivation) {
    final var results = new ArrayBlockingQueue<Result>(1);
    new BeginsWithMaskWorker(
        keyPath, password, new FixedSeedSecureRandom(seed), encoding, format, keyDerivation, false,
        null, 1, new AtomicInteger(0), new AtomicLong(0), results, 1024, Long.MAX_VALUE
    ).run();
    final var result = results.poll();
    assertNotNull(result, "worker returned without queueing a result");
    return result;
  }

  private static Path soleFile(final Path dir) throws IOException {
    try (final var files = Files.list(dir)) {
      final var found = files.toList();
      assertEquals(1, found.size(), "expected exactly one key file, got " + found);
      return found.getFirst();
    }
  }

  private static String secretFromJson(final String content) {
    final int at = content.indexOf("\"secret\":");
    assertTrue(at >= 0, "no secret field in " + content);
    var value = content.substring(at + "\"secret\":".length(), content.lastIndexOf('}')).strip();
    if (value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static Properties properties(final String content) throws IOException {
    final var properties = new Properties();
    properties.load(new StringReader(content));
    return properties;
  }

  /// Every encoding, in both file formats, must survive a write and read back to
  /// the same address.
  @Test
  @Timeout(120)
  void everyEncodingRoundTripsInBothFormats(@TempDir final Path tempDir) throws IOException {
    long seed = FixedSeedSecureRandom.SEEDS[0];
    for (final var encoding : PrivateKeyEncoding.values()) {
      for (final var format : KeyFileFormat.values()) {
        final var dir = Files.createDirectories(tempDir.resolve(encoding + "-" + format));
        final var result = generateInto(seed++, dir, null, encoding, format, null);
        final var address = result.publicKey().toBase58();
        final var file = soleFile(dir);
        final var label = encoding + "/" + format;

        final var expectedExtension = format == KeyFileFormat.properties ? ".properties" : ".json";
        assertEquals(address + expectedExtension, file.getFileName().toString(), label);

        final var content = Files.readString(file);
        final String secret;
        if (format == KeyFileFormat.properties) {
          final var properties = properties(content);
          assertEquals(address, properties.getProperty("pubKey"), label);
          assertEquals(encoding.name(), properties.getProperty("encoding"), label);
          secret = properties.getProperty("secret");
        } else {
          assertTrue(content.contains("\"pubKey\": \"" + address + '"'), label + ": " + content);
          assertTrue(content.contains("\"encoding\": \"" + encoding.name() + '"'), label + ": " + content);
          secret = secretFromJson(content);
        }

        assertNotNull(secret, label);
        final var recovered = encoding.parseSecret(secret);
        assertEquals(result.publicKey(), recovered.publicKey(), label + " recovered a different address");
      }
    }
  }

  /// A seed holding 0 and 255 puts both byte boundaries inside a valid serialized pair.
  private static final byte[] BOUNDARY_KEY_PAIR = boundaryKeyPair();

  private static byte[] boundaryKeyPair() {
    final byte[] seed = new byte[Signer.KEY_LENGTH];
    Arrays.fill(seed, (byte) 7);
    seed[0] = 0;
    seed[1] = (byte) 0xFF;
    return Signer.createKeyPairBytesFromPrivateKey(seed);
  }

  private static String jsonArray(final byte[] keyPair, final String separator) {
    final var joiner = new StringJoiner(separator, "[", "]");
    for (final byte b : keyPair) {
      joiner.add(Integer.toString(Byte.toUnsignedInt(b)));
    }
    return joiner.toString();
  }

  private static void assertRejected(final String message, final String json) {
    final var error = assertThrows(IllegalArgumentException.class,
        () -> PrivateKeyEncoding.jsonKeyPairArray.parseSecret(json), json);
    assertEquals(message, error.getMessage(), json);
  }

  /// solana-sdk's `read_keypair` is the reference: the trimmed input is one array of exactly 64
  /// integers from 0 to 255, with whitespace allowed around each element.
  @Test
  void jsonKeyPairArrayAcceptsWhatTheSolanaCliReads() {
    final var expected = Signer.createFromKeyPair(BOUNDARY_KEY_PAIR).publicKey();
    for (final var json : List.of(
        jsonArray(BOUNDARY_KEY_PAIR, ","),  // the Solana CLI's and the vanity writer's form
        jsonArray(BOUNDARY_KEY_PAIR, ", "), // Python's json.dumps default
        " \n" + jsonArray(BOUNDARY_KEY_PAIR, ",\n  ") + "\n"
    )) {
      assertEquals(expected, PrivateKeyEncoding.jsonKeyPairArray.parseSecret(json).publicKey(), json);
    }
  }

  @Test
  void jsonKeyPairArrayRejectsWhatTheSolanaCliRejects() {
    final var valid = jsonArray(BOUNDARY_KEY_PAIR, ",");
    final var elements = valid.substring(1, valid.length() - 1);
    final var afterFirst = elements.substring(elements.indexOf(',') + 1);

    assertRejected("Input must be a JSON array", valid.substring(0, valid.length() - 1));
    assertRejected("Input must be a JSON array", "key=" + valid);
    assertRejected("Input must be a JSON array", elements);
    assertRejected("Input must be a JSON array", "[");
    assertRejected("Input must be a JSON array", "");
    assertRejected("Expected 64 elements, found 1", "[]");
    assertRejected("Expected 64 elements, found 63", "[" + afterFirst + "]");
    assertRejected("Expected 64 elements, found 65", "[" + elements + ",0]");
    assertRejected("Element 0 must be 0 to 255, found 256", "[256," + afterFirst + "]");
    assertRejected("Element 0 must be 0 to 255, found -1", "[-1," + afterFirst + "]");
    assertThrows(IllegalArgumentException.class,
        () -> PrivateKeyEncoding.jsonKeyPairArray.parseSecret("[x," + afterFirst + "]"));
  }

  /// The key-pair encodings carry the seed and the public key; the private-key
  /// encodings carry only the seed and must re-derive the address.
  @Test
  @Timeout(60)
  void keyPairEncodingsCarryTheFullPairAndPrivateOnesTheSeed(@TempDir final Path tempDir) throws IOException {
    final var pairDir = Files.createDirectories(tempDir.resolve("pair"));
    final var pairResult = generateInto(FixedSeedSecureRandom.SEEDS[0], pairDir, null, PrivateKeyEncoding.base58KeyPair, KeyFileFormat.properties, null);
    final var pairSecret = properties(Files.readString(soleFile(pairDir))).getProperty("secret");
    assertArrayEquals(pairResult.keyPair(), software.sava.core.encoding.Base58.decode(pairSecret));

    final var seedDir = Files.createDirectories(tempDir.resolve("seed"));
    final var seedResult = generateInto(FixedSeedSecureRandom.SEEDS[1], seedDir, null, PrivateKeyEncoding.base58PrivateKey, KeyFileFormat.properties, null);
    final var seedSecret = properties(Files.readString(soleFile(seedDir))).getProperty("secret");
    final byte[] seed = software.sava.core.encoding.Base58.decode(seedSecret);
    assertEquals(32, seed.length);
    assertArrayEquals(Arrays.copyOfRange(seedResult.keyPair(), 0, 32), seed);
  }

  /// The password path replaces the plaintext secret with a PBKDF envelope. The
  /// point of the test is that the envelope still decrypts to the same key pair.
  @Test
  @Timeout(120)
  void passwordProtectedFileDecryptsBackToTheKeyPair(@TempDir final Path tempDir) throws IOException {
    final var password = "correct horse battery staple".toCharArray();
    // the lowest count the KDF accepts — this is testing the plumbing, not the
    // work factor, and PBKDF2WithHmacSHA512 refuses anything under 500_000
    final var keyDerivation = KeyDerivation.createPBKDF2WithHmacSHA512(500_000);
    final var dir = Files.createDirectories(tempDir.resolve("encrypted"));

    final var result = generateInto(FixedSeedSecureRandom.SEEDS[2], dir, password, PrivateKeyEncoding.base58KeyPair, KeyFileFormat.properties, keyDerivation);
    final var file = soleFile(dir);
    assertEquals(result.publicKey().toBase58() + ".properties", file.getFileName().toString());

    final var content = Files.readString(file);
    // the raw secret must not be sitting in the file alongside the envelope
    assertFalse(content.contains(software.sava.core.encoding.Base58.encode(result.keyPair())),
        "plaintext key pair written to an encrypted key file");

    final var properties = properties(content);
    assertEquals(result.publicKey().toBase58(), properties.getProperty("pubKey"));
    final byte[] decrypted = PBKDFEncryption.decrypt("", properties, password);
    assertArrayEquals(result.keyPair(), decrypted);
  }

  /// Writing uses CREATE_NEW, so a collision surfaces rather than overwriting a
  /// key file that already exists.
  @Test
  @Timeout(60)
  void existingKeyFileIsNotOverwritten(@TempDir final Path tempDir) throws IOException {
    final var dir = Files.createDirectories(tempDir.resolve("collide"));
    final var result = generateInto(FixedSeedSecureRandom.SEEDS[3], dir, null, PrivateKeyEncoding.base58KeyPair, KeyFileFormat.json, null);
    final var file = soleFile(dir);
    final var original = Files.readString(file);

    // re-running a worker that lands on the same address would collide; simulate
    // it directly, since the address is what names the file
    assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> Files.writeString(
        file, "clobbered",
        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE));
    assertEquals(original, Files.readString(file));
    assertNotNull(result.publicKey());
  }
}
