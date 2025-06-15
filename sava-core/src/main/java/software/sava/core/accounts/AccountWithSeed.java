package software.sava.core.accounts;

import java.nio.charset.StandardCharsets;

public interface AccountWithSeed {

  static AccountWithSeed createAccount(final PublicKey baseKey,
                                       final PublicKey publicKey,
                                       final byte[] asciiSeed,
                                       final PublicKey program) {
    return new AccountWithSeedRecord(baseKey, publicKey, asciiSeed, program);
  }

  static AccountWithSeed createAccount(final PublicKey baseKey,
                                       final PublicKey publicKey,
                                       final String seed,
                                       final PublicKey program) {
    return createAccount(
        baseKey,
        publicKey,
        seed.getBytes(StandardCharsets.US_ASCII),
        program
    );
  }

  PublicKey baseKey();

  PublicKey publicKey();

  byte[] asciiSeed();

  PublicKey program();
}
