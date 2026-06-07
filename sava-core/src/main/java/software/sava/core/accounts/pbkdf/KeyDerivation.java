package software.sava.core.accounts.pbkdf;

import java.util.Properties;

public interface KeyDerivation {

  static KeyDerivation createPBKDF2WithHmacSHA512(final int iterations) {
    return new PBKDF2WithHmacSHA512(iterations);
  }

  static KeyDerivation defaultPBKDF2WithHmacSHA512() {
    return PBKDF2WithHmacSHA512.DEFAULT;
  }

  static KeyDerivation createArgon2id(final int memoryKb, final int parallelism, final int iterations) {
    return new Argon2id(memoryKb, parallelism, iterations);
  }

  static KeyDerivation defaultArgon2id() {
    return Argon2id.DEFAULT;
  }

  /**
   * Derives a key from the given password. Implementations must scrub any internal
   * copies of the password they create, but MUST NOT clear the caller's
   * {@code password} array — ownership of that array remains with the caller.
   */
  byte[] derive(final char[] password, final byte[] salt, final int keyBits);

  String toJson();

  String toPropertiesString();

  void addProperties(final Properties properties);

  int iterations();
}
