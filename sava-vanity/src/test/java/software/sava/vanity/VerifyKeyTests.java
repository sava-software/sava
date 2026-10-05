package software.sava.vanity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.accounts.pbkdf.PBKDFEncryption;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/// Saved-key recovery checks using only the public RFC 8032 test-vector secret.
/// The address is fixed independently of the file's metadata; a successful exit
/// must mean this exact key decrypted and signed, not just that parsing succeeded.
final class VerifyKeyTests {

  private static final String ADDRESS = "FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z";
  private static final String PASSWORD = "synthetic-verifier-password";
  private static final String SEED_HEX = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";

  private static final class TestRandom extends SecureRandom {
    private final Random random = new Random(42);

    @Override
    public void nextBytes(final byte[] bytes) {
      random.nextBytes(bytes);
    }
  }

  private record Result(int status, String out, String err) {
  }

  private static Result run(final String[] args, final Supplier<char[]> passwordSource) {
    return run(args, passwordSource, null);
  }

  private static Result run(final String[] args, final Supplier<char[]> passwordSource, final VerifyKey.Checks checks) {
    final var out = new ByteArrayOutputStream();
    final var err = new ByteArrayOutputStream();
    final int status;
    try (final var stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
         final var stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      status = checks == null
          ? VerifyKey.run(args, passwordSource, new TestRandom(), stdout, stderr)
          : VerifyKey.run(args, passwordSource, new TestRandom(), stdout, stderr, checks);
    }
    return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static Result run(final Path file, final String expected, final char[] password) {
    return run(new String[]{file.toString(), expected}, () -> password);
  }

  private static Path fixture(final Path dir, final KeyDerivation kdf, final boolean seedOnly) throws Exception {
    return fixture(dir, kdf, seedOnly, PASSWORD.toCharArray());
  }

  private static Path fixture(final Path dir, final KeyDerivation kdf, final boolean seedOnly,
                              final char[] password) throws Exception {
    final var seed = HexFormat.of().parseHex(SEED_HEX);
    final var keyPair = Signer.createKeyPairBytesFromPrivateKey(seed);
    final var signer = Signer.createFromKeyPair(keyPair);
    assertEquals(ADDRESS, signer.publicKey().toBase58());
    final var envelope = PBKDFEncryption.encrypt(password, new TestRandom(),
        seedOnly ? seed : keyPair, kdf, signer.publicKey().toByteArray());
    return Files.writeString(dir.resolve("saved key.properties"), envelope.toPropertiesString(signer.publicKey()));
  }

  private static Path fixture(final Path dir) throws Exception {
    return fixture(dir, KeyDerivation.createPBKDF2WithHmacSHA512(500_000), false);
  }

  private static void assertFailure(final Result result, final int status, final String reason) {
    assertEquals(status, result.status(), result.toString());
    assertEquals("", result.out(), "a failed check must not report success");
    // Exact output also catches accidental extra diagnostics containing any secret encoding.
    assertEquals(reason + System.lineSeparator(), result.err());
  }

  @Test
  @ResourceLock("argon2id")
  void bothKdfsAndSecretLengthsRecoverAndSignWithoutChangingTheFile(@TempDir final Path dir) throws Exception {
    for (final var kdf : new KeyDerivation[]{
        KeyDerivation.createPBKDF2WithHmacSHA512(500_000),
        KeyDerivation.createArgon2id(19_456, 1, 1)
    }) {
      for (final boolean seedOnly : new boolean[]{true, false}) {
        final var file = fixture(dir, kdf, seedOnly);
        final var before = Files.readAllBytes(file);
        final var password = PASSWORD.toCharArray();

        final var result = run(file, ADDRESS, password);

        assertEquals(0, result.status(), result.toString());
        assertEquals("", result.err());
        assertTrue(result.out().startsWith("PASS: Decrypted the saved key and verified signing for " + ADDRESS));
        assertTrue(result.out().contains("Bouncy Castle and JDK SunEC; both rejected a changed message."));
        assertFalse(result.out().contains(PASSWORD));
        assertFalse(result.out().contains(SEED_HEX));
        assertArrayEquals(new char[password.length], password, "clear the caller-owned password after decrypting");
        assertArrayEquals(before, Files.readAllBytes(file), "verification must leave the encrypted file intact");
      }
    }
  }

  @Test
  void wrongPasswordFailsAndIsCleared(@TempDir final Path dir) throws Exception {
    final var file = fixture(dir);
    final var password = "incorrect password".toCharArray();
    final var result = run(file, ADDRESS, password);
    assertFailure(result, 1, "FAIL: Could not decrypt and validate the saved key. Check the password and encrypted properties file.");
    assertArrayEquals(new char[password.length], password);
  }

  @Test
  void validButDifferentExpectedAddressFails(@TempDir final Path dir) throws Exception {
    final var file = fixture(dir);
    final var otherAddress = Signer.createFromPrivateKey(new byte[32]).publicKey().toBase58();
    assertNotEquals(ADDRESS, otherAddress);
    assertFailure(run(file, otherAddress, PASSWORD.toCharArray()), 1, "FAIL: The saved key does not match the expected public address.");
  }

  @Test
  void changedCiphertextOrStoredAddressFails(@TempDir final Path dir) throws Exception {
    final var file = fixture(dir);
    final var original = Files.readString(file);
    for (final var field : new String[]{"secret", "pubKey"}) {
      final var properties = new Properties();
      properties.load(new StringReader(original));
      if (field.equals("pubKey")) {
        properties.setProperty(field, Signer.createFromPrivateKey(new byte[32]).publicKey().toBase58());
      } else {
        final var encrypted = Base64.getDecoder().decode(properties.getProperty(field));
        encrypted[0] ^= 1;
        properties.setProperty(field, Base64.getEncoder().encodeToString(encrypted));
      }
      final var writer = new StringWriter();
      properties.store(writer, null);
      Files.writeString(file, writer.toString());
      assertFailure(run(file, ADDRESS, PASSWORD.toCharArray()), 1, "FAIL: Could not decrypt and validate the saved key. Check the password and encrypted properties file.");
    }
  }

  @Test
  void invalidArgumentsFailBeforeReadingAPassword() {
    final Supplier<char[]> noPassword = () -> fail("invalid arguments must not request a password");
    assertFailure(run(new String[0], noPassword), 2, "Usage: VerifyKey <encrypted.properties> <expected-public-address> [--legacy-docker-password]");
    assertFailure(run(new String[]{"unused", ADDRESS, "extra"}, noPassword), 2, "Usage: VerifyKey <encrypted.properties> <expected-public-address> [--legacy-docker-password]");
    for (final var address : new String[]{"jstg", "1", "1".repeat(33), "0".repeat(32), "z".repeat(44), " " + ADDRESS}) {
      assertFailure(run(new String[]{"unused", address}, noPassword), 2, "FAIL: Supply the full expected public address in canonical base58.");
    }
  }

  @Test
  void missingFileAndDirectoryFailBeforeReadingAPassword(@TempDir final Path dir) {
    final Supplier<char[]> noPassword = () -> fail("invalid files must not request a password");
    for (final var file : new Path[]{dir.resolve("missing.properties"), dir}) {
      assertFailure(run(new String[]{file.toString(), ADDRESS}, noPassword), 1, "FAIL: The key file must exist and be a regular file.");
    }
  }

  @Test
  void malformedPropertiesFailWithoutLeakingInput(@TempDir final Path dir) throws Exception {
    final var file = Files.writeString(dir.resolve("malformed.properties"), "secret=" + PASSWORD + "\\uZZZZ");
    assertFailure(run(new String[]{file.toString(), ADDRESS}, () -> fail("malformed file must not request a password")),
        1, "FAIL: Could not read the encrypted properties key file.");
  }

  @Test
  void plaintextPropertiesAreNotTreatedAsEncrypted(@TempDir final Path dir) throws Exception {
    final var file = Files.writeString(dir.resolve("plain.properties"), "pubKey=" + ADDRESS
        + "\nencoding=base64PrivateKey\nsecret="
        + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(SEED_HEX)));
    assertFailure(run(file, ADDRESS, PASSWORD.toCharArray()), 1, "FAIL: Could not decrypt and validate the saved key. Check the password and encrypted properties file.");
  }

