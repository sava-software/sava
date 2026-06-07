package software.sava.core.accounts.pbkdf;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.util.Arrays;
import java.util.Properties;

record Argon2id(int memoryKB,
                int parallelism,
                int iterations) implements KeyDerivation {

  // Argon2id parameters. Hardened well above the OWASP minimum (19-46 MiB) toward the RFC 9106
  // recommendations. RFC 9106's FIRST RECOMMENDED option (2 GiB, t=1, p=4) is impractical for a
  // containerized/CLI run, so we use a strong middle ground: 256 MiB, t=3, p=4. This is 4x the
  // memory of the OWASP/RFC memory-constrained baseline and matches RFC 9106's parallelism.
  static final int ARGON2_MEMORY_KB = 262_144;
  static final int ARGON2_PARALLELISM = 4;
  static final int ARGON2_ITERATIONS = 3;

  // Bounds applied to attacker-controllable parameters read from a key file. The lower bounds keep
  // the derivation strong (no silently-weak KDF) and the upper bounds guard against
  // memory/CPU exhaustion (DoS) when loading an externally-provided file.
  static final int MIN_MEMORY_KB = 19_456;       // OWASP Argon2id floor (19 MiB).
  static final int MAX_MEMORY_KB = 2_097_152;     // RFC 9106 first-recommended ceiling (2 GiB).
  static final int MIN_PARALLELISM = 1;
  static final int MAX_PARALLELISM = 16;
  static final int MIN_ITERATIONS = 1;
  static final int MAX_ITERATIONS = 100;

  static final Argon2id DEFAULT = new Argon2id(ARGON2_MEMORY_KB, ARGON2_PARALLELISM, ARGON2_ITERATIONS);

  Argon2id {
    if (memoryKB < MIN_MEMORY_KB || memoryKB > MAX_MEMORY_KB) {
      throw new IllegalArgumentException(String.format(
          "Argon2id memoryKB must be within [%d, %d] but was %d", MIN_MEMORY_KB, MAX_MEMORY_KB, memoryKB));
    }
    if (parallelism < MIN_PARALLELISM || parallelism > MAX_PARALLELISM) {
      throw new IllegalArgumentException(String.format(
          "Argon2id parallelism must be within [%d, %d] but was %d", MIN_PARALLELISM, MAX_PARALLELISM, parallelism));
    }
    if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
      throw new IllegalArgumentException(String.format(
          "Argon2id iterations must be within [%d, %d] but was %d", MIN_ITERATIONS, MAX_ITERATIONS, iterations));
    }
  }

  @Override
  public byte[] derive(final char[] password, final byte[] salt, final int keyBits) {
    final byte[] passwordBytes = PBKDFEncryption.toUtf8Bytes(password);
    try {
      final var params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
          .withVersion(Argon2Parameters.ARGON2_VERSION_13)
          .withSalt(salt)
          .withMemoryAsKB(memoryKB)
          .withParallelism(parallelism)
          .withIterations(iterations)
          .build();
      final var generator = new Argon2BytesGenerator();
      generator.init(params);
      final byte[] keyBytes = new byte[keyBits / 8];
      generator.generateBytes(passwordBytes, keyBytes);
      return keyBytes;
    } finally {
      Arrays.fill(passwordBytes, (byte) 0);
    }
  }

  @Override
  public String toJson() {
    return String.format(
        """
            {
              "kdf": "Argon2id",
              "iterations": %d,
              "memoryKB": %d,
              "parallelism": %d
            }""",
        iterations,
        memoryKB,
        parallelism
    );
  }

  @Override
  public String toPropertiesString() {
    return String.format(
        """
            kdf=Argon2id
            iterations=%d
            memoryKB=%d
            parallelism=%d
            """,
        iterations,
        memoryKB,
        parallelism
    );
  }

  @Override
  public void addProperties(final Properties properties) {
    properties.put("kdf", "Argon2id");
    properties.put("iterations", Integer.toString(iterations));
    properties.put("memoryKB", Integer.toString(memoryKB));
    properties.put("parallelism", Integer.toString(parallelism));
  }
}
