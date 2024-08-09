package software.sava.core.accounts;

import java.nio.charset.StandardCharsets;

public record AccountWithSeed(PublicKey publicKey, byte[] asciiSeed) {

  static AccountWithSeed createAccount(final PublicKey publicKey, final String seed) {
    return new AccountWithSeed(publicKey, seed.getBytes(StandardCharsets.US_ASCII));
  }
}