  @Test
  void missingPasswordAndMissingConsoleFailClearly(@TempDir final Path dir) throws Exception {
    final var file = fixture(dir);
    assertFailure(run(file, ADDRESS, null), 1, "FAIL: An encryption password is required.");
    assertFailure(run(file, ADDRESS, new char[0]), 1, "FAIL: An encryption password is required.");
    assertFailure(run(new String[]{file.toString(), ADDRESS}, () -> {
      throw new IllegalStateException("no console");
    }), 1, "FAIL: Use an interactive terminal or set SAVA_VANITY_ENCRYPT_PASSWORD (wrapper: --passwordEnv=ENV_VAR_NAME).");
  }

  @Test
  void implicitEmptyPasswordEnvironmentFallsBackToPrompt() {
    for (final var environment : new String[]{null, ""}) {
      final var supplied = "prompt value".toCharArray();
      assertSame(supplied, VerifyKey.readPassword(environment, () -> supplied));
    }
    assertArrayEquals("  ".toCharArray(), VerifyKey.readPassword("  ", () -> fail("spaces are a real password")));
    assertArrayEquals("é🔑".toCharArray(), VerifyKey.readPassword("é🔑", () -> fail("environment is present")));
  }

  @Test
  void legacyMappingReplacesBytesNotCharacters() {
    final var original = "AéB€C🔑D".toCharArray();
    assertArrayEquals("A\uFFFD\uFFFDB\uFFFD\uFFFD\uFFFDC\uFFFD\uFFFD\uFFFD\uFFFDD".toCharArray(),
        VerifyKey.legacyDockerPassword(original));
    assertArrayEquals("AéB€C🔑D".toCharArray(), original, "helper borrows; run owns clearing");
    assertArrayEquals("ascii".toCharArray(), VerifyKey.legacyDockerPassword("ascii".toCharArray()));
    assertArrayEquals("\uFFFD\uFFFD\uFFFD".toCharArray(), VerifyKey.legacyDockerPassword("\uFFFD".toCharArray()));
  }

