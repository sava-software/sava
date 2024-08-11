package software.sava.rpc.json;

import software.sava.core.accounts.PublicKey;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

public final class PublicKeyEncoding {

  public static CharBufferFunction<PublicKey> PARSE_BASE58_PUBLIC_KEY = PublicKey::fromBase58Encoded;

  public static PublicKey parseBase58Encoded(final JsonIterator ji) {
    return ji.applyChars(PARSE_BASE58_PUBLIC_KEY);
  }

  private PublicKeyEncoding() {
  }
}
