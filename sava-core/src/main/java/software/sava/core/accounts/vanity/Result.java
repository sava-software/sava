package software.sava.core.accounts.vanity;

import software.sava.core.accounts.PublicKey;

public record Result(PublicKey publicKey, byte[] keyPair, long durationMillis) {

}