  @Test
  @ResourceLock("argon2id")
  void legacyRecoveryIsExplicitAndNormalUnicodeIsUnchanged(@TempDir final Path dir) throws Exception {
    final var original = "AéB€C🔑D";
    // Independent oracle: the historical JVM substituted once for each non-ASCII UTF-8 byte.
    final var historical = "A\uFFFD\uFFFDB\uFFFD\uFFFD\uFFFDC\uFFFD\uFFFD\uFFFD\uFFFDD";
    for (final var kdf : new KeyDerivation[]{KeyDerivation.createPBKDF2WithHmacSHA512(500_000),
        KeyDerivation.createArgon2id(19_456, 1, 1)}) {
      final var legacy = fixture(dir, kdf, false, historical.toCharArray());
      final var before = Files.readAllBytes(legacy);
      assertFailure(run(legacy, ADDRESS, original.toCharArray()), 1,
          "FAIL: Could not decrypt and validate the saved key. Check the password and encrypted properties file.");
      final var entered = original.toCharArray();
      final var converted = new AtomicReference<char[]>();
      final var checks = new VerifyKey.Checks((props, password) -> {
        converted.set(password);
        assertArrayEquals(historical.toCharArray(), password);
        return Signer.fromProperties(props, password);
      }, (key, message, signature) -> key.verifySignature(message, signature), VerifyKey::verifyWithJdk, 512L << 20);
      final var recovered = run(new String[]{legacy.toString(), ADDRESS, "--legacy-docker-password"}, () -> entered, checks);
      assertEquals(0, recovered.status(), recovered.err());
      assertTrue(recovered.out().contains("Legacy Docker password decoding was used explicitly."));
      assertArrayEquals(new char[entered.length], entered);
      assertArrayEquals(new char[converted.get().length], converted.get());
      assertArrayEquals(before, Files.readAllBytes(legacy));
      assertEquals(0, run(legacy, ADDRESS, historical.toCharArray()).status(), "literal replacement characters remain valid");
      assertFailure(run(new String[]{legacy.toString(), "11111111111111111111111111111111", "--legacy-docker-password"},
          () -> original.toCharArray()), 1, "FAIL: The saved key does not match the expected public address.");
      final var normal = fixture(dir, kdf, false, original.toCharArray());
      assertEquals(0, run(normal, ADDRESS, original.toCharArray()).status());
      assertFailure(run(new String[]{normal.toString(), ADDRESS, "--legacy-docker-password"}, () -> original.toCharArray()),
          1, "FAIL: Could not decrypt and validate the saved key. Check the password and encrypted properties file.");
    }
  }

