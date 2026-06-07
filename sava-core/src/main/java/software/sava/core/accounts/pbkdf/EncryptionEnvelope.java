package software.sava.core.accounts.pbkdf;

import software.sava.core.accounts.PublicKey;

import java.util.Base64;
import java.util.Properties;

public record EncryptionEnvelope(KeyDerivation keyDerivation,
                                 byte[] aad,
                                 byte[] salt,
                                 byte[] iv,
                                 byte[] cipherText) {

  public byte[] decrypt(final char[] password) {
    return PBKDFEncryption.decrypt(
        password,
        keyDerivation,
        aad,
        salt,
        iv,
        cipherText
    );
  }

  public void addProperties(final Properties properties) {
    keyDerivation.addProperties(properties);
    final var encoder = Base64.getEncoder();
    properties.put("aad", encoder.encodeToString(aad));
    properties.put("salt", encoder.encodeToString(salt));
    properties.put("iv", encoder.encodeToString(iv));
    properties.put("secret", encoder.encodeToString(cipherText));
  }

  public String toPropertiesString() {
    return toPropertiesString((String) null);
  }

  public String toPropertiesString(final PublicKey publicKey) {
    return toPropertiesString("pubKey=" + publicKey.toBase58());
  }

  public String toPropertiesString(final String prefix) {
    final var encoder = Base64.getEncoder();
    return String.format(
        """
            %s
            %s
            aad=%s
            salt=%s
            iv=%s
            secret=%s
            """,
        prefix == null || prefix.isBlank() ? "" : prefix.strip(),
        keyDerivation.toPropertiesString().strip(),
        encoder.encodeToString(aad),
        encoder.encodeToString(salt),
        encoder.encodeToString(iv),
        encoder.encodeToString(cipherText)
    );
  }

  public String toJson() {
    return toJson((String) null);
  }

  public String toJson(final PublicKey publicKey) {
    return toJson(String.format("""
        "pubKey": "%s",
        """, publicKey.toBase58()
    ));
  }

  public String toJson(final String prefix) {
    final var encoder = Base64.getEncoder();
    return String.format(
        """
            {
            %s
              "kdf": "%s",
              "aad": "%s",
              "salt": "%s",
              "iv": "%s",
              "secret": "%s"
            }""",
        prefix == null || prefix.isBlank() ? "" : prefix.indent(2).strip(),
        keyDerivation.toJson().indent(2).strip(),
        encoder.encodeToString(aad),
        encoder.encodeToString(salt),
        encoder.encodeToString(iv),
        encoder.encodeToString(cipherText)
    );
  }
}
