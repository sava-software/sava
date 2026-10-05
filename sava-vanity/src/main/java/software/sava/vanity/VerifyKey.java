package software.sava.vanity;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.pbkdf.KeyDerivation;

import java.io.PrintStream;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/// Offline recovery and signing check for a saved encrypted properties key.
/// The expected address must come from the caller, independently of the key file.
public final class VerifyKey {

  private static final String PASSWORD_ENV = "SAVA_VANITY_ENCRYPT_PASSWORD";
  private static final String MEMORY_FAILURE =
      "FAIL: Insufficient heap for key derivation. Increase --maxHeap (or Java -Xmx) for this file's KDF settings.";

  @FunctionalInterface
  interface SignatureVerifier {
    boolean verify(PublicKey key, byte[] message, byte[] signature) throws GeneralSecurityException;
  }

  /// Package-private seams let tests require both verifiers and failure handling.
  record Checks(BiFunction<Properties, char[], Signer> loadSigner,
                SignatureVerifier bouncyCastle,
                SignatureVerifier jdk,
                long maxHeap) {
  }

  public static void main(final String[] args) {
    final int status = run(args, VerifyKey::readPassword, new SecureRandom(), System.out, System.err);
    if (status != 0) {
      System.exit(status);
    }
  }

  private static char[] readPassword() {
    return readPassword(System.getenv(PASSWORD_ENV), () -> {
      final var console = System.console();
      if (console == null) {
        throw new IllegalStateException("No interactive console");
      }
      return console.readPassword("Encryption password: ");
    });
  }

  static char[] readPassword(final String environment, final Supplier<char[]> prompt) {
    return environment == null || environment.isEmpty() ? prompt.get() : environment.toCharArray();
  }

  /// Returns 0 on success, 1 on a failed check, or 2 for invalid arguments.
  /// Exception messages from key parsing/decryption are deliberately not printed.
  static int run(final String[] args,
                 final Supplier<char[]> passwordSource,
                 final SecureRandom random,
                 final PrintStream out,
                 final PrintStream err) {
    return run(args, passwordSource, random, out, err, new Checks(
        Signer::fromProperties, (key, message, signature) -> key.verifySignature(message, signature), VerifyKey::verifyWithJdk,
        Runtime.getRuntime().maxMemory()
    ));
  }

