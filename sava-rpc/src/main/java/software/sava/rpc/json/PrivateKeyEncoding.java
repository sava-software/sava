package software.sava.rpc.json;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public enum PrivateKeyEncoding {

  jsonKeyPairArray,
  base64PrivateKey,
  base64KeyPair,
  base58PrivateKey,
  base58KeyPair;

  private static final FieldMatcher ENCODINGS = FieldMatcher.of(
      "jsonKeyPairArray",
      "base64PrivateKey",
      "base64KeyPair",
      "base58PrivateKey",
      "base58KeyPair"
  );

  private static final CharBufferFunction<PrivateKeyEncoding> JSON_PARSER = (buf, offset, len) -> switch (ENCODINGS.match(buf, offset, len)) {
    case 0 -> PrivateKeyEncoding.jsonKeyPairArray;
    case 1 -> PrivateKeyEncoding.base64PrivateKey;
    case 2 -> PrivateKeyEncoding.base64KeyPair;
    case 3 -> PrivateKeyEncoding.base58PrivateKey;
    case 4 -> PrivateKeyEncoding.base58KeyPair;
    default ->
        throw new IllegalArgumentException(new String(buf, offset, len) + " private key encoding is not supported.");
  };

  /// Reads a Solana CLI key-pair array: the 32 private-key bytes followed by the 32 public-key
  /// bytes.
  ///
  /// @throws IllegalArgumentException if the array does not hold exactly 64 elements
  /// @throws IllegalStateException    if the public key does not match the private key
  public static Signer fromJsonArray(final JsonIterator ji) {
    final var keyPair = new byte[Signer.KEY_LENGTH << 1];
    int length = 0;
    while (ji.readArray()) {
      final int value = ji.readInt();
      if (length < keyPair.length) {
        keyPair[length] = (byte) value;
      }
      ++length;
    }
    if (length != keyPair.length) {
      throw new IllegalArgumentException("Expected " + keyPair.length + " elements, found " + length);
    }
    return Signer.createFromKeyPair(keyPair);
  }

  /// Parses `jsonArrayKeyPair` as [#fromJsonArray(JsonIterator)] does.
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

  /// Imports a key-pair array, or an object with `encoding` and `secret` fields and an optional
  /// `pubKey` checked against the derived key. Object fields may appear in any order and
  /// unrecognized ones are skipped. On success the iterator is left immediately after the
  /// imported value. If `encoding` is repeated, a secret decoded before a later encoding is not
  /// reinterpreted.
  ///
  /// @throws IllegalStateException if the value is neither an array nor an object, `encoding` or
  /// `secret` is missing (the message names it), or `pubKey` does not match the derived key
  /// @throws IllegalArgumentException if a key-pair array does not hold exactly 64 elements (as
  /// [#fromJsonArray(JsonIterator)] throws), or `encoding` names no [PrivateKeyEncoding] constant
  public static Signer fromJsonPrivateKey(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case ARRAY -> fromJsonPrivateKey(ji, jsonKeyPairArray);
      case OBJECT -> {
        final var parser = new Parser();
        ji.testObject(parser);
        yield parser.createSigner(ji);
      }
      default -> throw new IllegalStateException("Must be a JSON object or private key pair array.");
    };
  }

  public Signer parseSecret(final String secret) {
    return switch (this) {
      case jsonKeyPairArray -> fromJsonArray(secret.getBytes(StandardCharsets.US_ASCII));
      case base64PrivateKey -> Signer.createFromPrivateKey(Base64.getDecoder().decode(secret));
      case base64KeyPair -> Signer.createFromKeyPair(Base64.getDecoder().decode(secret));
      case base58PrivateKey -> Signer.createFromPrivateKey(Base58.decode(secret));
      case base58KeyPair -> Signer.createFromKeyPair(Base58.decode(secret));
    };
  }

  public static Signer fromProperties(final String prefix, final Properties properties) {
    final var resolvedPrefix = prefix == null || prefix.isBlank()
        ? ""
        : prefix.endsWith(".") ? prefix : prefix + ".";
    final var encodingValue = properties.getProperty(resolvedPrefix + "encoding");
    if (encodingValue == null || encodingValue.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + resolvedPrefix + "encoding");
    }
    final var encoding = PrivateKeyEncoding.valueOf(encodingValue.strip());
    final var secret = properties.getProperty(resolvedPrefix + "secret");
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + resolvedPrefix + "secret");
    }
    final var signer = encoding.parseSecret(secret.strip());
    final var pubKeyValue = properties.getProperty(resolvedPrefix + "pubKey");
    if (pubKeyValue != null && !pubKeyValue.isBlank()) {
      final var publicKey = PublicKey.fromBase58Encoded(pubKeyValue.strip());
      if (!publicKey.equals(signer.publicKey())) {
        throw new IllegalStateException(String.format("[expected=%s] != [derived=%s]", publicKey, signer.publicKey()));
      }
    }
    return signer;
  }

  public static Signer fromProperties(final Properties properties) {
    return fromProperties(null, properties);
  }

  private static final class Parser implements FieldBufferPredicate {

    private PublicKey publicKey;
    private PrivateKeyEncoding encoding;
    private Signer signer;
    private int secretMark;

    Signer createSigner(final JsonIterator ji) {
      if (signer == null) {
        if (encoding == null) {
          throw new IllegalStateException("Must configure 'encoding' field " + Arrays.toString(PrivateKeyEncoding.values()));
        }
        if (secretMark == 0) {
          throw new IllegalStateException("Must configure 'secret' field");
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
          ji.skip();
        } else {
          signer = fromJsonPrivateKey(ji, encoding);
        }
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
