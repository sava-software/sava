package software.sava.vanity.fixture;

import software.sava.core.accounts.Signer;
import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.accounts.pbkdf.PBKDFEncryption;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

/// Defensive runtime fixtures for password input and recovery testing.
/// The seed is the public RFC 8032 first Ed25519 test vector, never a user key.
/// These deterministic encrypted files are public test data and must never hold funds.
public final class RuntimeKeyFixture {

  private static final String ADDRESS = "FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z";
  private static final String SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
  private static final String ASCII_PASSWORD = "synthetic-runtime-password";
  private static final String UNICODE_PASSWORD = "AéB€C🔑D";
  // Independent oracle: two, three and four replacement characters respectively.
  // Do not call the implementation's legacy conversion to construct this fixture.
  private static final String LEGACY_PASSWORD = "A\uFFFD\uFFFDB\uFFFD\uFFFD\uFFFDC\uFFFD\uFFFD\uFFFD\uFFFDD";

  private static final class FixtureRandom extends SecureRandom {
    private final Random random = new Random(42);

    @Override
    public void nextBytes(final byte[] bytes) {
      random.nextBytes(bytes);
    }
  }

  public static void main(final String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Supply a public test-fixture output directory.");
    }
    final var directory = Path.of(args[0]);
    Files.createDirectories(directory);
    final var seed = HexFormat.of().parseHex(SEED);
    final var signer = Signer.createFromPrivateKey(seed);
    if (!ADDRESS.equals(signer.publicKey().toBase58())) {
      throw new IllegalStateException("RFC 8032 public test-vector address did not match.");
    }
    final var names = new String[]{"pbkdf2", "argon2id"};
    final var kdfs = new KeyDerivation[]{
        KeyDerivation.createPBKDF2WithHmacSHA512(500_000),
        KeyDerivation.createArgon2id(19_456, 1, 1)
    };
    final var variants = new String[]{"ascii", "unicode", "legacy"};
    final var passwords = new String[]{ASCII_PASSWORD, UNICODE_PASSWORD, LEGACY_PASSWORD};
    try {
      for (int kdf = 0; kdf < kdfs.length; ++kdf) {
        for (int variant = 0; variant < variants.length; ++variant) {
          final var password = passwords[variant].toCharArray();
          try {
            final var envelope = PBKDFEncryption.encrypt(password, new FixtureRandom(), seed,
                kdfs[kdf], signer.publicKey().toByteArray());
            Files.writeString(directory.resolve(names[kdf] + '-' + variants[variant] + ".properties"),
                envelope.toPropertiesString(signer.publicKey()), StandardCharsets.UTF_8);
          } finally {
            Arrays.fill(password, '\0');
          }
        }
      }
      Files.writeString(directory.resolve("expected-address.txt"), ADDRESS + '\n', StandardCharsets.US_ASCII);
    } finally {
      Arrays.fill(seed, (byte) 0);
    }
  }

  private RuntimeKeyFixture() {
  }
}