  static int run(final String[] args,
                 final Supplier<char[]> passwordSource,
                 final SecureRandom random,
                 final PrintStream out,
                 final PrintStream err,
                 final Checks checks) {
    if (args.length != 2 && (args.length != 3 || !args[2].equals("--legacy-docker-password"))) {
      err.println("Usage: VerifyKey <encrypted.properties> <expected-public-address> [--legacy-docker-password]");
      return 2;
    }
    final boolean legacyPassword = args.length == 3;

    final PublicKey expected;
    try {
      // Bound the input before decoding; the decoder itself requires exactly 32 bytes.
      if (args[1].length() < 32 || args[1].length() > 44) {
        throw new IllegalArgumentException();
      }
      expected = PublicKey.fromBase58Encoded(args[1]);
      if (!expected.toBase58().equals(args[1])) {
        throw new IllegalArgumentException();
      }
    } catch (final RuntimeException _) {
      err.println("FAIL: Supply the full expected public address in canonical base58.");
      return 2;
    }

    final var properties = new Properties();
    try {
      final var keyFile = Path.of(args[0]);
      if (!Files.isRegularFile(keyFile)) {
        err.println("FAIL: The key file must exist and be a regular file.");
        return 1;
      }
      try (final var reader = Files.newBufferedReader(keyFile, StandardCharsets.UTF_8)) {
        properties.load(reader);
      }
    } catch (final AccessDeniedException _) {
      err.println("FAIL: Could not read the key file. Check file permissions and the Docker user mapping.");
      return 1;
    } catch (final Exception _) {
      err.println("FAIL: Could not read the encrypted properties key file.");
      return 1;
    }

    try {
      if ("argon2id".equalsIgnoreCase(properties.getProperty("kdf", "").strip())) {
        final int memoryKb = Integer.parseInt(properties.getProperty("memoryKB", "").strip());
        KeyDerivation.createArgon2id(memoryKb,
            Integer.parseInt(properties.getProperty("parallelism", "").strip()),
            Integer.parseInt(properties.getProperty("iterations", "").strip()));
        // Same headroom as the generator; this is a preflight, not an allocation guarantee.
        if (memoryKb > 0 && ((long) memoryKb << 10) + (128L << 20) > checks.maxHeap()) {
          err.println(MEMORY_FAILURE);
          return 1;
        }
      }
    } catch (final IllegalArgumentException _) {
      err.println("FAIL: Invalid Argon2id parameters in the encrypted properties file.");
      return 1;
    }

    char[] password = null;
    final Signer signer;
    try {
      try {
        password = passwordSource.get();
      } catch (final RuntimeException _) {
        err.println("FAIL: Use an interactive terminal or set SAVA_VANITY_ENCRYPT_PASSWORD (wrapper: --passwordEnv=ENV_VAR_NAME).");
        return 1;
      }
      if (password == null || password.length == 0) {
        err.println("FAIL: An encryption password is required.");
        return 1;
      }
      if (legacyPassword) {
        final var original = password;
        password = legacyDockerPassword(original);
        Arrays.fill(original, '\0');
      }
      signer = checks.loadSigner().apply(properties, password);
    } catch (final OutOfMemoryError _) {
      // Narrowly handle KDF allocation failure; do not turn other JVM errors into success.
      err.println(MEMORY_FAILURE);
      return 1;
    } catch (final Exception _) {
      err.println("FAIL: Could not decrypt and validate the saved key. Check the password and encrypted properties file.");
      return 1;
    } finally {
      if (password != null) {
        Arrays.fill(password, '\0');
      }
    }

    if (!expected.equals(signer.publicKey())) {
      err.println("FAIL: The saved key does not match the expected public address.");
      return 1;
    }

    try {
      final var nonce = new byte[32];
      random.nextBytes(nonce);
      final var message = ("Sava local saved-key signing check v1\nAddress: " + expected.toBase58()
          + "\nNonce: " + HexFormat.of().formatHex(nonce)).getBytes(StandardCharsets.US_ASCII);
      final var signature = signer.sign(message);
      if (signature.length != 64
          || !checks.bouncyCastle().verify(expected, message, signature)
          || !checks.jdk().verify(expected, message, signature)) {
        err.println("FAIL: The saved key's signature did not pass both Ed25519 verifiers.");
        return 1;
      }
      message[0] ^= 1;
      if (checks.bouncyCastle().verify(expected, message, signature) || checks.jdk().verify(expected, message, signature)) {
        err.println("FAIL: An Ed25519 verifier accepted the signature for a changed message.");
        return 1;
      }
    } catch (final Exception _) {
      err.println("FAIL: Could not complete the offline signing check.");
      return 1;
    }

    out.println("PASS: Decrypted the saved key and verified signing for " + expected.toBase58());
    out.println("Verified with Bouncy Castle and JDK SunEC; both rejected a changed message.");
    if (legacyPassword) {
      out.println("Legacy Docker password decoding was used explicitly. The file remains unchanged; this does not prove the original Unicode password was unique.");
    }
    return 0;
  }

  /// Reproduces only the historical Docker UTF-8 environment-byte -> ASCII replacement path.
  /// Never called as a fallback and never used when creating new key files.
  static char[] legacyDockerPassword(final char[] original) {
    final var bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(original));
    try {
      final var recovered = new char[bytes.remaining()];
      for (int i = 0; i < recovered.length; ++i) {
        final byte value = bytes.get();
        recovered[i] = value < 0 ? '\uFFFD' : (char) value;
      }
      return recovered;
    } finally {
      Arrays.fill(bytes.array(), (byte) 0);
    }
  }

  static boolean verifyWithJdk(final PublicKey publicKey,
                                       final byte[] message,
                                       final byte[] signature) throws GeneralSecurityException {
    final var verifier = Signature.getInstance("Ed25519", "SunEC");
    verifier.initVerify(publicKey.toJavaPublicKey());
    verifier.update(message);
    return verifier.verify(signature);
  }

  private VerifyKey() {
  }
}
