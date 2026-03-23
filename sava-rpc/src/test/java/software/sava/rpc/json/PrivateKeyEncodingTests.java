package software.sava.rpc.json;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.*;

final class PrivateKeyEncodingTests {

  private static String EXPECTED_PUB_KEY;

  private static String JSON_ARRAY;
  private static String BASE64_KEY_PAIR;
  private static String BASE64_PRIVATE_KEY;
  private static String BASE58_KEY_PAIR;
  private static String BASE58_PRIVATE_KEY;

  @BeforeAll
  static void setup() {
    final byte[] keyPair = Signer.generatePrivateKeyPairBytes();
    final byte[] privateKey = Arrays.copyOfRange(keyPair, 0, Signer.KEY_LENGTH);
    final var signer = Signer.createFromKeyPair(keyPair);
    EXPECTED_PUB_KEY = signer.publicKey().toBase58();

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
    assertEquals(EXPECTED_PUB_KEY, signer.publicKey().toBase58());
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
