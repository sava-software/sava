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

  static final Argon2id DEFAULT = new Argon2id(ARGON2_MEMORY_KB, ARGON2_PARALLELISM, ARGON2_ITERATIONS);

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
