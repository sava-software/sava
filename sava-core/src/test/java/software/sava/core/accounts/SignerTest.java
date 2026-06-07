package software.sava.core.accounts;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.encoding.Base58;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.Signer.KEY_LENGTH;

final class SignerTest {

  @Test
  void generateKeyPair() {
    final byte[] keyPair = Signer.generatePrivateKeyPairBytes();
    final var expectedPublicKey = Base58.encode(Arrays.copyOfRange(keyPair, 32, 64));

    var signer = Signer.createFromKeyPair(Arrays.copyOf(keyPair, keyPair.length));
    assertEquals(signer.publicKey().toBase58(), expectedPublicKey);

    final byte[] privateKeyBytes = Arrays.copyOfRange(keyPair, 0, 32);
    signer = Signer.createFromPrivateKey(Arrays.copyOf(privateKeyBytes, privateKeyBytes.length));
    assertEquals(signer.publicKey().toBase58(), expectedPublicKey);

    assertArrayEquals(keyPair, Signer.createKeyPairBytesFromPrivateKey(privateKeyBytes));
  }

  @Test
  void accountFromSecretKey() {
    final byte[] secretKey = Base58.decode("4Z7cXSyeFR8wNGMVXUE1TwtKn5D5Vu7FzEv69dokLv7KrQk7h6pu4LF8ZRR9yQBhc7uSM6RTTZtU1fmaxiNrxXrs");
    assertEquals("QqCCvshxtqMAL2CVALqiJB7uEeE5mjSPsseQdDzsRUo", Signer.createFromKeyPair(secretKey).publicKey().toString());
  }

  @Test
  void recoverPublicKey() {
    final byte[] keyPairBytes = Signer.generatePrivateKeyPairBytes();
    final byte[] privatePublicCopy = Arrays.copyOf(keyPairBytes, keyPairBytes.length);
    final var signer = Signer.createFromKeyPair(keyPairBytes);
    assertArrayEquals(
        Arrays.copyOfRange(keyPairBytes, KEY_LENGTH, KEY_LENGTH << 1),
        signer.publicKey().toByteArray()
    );

    final byte[] publicKey = new byte[KEY_LENGTH];
    Ed25519.generatePublicKey(privatePublicCopy, 0, publicKey, 0);
    assertArrayEquals(publicKey, signer.publicKey().toByteArray());
  }

  @Test
  void bcSigVerify() {
    final var msg = "sava";
    final var keyPair = Signer.generatePrivateKeyPairBytes();
    final var signer = Signer.createFromKeyPair(keyPair);
    final var signature = signer.sign(msg.getBytes());

    final var publicKey = signer.publicKey();
    final boolean verified = publicKey.verifySignature(msg, signature);
    assertTrue(verified);
  }

  @Test
  void javaSigVerify() {
    final var msg = "sava";
    final var keyPair = Signer.generatePrivateKeyPairBytes();
    final var signer = Signer.createFromKeyPair(keyPair);
    final var signature = signer.sign(msg.getBytes());

    final var publicKey = signer.publicKey().toJavaPublicKey();
    final boolean verified = PublicKey.verifySignature(publicKey, msg, signature);
    assertTrue(verified);
  }

  private static void verifySigner(final byte[] keyPair, final Signer signer) {
    assertTrue(Arrays.equals(
        keyPair, Signer.KEY_LENGTH, Signer.KEY_LENGTH << 1,
        signer.publicKey().toByteArray(), 0, PublicKey.PUBLIC_KEY_LENGTH
    ));
  }

  private static Properties encryptedProperties(final byte[] payload,
                                                final char[] password,
                                                final KeyDerivation kdf) {
    final var encrypted = Signer.encryptKey(payload, password, kdf);
    final var props = new Properties(8);
    encrypted.addProperties(props);
    return props;
  }

  @Test
  void encryptedBase64KeyPairFromProperties() {
    final var keyPair = Signer.generatePrivateKeyPairBytes();
    final var password = "correct horse battery staple";
    final char[] passwordChars = password.toCharArray();
    final var props = encryptedProperties(keyPair, passwordChars, KeyDerivation.defaultPBKDF2WithHmacSHA512());
    final var signer = Signer.fromProperties(props, passwordChars);
    verifySigner(keyPair, signer);
    assertArrayEquals(password.toCharArray(), passwordChars);
  }

  @Test
  @ResourceLock("argon2id")
  void argon2EncryptedBase64KeyPairFromProperties() {
    final var keyPair = Signer.generatePrivateKeyPairBytes();
    final var password = "correct horse battery staple";
    final char[] passwordChars = password.toCharArray();
    final var props = encryptedProperties(keyPair, passwordChars, KeyDerivation.defaultArgon2id());
    final var signer = Signer.fromProperties(props, passwordChars);
    verifySigner(keyPair, signer);
    assertArrayEquals(password.toCharArray(), passwordChars);
  }

