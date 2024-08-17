package software.sava.core.accounts;

import java.nio.charset.StandardCharsets;

public record AccountWithSeed(PublicKey baseKey,
                              PublicKey publicKey,
                              byte[] asciiSeed) {

  static AccountWithSeed createAccount(final PublicKey baseKey,
                                       final PublicKey publicKey,
                                       final String seed) {
    return new AccountWithSeed(baseKey, publicKey, seed.getBytes(StandardCharsets.US_ASCII));
  }
}
