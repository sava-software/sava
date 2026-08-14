package software.sava.core.accounts;

public interface AccountWithSeed {

  static AccountWithSeed createAccount(final PublicKey baseKey,
                                       final PublicKey publicKey,
                                       final byte[] asciiSeed,
                                       final PublicKey program) {
    return new AccountWithSeedRecord(baseKey, publicKey, asciiSeed, program);
  }

  /**
   * Creates account metadata carrying the UTF-8 encoding of {@code seed}.
   *
   * @throws IllegalArgumentException if {@code seed} contains an unpaired UTF-16 surrogate
   */
  static AccountWithSeed createAccount(final PublicKey baseKey,
                                       final PublicKey publicKey,
                                       final String seed,
                                       final PublicKey program) {
    return createAccount(
        baseKey,
        publicKey,
        PublicKeyBytes.encodeUtf8Seed(seed),
        program
    );
  }

  PublicKey baseKey();

  PublicKey publicKey();

  byte[] asciiSeed();

  PublicKey program();
}
