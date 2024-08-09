package software.sava.core.accounts;

import software.sava.core.encoding.Base58;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

public enum PrivateKeyEncoding {

  jsonKeyPairArray,
  base64PrivateKey,
  base64KeyPair,
  base58PrivateKey,
  base58KeyPair;

  private static final CharBufferFunction<PrivateKeyEncoding> JSON_PARSER = (buf, offset, len) -> {
    if (JsonIterator.fieldEquals("jsonKeyPairArray", buf, offset, len)) {
      return PrivateKeyEncoding.jsonKeyPairArray;
    } else if (JsonIterator.fieldEquals("base64PrivateKey", buf, offset, len)) {
      return PrivateKeyEncoding.base64PrivateKey;
    } else if (JsonIterator.fieldEquals("base64KeyPair", buf, offset, len)) {
      return PrivateKeyEncoding.base64KeyPair;
    } else if (JsonIterator.fieldEquals("base58PrivateKey", buf, offset, len)) {
      return PrivateKeyEncoding.base58PrivateKey;
    } else if (JsonIterator.fieldEquals("base58KeyPair", buf, offset, len)) {
      return PrivateKeyEncoding.base58KeyPair;
    } else {
      throw new IllegalArgumentException(new String(buf, offset, len) + " private key encoding is not supported.");
    }
  };

  public static Signer fromJsonArray(final JsonIterator ji) {
    final var privateKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; ji.readArray(); ) {
      privateKey[i] = (byte) ji.readInt();
      if (++i == 32) {
        break;
      }
    }
    final var publicKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; ji.readArray(); ) {
      publicKey[i++] = (byte) ji.readInt();
    }
    Signer.validateKeyPair(privateKey, publicKey);
    return new KeyPairSigner(publicKey, privateKey);
  }

  public static Signer fromJsonArray(final byte[] jsonArrayKeyPair) {
    final var ji = JsonIterator.parse(jsonArrayKeyPair);
    return fromJsonArray(ji);
  }

  public static Signer fromJsonPrivateKey(final JsonIterator ji,
                                          final PrivateKeyEncoding encoding) {
    return switch (encoding) {
      case jsonKeyPairArray -> fromJsonArray(ji);
      case base64PrivateKey -> Signer.createFromPrivateKey(ji.decodeBase64String());
      case base64KeyPair -> Signer.createFromKeyPair(ji.decodeBase64String());
      case base58PrivateKey -> Signer.createFromPrivateKey(ji.applyChars(Base58::decode));
      case base58KeyPair -> Signer.createFromKeyPair(ji.applyChars(Base58::decode));
    };
  }

  public static Signer fromJsonPrivateKey(final JsonIterator ji) {
    final int mark = ji.mark();
    final var encoding = ji.skipUntil("encoding").applyChars(JSON_PARSER);
    if (ji.skipUntil("secret") == null) {
      ji.reset(mark).skipUntil("secret");
    }
    return fromJsonPrivateKey(ji, encoding);
  }
}
