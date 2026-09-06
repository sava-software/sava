package software.sava.rpc.json;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.*;

final class PrivateKeyEncodingTests {

  // RFC 8032 section 7.1, test 1: public test material, also used by Ed25519UtilTests.
  private static final byte[] EXPECTED_PUBLIC_KEY = HexFormat.of().parseHex(
      "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");

  private static String EXPECTED_PUB_KEY;

  private static String JSON_ARRAY;
  private static String BASE64_KEY_PAIR;
  private static String BASE64_PRIVATE_KEY;
  private static String BASE58_KEY_PAIR;
  private static String BASE58_PRIVATE_KEY;

  @BeforeAll
  static void setup() {
    final byte[] privateKey = HexFormat.of().parseHex(
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    final byte[] keyPair = Arrays.copyOf(privateKey, Signer.KEY_LENGTH << 1);
    System.arraycopy(EXPECTED_PUBLIC_KEY, 0, keyPair, Signer.KEY_LENGTH, Signer.KEY_LENGTH);
    EXPECTED_PUB_KEY = Base58.encode(EXPECTED_PUBLIC_KEY);

    final var joiner = new StringJoiner(",", "[", "]");
    for (final byte b : keyPair) {
      joiner.add(Integer.toString(Byte.toUnsignedInt(b)));
    }
    JSON_ARRAY = joiner.toString();

    BASE64_KEY_PAIR = Base64.getEncoder().encodeToString(keyPair);
    BASE64_PRIVATE_KEY = Base64.getEncoder().encodeToString(privateKey);
    BASE58_KEY_PAIR = Base58.encode(keyPair);
    BASE58_PRIVATE_KEY = Base58.encode(privateKey);
  }

  private static void verifySigner(final Signer signer) {
    assertArrayEquals(EXPECTED_PUBLIC_KEY, signer.publicKey().copyByteArray());
  }

  // --- JSON object tests ---

  @Test
  void jsonKeyPairArrayFromJson() {
    final var json = String.format("""
        {"encoding":"jsonKeyPairArray","secret":%s}""", JSON_ARRAY);
    final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
    verifySigner(PrivateKeyEncoding.fromJsonPrivateKey(ji));
  }

  @Test
  void jsonKeyPairArrayFromJsonWithPubKey() {
    final var json = String.format("""
        {"encoding":"jsonKeyPairArray","secret":%s,"pubKey":"%s"}""", JSON_ARRAY, EXPECTED_PUB_KEY);
    final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
    verifySigner(PrivateKeyEncoding.fromJsonPrivateKey(ji));
  }

  @Test
  void base64PrivateKeyFromJson() {
    final var json = String.format("""
        {"encoding":"base64PrivateKey","secret":"%s"}""", BASE64_PRIVATE_KEY);
    final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
    verifySigner(PrivateKeyEncoding.fromJsonPrivateKey(ji));
  }

  @Test
  void base64KeyPairFromJson() {
    final var json = String.format("""
        {"encoding":"base64KeyPair","secret":"%s"}""", BASE64_KEY_PAIR);
    final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
    verifySigner(PrivateKeyEncoding.fromJsonPrivateKey(ji));
  }

  @Test
  void base58PrivateKeyFromJson() {
    final var json = String.format("""
        {"encoding":"base58PrivateKey","secret":"%s"}""", BASE58_PRIVATE_KEY);
    final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
    verifySigner(PrivateKeyEncoding.fromJsonPrivateKey(ji));
  }

  @Test
  void base58KeyPairFromJson() {
    final var json = String.format("""
        {"encoding":"base58KeyPair","secret":"%s"}""", BASE58_KEY_PAIR);
    final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
    verifySigner(PrivateKeyEncoding.fromJsonPrivateKey(ji));
  }

  @Test
  void jsonArrayDirectParsing() {
    final var ji = JsonIterator.parse(JSON_ARRAY.getBytes(StandardCharsets.UTF_8));
    verifySigner(PrivateKeyEncoding.fromJsonPrivateKey(ji));
  }

  private record JsonSecret(PrivateKeyEncoding encoding, String value) {

    String jsonValue() {
      return encoding == PrivateKeyEncoding.jsonKeyPairArray ? value : "\"%s\"".formatted(value);
    }
  }

  private static List<JsonSecret> encodedJsonSecrets() {
    return List.of(
        new JsonSecret(PrivateKeyEncoding.jsonKeyPairArray, JSON_ARRAY),
        new JsonSecret(PrivateKeyEncoding.base64PrivateKey, BASE64_PRIVATE_KEY),
        new JsonSecret(PrivateKeyEncoding.base64KeyPair, BASE64_KEY_PAIR),
        new JsonSecret(PrivateKeyEncoding.base58PrivateKey, BASE58_PRIVATE_KEY),
        new JsonSecret(PrivateKeyEncoding.base58KeyPair, BASE58_KEY_PAIR)
    );
  }

  private static void assertImportedSignerAndOuterArrayCursor(final JsonIterator ji, final String encoding) {
    verifySigner(PrivateKeyEncoding.fromJsonPrivateKey(ji));
    assertTrue(ji.readArray(), encoding);
    assertEquals(73, ji.readInt(), encoding);
    assertFalse(ji.readArray(), encoding);
  }

  @Test
  void jsonEncodingBeforeSecretPreservesSignerAndOuterArrayCursor() {
    for (final var secret : encodedJsonSecrets()) {
      final var json = "[{\"pubKey\":\"%s\",\"encoding\":\"%s\",\"secret\":%s},73]"
          .formatted(EXPECTED_PUB_KEY, secret.encoding(), secret.jsonValue());
      final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
      assertTrue(ji.readArray());
      assertImportedSignerAndOuterArrayCursor(ji, secret.encoding().name());
    }
  }

  @Test
  void jsonSecretBeforeEncodingPreservesSignerAndOuterArrayCursor() {
    for (final var secret : encodedJsonSecrets()) {
      final var json = "[{\"pubKey\":\"%s\",\"secret\":%s,\"encoding\":\"%s\"},73]"
          .formatted(EXPECTED_PUB_KEY, secret.jsonValue(), secret.encoding());
      final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
      assertTrue(ji.readArray());
      assertImportedSignerAndOuterArrayCursor(ji, secret.encoding().name());
    }
  }

  @Test
  void jsonSecretBeforeEncodingWithoutPublicKeyPreservesOuterArrayCursor() {
    for (final var secret : encodedJsonSecrets()) {
      final var json = "[{\"secret\":%s,\"encoding\":\"%s\"},73]"
          .formatted(secret.jsonValue(), secret.encoding());
      final var ji = JsonIterator.parse(json);
      assertTrue(ji.readArray());
      assertImportedSignerAndOuterArrayCursor(ji, secret.encoding().name());
    }
  }

  @Test
  void jsonPublicKeyBetweenSecretAndEncodingPreservesSignerAndOuterArrayCursor() {
    for (final var secret : encodedJsonSecrets()) {
      final var json = "[{\"secret\":%s,\"pubKey\":\"%s\",\"encoding\":\"%s\"},73]"
          .formatted(secret.jsonValue(), EXPECTED_PUB_KEY, secret.encoding());
      final var ji = JsonIterator.parse(json);
      assertTrue(ji.readArray());
      assertImportedSignerAndOuterArrayCursor(ji, secret.encoding().name());
    }
  }

  @Test
  void jsonDeferredSecretRejectsAMismatchingPublicKeyAfterEncoding() {
    final String wrongPublicKey = "11111111111111111111111111111111";
    for (final var secret : encodedJsonSecrets()) {
      final var json = "{\"secret\":%s,\"encoding\":\"%s\",\"pubKey\":\"%s\"}"
          .formatted(secret.jsonValue(), secret.encoding(), wrongPublicKey);
      final var error = assertThrows(IllegalStateException.class,
          () -> PrivateKeyEncoding.fromJsonPrivateKey(JsonIterator.parse(json)), secret.encoding().name());
      assertEquals("[expected=%s] != [derived=%s]".formatted(wrongPublicKey, EXPECTED_PUB_KEY), error.getMessage());
    }
  }

  @Test
  void jsonSecretWithoutEncodingNamesTheRequiredEncoding() {
    for (final var secret : encodedJsonSecrets()) {
      final var json = "{\"secret\":%s}".formatted(secret.jsonValue());
      final var error = assertThrows(IllegalStateException.class,
          () -> PrivateKeyEncoding.fromJsonPrivateKey(JsonIterator.parse(json)), secret.encoding().name());
      assertEquals("Must configure 'encoding' field [jsonKeyPairArray, base64PrivateKey, base64KeyPair, base58PrivateKey, base58KeyPair]",
          error.getMessage());
    }
  }

  @Test
  void repeatedEncodingKeepsTheAlreadyImportedSignerForCompatibility() {
    // Preserve the handling shipped in 25.10.0; repeated fields are not a
    // recommended JSON representation, but a later encoding did not reinterpret a decoded key.
    final var json = "[{\"encoding\":\"base64PrivateKey\",\"secret\":\"%s\",\"encoding\":\"jsonKeyPairArray\"},73]"
        .formatted(BASE64_PRIVATE_KEY);
    final var ji = JsonIterator.parse(json);
    assertTrue(ji.readArray());
    assertImportedSignerAndOuterArrayCursor(ji, "base64PrivateKey");
  }

  @Test
  void anUnrecognizedFieldCannotSupplyTheRequiredSecret() {
    final var json = "{\"encoding\":\"base64PrivateKey\",\"notSecret\":\"%s\"}"
        .formatted(BASE64_PRIVATE_KEY);
    assertThrows(RuntimeException.class,
        () -> PrivateKeyEncoding.fromJsonPrivateKey(JsonIterator.parse(json)));
  }

  @Test
  void aClosedKeyArrayCannotImportBytesThatFollowIt() {
    final String[] bytes = JSON_ARRAY.substring(1, JSON_ARRAY.length() - 1).split(",");
    // All 64 values would make a valid pair if concatenated. Closing the array after
    // 31 values must prevent later tokens from supplying the rest of that pair.
    final var malformed = "[" + String.join(",", Arrays.copyOfRange(bytes, 0, 31)) + "]"
        + String.join(",", Arrays.copyOfRange(bytes, 31, bytes.length)) + "]";
    assertThrows(RuntimeException.class,
        () -> PrivateKeyEncoding.fromJsonArray(JsonIterator.parse(malformed)));
  }

  @Test
  void jsonObjectWithoutSecretOrEncodingNamesTheRequiredEncoding() {
    for (final var json : List.of("{}", "{\"pubKey\":\"%s\"}".formatted(EXPECTED_PUB_KEY))) {
      final var error = assertThrows(IllegalStateException.class,
          () -> PrivateKeyEncoding.fromJsonPrivateKey(JsonIterator.parse(json)));
      assertEquals("Must configure 'encoding' field [jsonKeyPairArray, base64PrivateKey, base64KeyPair, base58PrivateKey, base58KeyPair]",
          error.getMessage());
    }
  }

  @Test
  void jsonObjectWithEncodingButNoSecretIsRejected() {
    final var ji = JsonIterator.parse("{\"encoding\":\"base64PrivateKey\"}");
    assertThrows(IllegalStateException.class, () -> PrivateKeyEncoding.fromJsonPrivateKey(ji));
  }

  // --- Properties tests ---

  @Test
  void jsonKeyPairArrayFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "jsonKeyPairArray");
    props.setProperty("secret", JSON_ARRAY);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void base64PrivateKeyFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "base64PrivateKey");
    props.setProperty("secret", BASE64_PRIVATE_KEY);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void base64KeyPairFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "base64KeyPair");
    props.setProperty("secret", BASE64_KEY_PAIR);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void base58PrivateKeyFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "base58PrivateKey");
    props.setProperty("secret", BASE58_PRIVATE_KEY);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void base58KeyPairFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "base58KeyPair");
    props.setProperty("secret", BASE58_KEY_PAIR);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void fromPropertiesWithPrefix() {
    final var props = new Properties();
    props.setProperty("signer.encoding", "base58PrivateKey");
    props.setProperty("signer.secret", BASE58_PRIVATE_KEY);
    props.setProperty("signer.pubKey", EXPECTED_PUB_KEY);
    verifySigner(PrivateKeyEncoding.fromProperties("signer", props));
  }

  @Test
  void fromPropertiesWithDotSuffixedPrefix() {
    final var props = new Properties();
    props.setProperty("signer.encoding", "base64KeyPair");
    props.setProperty("signer.secret", BASE64_KEY_PAIR);
    verifySigner(PrivateKeyEncoding.fromProperties("signer.", props));
  }

  @Test
  void fromPropertiesWithPubKeyValidation() {
    final var props = new Properties();
    props.setProperty("encoding", "base58KeyPair");
    props.setProperty("secret", BASE58_KEY_PAIR);
    props.setProperty("pubKey", EXPECTED_PUB_KEY);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void propertiesTreatBlankPrefixesAsUnprefixed() {
    final var props = new Properties();
    props.setProperty("encoding", "base64PrivateKey");
    props.setProperty("secret", BASE64_PRIVATE_KEY);
    for (final var prefix : List.of("", " ", "\t\n", "\u2003")) {
      verifySigner(PrivateKeyEncoding.fromProperties(prefix, props));
    }
  }

  @Test
  void propertiesStripWhitespaceAroundEncodingSecretAndPublicKey() {
    final var props = new Properties();
    props.setProperty("signer.encoding", "\u2003 base64PrivateKey\t");
    props.setProperty("signer.secret", "\n" + BASE64_PRIVATE_KEY + " \u2003");
    props.setProperty("signer.pubKey", "\t" + EXPECTED_PUB_KEY + "\u2003");
    verifySigner(PrivateKeyEncoding.fromProperties("signer", props));
  }

  @Test
  void propertiesRejectBlankEncodingWithThePropertyName() {
    for (final var blank : List.of("", " \t", "\u2003")) {
      final var props = new Properties();
      props.setProperty("signer.encoding", blank);
      props.setProperty("signer.secret", BASE64_PRIVATE_KEY);
      final var error = assertThrows(IllegalArgumentException.class,
          () -> PrivateKeyEncoding.fromProperties("signer", props));
      assertEquals("Missing required property: signer.encoding", error.getMessage());
    }
  }

  @Test
  void propertiesRejectBlankSecretWithThePropertyName() {
    for (final var blank : List.of("", " \t", "\u2003")) {
      final var props = new Properties();
      props.setProperty("signer.encoding", "base64PrivateKey");
      props.setProperty("signer.secret", blank);
      final var error = assertThrows(IllegalArgumentException.class,
          () -> PrivateKeyEncoding.fromProperties("signer", props));
      assertEquals("Missing required property: signer.secret", error.getMessage());
    }
  }

  @Test
  void fromPropertiesMissingEncoding() {
    final var props = new Properties();
    props.setProperty("secret", BASE58_PRIVATE_KEY);
    assertThrows(IllegalArgumentException.class, () -> PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void fromPropertiesMissingSecret() {
    final var props = new Properties();
    props.setProperty("encoding", "base58PrivateKey");
    assertThrows(IllegalArgumentException.class, () -> PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void fromPropertiesWrongPubKey() {
    final var props = new Properties();
    props.setProperty("encoding", "base58PrivateKey");
    props.setProperty("secret", BASE58_PRIVATE_KEY);
    props.setProperty("pubKey", "11111111111111111111111111111111");
    assertThrows(IllegalStateException.class, () -> PrivateKeyEncoding.fromProperties(props));
  }

  // --- JSON with wrong pubKey ---

  @Test
  void jsonWithWrongPubKeyThrows() {
    final var json = String.format("""
        {"encoding":"base58PrivateKey","secret":"%s","pubKey":"%s"}""",
        BASE58_PRIVATE_KEY, "11111111111111111111111111111111");
    final var ji = JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalStateException.class, () -> PrivateKeyEncoding.fromJsonPrivateKey(ji));
  }

  // --- parseSecret tests ---

  @Test
  void parseSecretJsonKeyPairArray() {
    verifySigner(PrivateKeyEncoding.jsonKeyPairArray.parseSecret(JSON_ARRAY));
  }

  @Test
  void parseSecretBase64PrivateKey() {
    verifySigner(PrivateKeyEncoding.base64PrivateKey.parseSecret(BASE64_PRIVATE_KEY));
  }

  @Test
  void parseSecretBase64KeyPair() {
    verifySigner(PrivateKeyEncoding.base64KeyPair.parseSecret(BASE64_KEY_PAIR));
  }

  @Test
  void parseSecretBase58PrivateKey() {
    verifySigner(PrivateKeyEncoding.base58PrivateKey.parseSecret(BASE58_PRIVATE_KEY));
  }

  @Test
  void parseSecretBase58KeyPair() {
    verifySigner(PrivateKeyEncoding.base58KeyPair.parseSecret(BASE58_KEY_PAIR));
  }
}
