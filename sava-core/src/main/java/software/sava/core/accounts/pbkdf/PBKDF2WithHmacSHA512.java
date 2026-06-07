package software.sava.core.accounts.pbkdf;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Properties;

public record PBKDF2WithHmacSHA512(int iterations) implements KeyDerivation {

  // OWASP recommended minimum for PBKDF2-HMAC-SHA512.
  static final int ITERATIONS = 210_000;

  static final PBKDF2WithHmacSHA512 DEFAULT = new PBKDF2WithHmacSHA512(ITERATIONS);

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