  @Test
  void encryptedFromPropertiesRequiresPassword() {
    final var props = encryptedProperties(Signer.generatePrivateKeyPairBytes(), "pw".toCharArray(), KeyDerivation.defaultPBKDF2WithHmacSHA512());
    assertThrows(IllegalArgumentException.class, () -> Signer.fromProperties(props, null));
  }

  @Test
  void encryptedFromPropertiesWrongPassword() {
    final var props = encryptedProperties(Signer.generatePrivateKeyPairBytes(), "right".toCharArray(), KeyDerivation.defaultPBKDF2WithHmacSHA512());
    assertThrows(IllegalStateException.class, () -> Signer.fromProperties(props, "wrong".toCharArray()));
  }

  @Test
  void encryptedFromPropertiesMissingPropertyFails() {
    for (final var missing : new String[]{"kdf", "iterations", "salt", "iv", "secret", "pubKey"}) {
      final var props = encryptedProperties(Signer.generatePrivateKeyPairBytes(), "pw".toCharArray(), KeyDerivation.defaultPBKDF2WithHmacSHA512());
      props.remove(missing);
      if (missing.equals("pubKey")) {
        props.remove("aad");
      }
      assertThrows(IllegalArgumentException.class,
          () -> Signer.fromProperties(props, "pw".toCharArray()),
          "Expected failure when '" + missing + "' is missing"
      );
    }
  }

  @Test
  @ResourceLock("argon2id")
  void argon2EncryptedFromPropertiesMissingPropertyFails() {
    for (final var missing : new String[]{"memoryKB", "parallelism"}) {
      final var props = encryptedProperties(Signer.generatePrivateKeyPairBytes(), "pw".toCharArray(), KeyDerivation.defaultArgon2id());
      props.remove(missing);
      assertThrows(IllegalArgumentException.class,
          () -> Signer.fromProperties(props, "pw".toCharArray()),
          "Expected failure when '" + missing + "' is missing"
      );
    }
  }

  @Test
  @ResourceLock("argon2id")
  void argon2EncryptedFromPropertiesWrongPassword() {
    final var props = encryptedProperties(Signer.generatePrivateKeyPairBytes(), "right".toCharArray(), KeyDerivation.defaultArgon2id());
    assertThrows(IllegalStateException.class, () -> Signer.fromProperties(props, "wrong".toCharArray()));
  }

  @Test
  void encryptedFromPropertiesLiteral() throws java.io.IOException {
    final var propsText = """
        pubKey=4bC4GcP7zzksyv9oeqJ3w7yHHLgkkpbsGKrmpYBLkr8U
        kdf=PBKDF2WithHmacSHA512
        iterations=210000
        aad=NVVPTFl+i9ZQ3b6Iq7KHFzCv2UkD+mFDUR5TGIoz7/0=
        salt=+OnLFicjZxX5UDQCwulAxA==
        cipher=AES/GCM/NoPadding
        iv=Mtl611cPAdqqBR4o
        secret=K/Mg2QuXKhhhrb6q37NoL7ZJe6zpWj9BXsTfvYgcRWWAq1UrfFY0yJPbfXIcFMxqOqu4gNygmt0G6mve3RoJgcHHsBRZMeDRodpQoMLU8dg=
        """;
    final var props = new Properties();
    props.load(new StringReader(propsText));
    final var signer = Signer.fromProperties(props, "asdf".toCharArray());
    assertEquals("4bC4GcP7zzksyv9oeqJ3w7yHHLgkkpbsGKrmpYBLkr8U", signer.publicKey().toBase58());
  }

  @Test
  @ResourceLock("argon2id")
  void argon2idEncryptedFromPropertiesLiteral() throws java.io.IOException {
    final var propsText = """
        pubKey=4bcagbEYKsdngabVBheRETcqrA9MYXEurdzAnk2J9BM3
        kdf=Argon2id
        iterations=3
        memoryKB=262144
        parallelism=4
        aad=NXEKIBcTEMP6g2MZdM13HFf1/CcRQHd7MnhMmmdPXSI=
        salt=BUDAce/8ez0Nne00lAQQGg==
        cipher=AES/GCM/NoPadding
        iv=a75VUXaxCkUNZ1rU
        secret=5ScUfduVAOsbEsH1+QRJwR76TKTEVLOK5m8/d5dvCHR8pax2i0alMLmUlpGOru6q/S+BZCP1qyZxCTOLP2TQaFZi06KLBaxvM48zeQ6YtEs=
        """;
    final var props = new Properties();
    props.load(new java.io.StringReader(propsText));
    final var signer = Signer.fromProperties(props, "asdf".toCharArray());
    assertEquals("4bcagbEYKsdngabVBheRETcqrA9MYXEurdzAnk2J9BM3", signer.publicKey().toBase58());
  }
}
