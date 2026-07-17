package software.sava.rpc.json;

import software.sava.core.accounts.PublicKey;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.FieldBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

public final class PublicKeyEncoding {

  public static CharBufferFunction<PublicKey> PARSE_BASE58_PUBLIC_KEY = PublicKey::fromBase58Encoded;

  private static final FieldBufferFunction<PublicKey> PARSE_BASE58_FIELD_NAME =
      (buf, offset, len, _) -> PublicKey.fromBase58Encoded(buf, offset, len);

  public static PublicKey parseBase58Encoded(final JsonIterator ji) {
    return ji.applyChars(PARSE_BASE58_PUBLIC_KEY);
  }

  /// Constructs the key from the next field name, leaving the iterator on the
  /// field's value; returns null at the end of the object.
  public static PublicKey parseObjectFieldBase58Encoded(final JsonIterator ji) {
    return ji.applyObject(PARSE_BASE58_FIELD_NAME);
  }

  public static PublicKey parseIntArrayEncoded(final JsonIterator ji) {
    final byte[] publicKey = new byte[PUBLIC_KEY_LENGTH];
    ji.readByteArray(publicKey);
    return PublicKey.createPubKey(publicKey);
  }

  private PublicKeyEncoding() {
  }
}
