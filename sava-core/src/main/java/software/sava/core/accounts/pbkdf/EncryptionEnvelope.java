package software.sava.core.accounts.pbkdf;

import software.sava.core.accounts.PublicKey;

import java.util.Base64;

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

  public String toProperties() {
    return toProperties((String) null);
  }

  public String toProperties(final PublicKey publicKey) {
    return toProperties("pubKey=" + publicKey.toBase58());
  }

  public String toProperties(final String prefix) {
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
        keyDerivation.toProperties().strip(),
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
