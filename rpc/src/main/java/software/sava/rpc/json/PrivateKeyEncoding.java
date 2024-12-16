package software.sava.rpc.json;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Arrays;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public enum PrivateKeyEncoding {

  jsonKeyPairArray,
  base64PrivateKey,
  base64KeyPair,
  base58PrivateKey,
  base58KeyPair;

  private static final CharBufferFunction<PrivateKeyEncoding> JSON_PARSER = (buf, offset, len) -> {
    if (fieldEquals("jsonKeyPairArray", buf, offset, len)) {
      return PrivateKeyEncoding.jsonKeyPairArray;
    } else if (fieldEquals("base64PrivateKey", buf, offset, len)) {
      return PrivateKeyEncoding.base64PrivateKey;
    } else if (fieldEquals("base64KeyPair", buf, offset, len)) {
      return PrivateKeyEncoding.base64KeyPair;
    } else if (fieldEquals("base58PrivateKey", buf, offset, len)) {
      return PrivateKeyEncoding.base58PrivateKey;
    } else if (fieldEquals("base58KeyPair", buf, offset, len)) {
      return PrivateKeyEncoding.base58KeyPair;
    } else {
      throw new IllegalArgumentException(new String(buf, offset, len) + " private key encoding is not supported.");
    }
  };

  public static Signer fromJsonArray(final JsonIterator ji) {
    final var privateKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; ji.readArray(); ) {
      privateKey[i] = (byte) ji.readInt();
      if (++i == Signer.KEY_LENGTH) {
        break;
      }
    }
    final var publicKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; ji.readArray(); ) {
      publicKey[i++] = (byte) ji.readInt();
    }
    return Signer.createFromKeyPair(publicKey, privateKey);
  }

  public static Signer fromJsonArray(final byte[] jsonArrayKeyPair) {
    final var ji = JsonIterator.parse(jsonArrayKeyPair);
    return fromJsonArray(ji);
  }

  public static Signer fromJsonPrivateKey(final JsonIterator ji, final PrivateKeyEncoding encoding) {
    return switch (encoding) {
      case jsonKeyPairArray -> fromJsonArray(ji);
      case base64PrivateKey -> Signer.createFromPrivateKey(ji.decodeBase64String());
      case base64KeyPair -> Signer.createFromKeyPair(ji.decodeBase64String());
      case base58PrivateKey -> Signer.createFromPrivateKey(ji.applyChars(Base58::decode));
      case base58KeyPair -> Signer.createFromKeyPair(ji.applyChars(Base58::decode));
    };
  }

  public static Signer fromJsonPrivateKey(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.createSigner(ji);
  }

  private static final class Parser implements FieldBufferPredicate {

    private PublicKey publicKey;
    private PrivateKeyEncoding encoding;
    private Signer signer;
    private int secretMark;

    Signer createSigner(final JsonIterator ji) {
      if (signer == null) {
        if (secretMark == 0) {
          throw new IllegalStateException("Must configure 'encoding' field " + Arrays.toString(PrivateKeyEncoding.values()));
        }
        final int mark = ji.mark();
        signer = fromJsonPrivateKey(ji.reset(secretMark), encoding);
        ji.reset(mark);
      }
      if (publicKey != null && !publicKey.equals(signer.publicKey())) {
        throw new IllegalStateException(String.format("[expected=%s] != [derived=%s]", publicKey, signer.publicKey()));
      }
      return signer;
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("pubKey", buf, offset, len)) {
        publicKey = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("encoding", buf, offset, len)) {
        encoding = ji.applyChars(JSON_PARSER);
      } else if (fieldEquals("secret", buf, offset, len)) {
        if (encoding == null) {
          secretMark = ji.mark();
        } else {
          signer = fromJsonPrivateKey(ji, encoding);
        }
      }
      return true;
    }
  }
}
