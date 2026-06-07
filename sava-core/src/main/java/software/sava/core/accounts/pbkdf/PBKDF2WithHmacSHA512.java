package software.sava.core.accounts.pbkdf;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Properties;

record PBKDF2WithHmacSHA512(int iterations) implements KeyDerivation {

  // Lower bound used when reading attacker-controllable parameters so a corrupted/malicious file
  // cannot silently request a weak (cheap to brute-force) derivation. This sits well above the
  // OWASP interactive floor (210_000) because this KDF protects a long-lived key at rest and runs
  // only once per key-file load/save, so a stronger floor is affordable.
  static final int MIN_ITERATIONS = 800_000;

  // Default iteration count for newly produced key files, calibrated for the offline/at-rest
  // threat model rather than the much lower OWASP interactive minimum.
  static final int DEFAULT_ITERATIONS = 2_100_000;

  // Upper bound to cap the CPU cost an externally-provided file can force on us (DoS guard).
  static final int MAX_ITERATIONS = 100_000_000;

  static final PBKDF2WithHmacSHA512 DEFAULT = new PBKDF2WithHmacSHA512(DEFAULT_ITERATIONS);

  PBKDF2WithHmacSHA512 {
    if (iterations < MIN_ITERATIONS) {
      throw new IllegalArgumentException(String.format(
          "PBKDF2WithHmacSHA512 iterations must be at least %d but was %d", MIN_ITERATIONS, iterations));
    }
    if (iterations > MAX_ITERATIONS) {
      throw new IllegalArgumentException(String.format(
          "PBKDF2WithHmacSHA512 iterations must be at most %d but was %d", MAX_ITERATIONS, iterations));
    }
  }

  @Override
  public byte[] derive(final char[] password, final byte[] salt, final int keyBits) {
    final var keySpec = new PBEKeySpec(password, salt, iterations, keyBits);
    try {
      final var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
      return factory.generateSecret(keySpec).getEncoded();
    } catch (final NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new RuntimeException(e);
    } finally {
      keySpec.clearPassword();
    }
  }

  @Override
  public String toJson() {
    return String.format(
        """
            {
              "kdf": "PBKDF2WithHmacSHA512",
              "iterations": %d
            }""",
        iterations
    );
  }

  @Override
  public String toPropertiesString() {
    return String.format(
        """
            kdf=PBKDF2WithHmacSHA512
            iterations=%d
            """,
        iterations
    );
  }

  @Override
  public void addProperties(final Properties properties) {
    properties.put("kdf", "PBKDF2WithHmacSHA512");
    properties.put("iterations", Integer.toString(iterations));
  }
}
