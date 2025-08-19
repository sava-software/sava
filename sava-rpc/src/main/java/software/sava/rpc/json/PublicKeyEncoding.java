package software.sava.rpc.json;

import software.sava.core.accounts.PublicKey;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

public final class PublicKeyEncoding {

  public static CharBufferFunction<PublicKey> PARSE_BASE58_PUBLIC_KEY = PublicKey::fromBase58Encoded;

  public static PublicKey parseBase58Encoded(final JsonIterator ji) {
    return ji.applyChars(PARSE_BASE58_PUBLIC_KEY);
  }

  public static PublicKey parseObjectFieldBase58Encoded(final JsonIterator ji) {
    return ji.applyObjField(PARSE_BASE58_PUBLIC_KEY);
  }

  public static PublicKey parseIntArrayEncoded(final JsonIterator ji) {
    final byte[] publicKey = new byte[PUBLIC_KEY_LENGTH];
    int i = 0;
    for (; ji.readArray(); ++i) {
      publicKey[i] = (byte) ji.readInt();
    }
    return PublicKey.createPubKey(publicKey);
  }

  private PublicKeyEncoding() {
  }
}