  @Test
  void eachVerifierMustAcceptTheOriginalMessage(@TempDir final Path dir) throws Exception {
    final var file = fixture(dir);
    for (final boolean rejectBc : new boolean[]{true, false}) {
      final var bcCalls = new AtomicInteger();
      final var jdkCalls = new AtomicInteger();
      final var checks = new VerifyKey.Checks(Signer::fromProperties, (key, msg, sig) -> {
        bcCalls.incrementAndGet();
        return !rejectBc;
      }, (key, msg, sig) -> {
        jdkCalls.incrementAndGet();
        return rejectBc;
      }, 512L << 20);
      assertFailure(run(new String[]{file.toString(), ADDRESS}, () -> PASSWORD.toCharArray(), checks), 1,
          "FAIL: The saved key's signature did not pass both Ed25519 verifiers.");
      assertEquals(1, bcCalls.get());
      assertEquals(rejectBc ? 0 : 1, jdkCalls.get());
    }
  }

  @Test
  void neitherVerifierMayAcceptTheChangedMessage(@TempDir final Path dir) throws Exception {
    final var file = fixture(dir);
    for (final boolean faultyBc : new boolean[]{true, false}) {
      final VerifyKey.SignatureVerifier bc = faultyBc ? (_, _, _) -> true : (key, message, signature) -> key.verifySignature(message, signature);
      final VerifyKey.SignatureVerifier jdk = faultyBc ? VerifyKey::verifyWithJdk : (_, _, _) -> true;
      final var checks = new VerifyKey.Checks(Signer::fromProperties, bc, jdk, 512L << 20);
      assertFailure(run(new String[]{file.toString(), ADDRESS}, () -> PASSWORD.toCharArray(), checks), 1,
          "FAIL: An Ed25519 verifier accepted the signature for a changed message.");
    }
  }

  @Test
  void aSignatureFromAnotherPrivateKeyFailsTheSigningCheck(@TempDir final Path dir) throws Exception {
    final var file = fixture(dir);
    final var wrong = Signer.createFromPrivateKey(new byte[32]);
    final var checks = new VerifyKey.Checks((props, password) -> {
      final var genuine = Signer.fromProperties(props, password);
      // Deliberately mismatched factory overload: the declared identity matches but signing cannot.
      return Signer.createFromKeyPair(genuine.publicKey(), wrong.privateKey());
    }, (key, message, signature) -> key.verifySignature(message, signature), VerifyKey::verifyWithJdk, 512L << 20);
    assertFailure(run(new String[]{file.toString(), ADDRESS}, () -> PASSWORD.toCharArray(), checks), 1,
        "FAIL: The saved key's signature did not pass both Ed25519 verifiers.");
  }

  @Test
  void derivationMemoryBudgetIsCheckedBeforePasswordOrAllocation(@TempDir final Path dir) throws Exception {
    final var file = Files.writeString(dir.resolve("large.properties"),
        "kdf= Argon2id \nmemoryKB=524288\nparallelism=1\niterations=1\n");
    final var checks = new VerifyKey.Checks((_, _) -> fail("must not allocate"),
        (_, _, _) -> fail("must not verify"), (_, _, _) -> fail("must not verify"), 512L << 20);
    assertFailure(run(new String[]{file.toString(), ADDRESS}, () -> fail("preflight before prompt"), checks), 1,
        "FAIL: Insufficient heap for key derivation. Increase --maxHeap (or Java -Xmx) for this file's KDF settings.");
    Files.writeString(file, "kdf=argon2id\nmemoryKB=2147483647\nparallelism=1\niterations=1\n");
    assertFailure(run(new String[]{file.toString(), ADDRESS}, () -> fail("invalid parameters before prompt"), checks), 1,
        "FAIL: Invalid Argon2id parameters in the encrypted properties file.");
  }

  @Test
  void allocationFailureHasSafeDiagnosticAndClearsPassword(@TempDir final Path dir) throws Exception {
    final var file = fixture(dir);
    final var password = PASSWORD.toCharArray();
    final var checks = new VerifyKey.Checks((_, _) -> { throw new OutOfMemoryError("must not print exception detail"); },
        (key, message, signature) -> key.verifySignature(message, signature), VerifyKey::verifyWithJdk, 512L << 20);
    assertFailure(run(new String[]{file.toString(), ADDRESS}, () -> password, checks), 1,
        "FAIL: Insufficient heap for key derivation. Increase --maxHeap (or Java -Xmx) for this file's KDF settings.");
    assertArrayEquals(new char[password.length], password);
  }
}
