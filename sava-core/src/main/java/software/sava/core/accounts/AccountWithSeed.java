package software.sava.core.accounts;

public interface AccountWithSeed {

  static AccountWithSeed createAccount(final PublicKey baseKey,
                                       final PublicKey publicKey,
                                       final byte[] asciiSeed,
                                       final PublicKey program) {
    return new AccountWithSeedRecord(baseKey, publicKey, asciiSeed, program);
  }

  /// Creates account metadata carrying the UTF-8 encoding of `seed`.
  ///
  /// @throws IllegalArgumentException if `seed` contains an unpaired UTF-16 surrogate
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

  /// The raw seed bytes. An account from these factories returns its stored array, not a copy,
  /// and the `byte[]` factory stores its argument without copying. Despite the name the bytes
  /// need not be ASCII: [#createAccount(PublicKey, PublicKey, String, PublicKey)] stores UTF-8.
  byte[] asciiSeed();

  PublicKey program();
}
