package software.sava.core.accounts.pbkdf;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Defensive regression for sava's encrypted-key serializer: a strict, independent
/// JSON parser must read the entire envelope and recover its KDF metadata and bytes.
/// No encryption is needed to verify this wire format.
final class EncryptionEnvelopeJsonTests {

  private static EncryptionEnvelope envelope(final KeyDerivation kdf, final byte[] aad) {
    return new EncryptionEnvelope(kdf, aad, new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, new byte[]{7, 8, 9});
  }

  private static JsonObject parse(final String json) throws IOException {
    try (final var reader = new JsonReader(new StringReader(json))) {
      reader.setStrictness(Strictness.STRICT);
      final var parsed = JsonParser.parseReader(reader).getAsJsonObject();
      assertEquals(JsonToken.END_DOCUMENT, reader.peek(), "trailing data after the envelope");
      return parsed;
    }
  }

  private static void assertEncodedBytes(final JsonObject json) {
    assertEquals("AQID", json.get("salt").getAsString());
    assertEquals("BAUG", json.get("iv").getAsString());
    assertEquals("BwgJ", json.get("secret").getAsString());
  }

  @Test
  void pbkdf2MetadataIsAJsonObjectRatherThanUnescapedText() throws IOException {
    final var envelope = envelope(KeyDerivation.createPBKDF2WithHmacSHA512(543_210), null);
    final var json = parse(envelope.toJson());

    assertEquals(Set.of("kdf", "salt", "iv", "secret"), json.keySet());
    final var kdf = json.getAsJsonObject("kdf");
    assertEquals(Set.of("kdf", "iterations"), kdf.keySet());
    assertEquals("PBKDF2WithHmacSHA512", kdf.get("kdf").getAsString());
    assertEquals(543_210, kdf.get("iterations").getAsInt());
    assertEncodedBytes(json);
  }

  @Test
  void argon2MetadataIsAJsonObjectRatherThanUnescapedText() throws IOException {
    final var envelope = envelope(KeyDerivation.createArgon2id(32_768, 2, 4), new byte[]{10, 11, 12});
    final var json = parse(envelope.toJson());

    assertEquals(Set.of("kdf", "aad", "salt", "iv", "secret"), json.keySet());
    final var kdf = json.getAsJsonObject("kdf");
    assertEquals(Set.of("kdf", "iterations", "memoryKB", "parallelism"), kdf.keySet());
    assertEquals("Argon2id", kdf.get("kdf").getAsString());
    assertEquals(32_768, kdf.get("memoryKB").getAsInt());
    assertEquals(2, kdf.get("parallelism").getAsInt());
    assertEquals(4, kdf.get("iterations").getAsInt());
    assertEquals("CgsM", json.get("aad").getAsString());
    assertEncodedBytes(json);
  }

  @Test
  void publicKeyOverloadAddsTheAddressAlongsideTheEnvelope() throws IOException {
    final var address = "4bcoVWVXfw6xKsEYdM6s7AeZQMgDG958kK5Uzhc2sw37";
    final var envelope = envelope(KeyDerivation.createPBKDF2WithHmacSHA512(543_210), new byte[]{10, 11, 12});
    final var json = parse(envelope.toJson(PublicKey.fromBase58Encoded(address)));

    assertEquals(Set.of("pubKey", "kdf", "aad", "salt", "iv", "secret"), json.keySet());
    assertEquals(address, json.get("pubKey").getAsString());
    assertEquals("PBKDF2WithHmacSHA512", json.getAsJsonObject("kdf").get("kdf").getAsString());
    assertEquals("CgsM", json.get("aad").getAsString());
    assertEncodedBytes(json);
  }

  @Test
  void blankPrefixesAndAbsentAadDoNotAddFields() throws IOException {
    for (final var aad : new byte[][]{null, new byte[0]}) {
      final var envelope = envelope(KeyDerivation.createPBKDF2WithHmacSHA512(543_210), aad);
      for (final var prefix : new String[]{null, "", " \n\t", "\u2003"}) {
        final var json = parse(envelope.toJson(prefix));
        assertEquals(Set.of("kdf", "salt", "iv", "secret"), json.keySet());
        assertEncodedBytes(json);
      }
    }
  }

  @Test
  void nonblankPrefixAddsJsonMembersWithoutQuotingThem() throws IOException {
    final var envelope = envelope(KeyDerivation.createPBKDF2WithHmacSHA512(543_210), null);
    for (final var prefix : List.of("\"label\": \"saved key\",", " \n\"label\": \"saved key\",\n ")) {
      final var json = parse(envelope.toJson(prefix));
      assertEquals(Set.of("label", "kdf", "salt", "iv", "secret"), json.keySet());
      assertEquals("saved key", json.get("label").getAsString());
      assertEncodedBytes(json);
    }
  }
}
