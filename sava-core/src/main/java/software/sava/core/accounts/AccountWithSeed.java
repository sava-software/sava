package software.sava.core.accounts;

import static java.nio.charset.StandardCharsets.UTF_8;

public interface AccountWithSeed {

  static AccountWithSeed createAccount(final PublicKey baseKey,
                                       final PublicKey publicKey,
                                       final byte[] asciiSeed,
                                       final PublicKey program) {
    return new AccountWithSeedRecord(baseKey, publicKey, asciiSeed, program);
  }

  /** Creates account metadata carrying the UTF-8 encoding of {@code seed}. */
  static AccountWithSeed createAccount(final PublicKey baseKey,
                                       final PublicKey publicKey,
                                       final String seed,
                                       final PublicKey program) {
    return createAccount(
        baseKey,
        publicKey,
        seed.getBytes(UTF_8),
        program
    );
  }

  PublicKey baseKey();

  PublicKey publicKey();

  byte[] asciiSeed();

  PublicKey program();
}
