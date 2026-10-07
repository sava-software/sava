package software.sava.core.accounts;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Transaction;

import javax.crypto.AEADBadTagException;
import java.io.StringReader;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.Signer.KEY_LENGTH;

final class SignerTest {

  // Use the minimum supported PBKDF2 iteration count for tests so the (otherwise expensive)
  // key derivation stays fast; the security floor itself is exercised by the rejects* tests.
  private static final int MIN_PBKDF2_ITERATIONS = 500_000;

  private static KeyDerivation minPBKDF2() {
    return KeyDerivation.createPBKDF2WithHmacSHA512(MIN_PBKDF2_ITERATIONS);
  }

  /// A fixed 64-byte Solana key pair, private half then public half, whose public key is
  /// [#FIXED_PUBLIC_KEY]. Tests whose subject is not key generation start from it, so their
  /// inputs are the same on every run.
  private static final String FIXED_KEY_PAIR = "4Z7cXSyeFR8wNGMVXUE1TwtKn5D5Vu7FzEv69dokLv7KrQk7h6pu4LF8ZRR9yQBhc7uSM6RTTZtU1fmaxiNrxXrs";
  private static final String FIXED_PUBLIC_KEY = "QqCCvshxtqMAL2CVALqiJB7uEeE5mjSPsseQdDzsRUo";
  /// The RFC 8032 section 7.1 TEST 2 public key: a valid Ed25519 key that belongs to a different
  /// private key than [#FIXED_KEY_PAIR]'s.
  private static final String UNRELATED_PUBLIC_KEY = "586Z7H2vpX9qNhN2T4e9Utugie3ogjbxzGaMtM3E6HR5";

  private static byte[] fixedKeyPair() {
    return Base58.decode(FIXED_KEY_PAIR);
  }

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
    final byte[] secretKey = fixedKeyPair();
    assertEquals(FIXED_PUBLIC_KEY, Signer.createFromKeyPair(secretKey).publicKey().toString());
  }

  @Test
  void recoverPublicKey() {
    final byte[] keyPairBytes = fixedKeyPair();
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
    final var keyPair = fixedKeyPair();
    final var signer = Signer.createFromKeyPair(keyPair);
    final var signature = signer.sign(msg.getBytes());

    final var publicKey = signer.publicKey();
    final boolean verified = publicKey.verifySignature(msg, signature);
    assertTrue(verified);
  }

  @Test
  void javaSigVerify() {
    final var msg = "sava";
    final var keyPair = fixedKeyPair();
    final var signer = Signer.createFromKeyPair(keyPair);
    final var signature = signer.sign(msg.getBytes());

    final var publicKey = signer.publicKey().toJavaPublicKey();
    final boolean verified = PublicKey.verifySignature(publicKey, msg, signature);
    assertTrue(verified);
  }

  /** The JDK Ed25519 verifier is independent of Sava's raw-key Bouncy Castle path. */
  @Test
  void stringVerificationUsesTheEntireUtf8Message() {
    final var msg = "é";
    final byte[] utf8 = msg.getBytes(UTF_8);
    assertTrue(utf8.length > msg.length(), "the regression requires byte and UTF-16 lengths to differ");

    final var signer = Signer.createFromPrivateKey(new byte[KEY_LENGTH]);
    final var publicKey = signer.publicKey();
    final byte[] signature = signer.sign(utf8);

    assertTrue(publicKey.verifySignature(utf8, signature));
    assertTrue(PublicKey.verifySignature(publicKey.toJavaPublicKey(), msg, signature));
    assertTrue(PublicKey.verifySignature(publicKey.toByteArray(), 0, msg, signature));
    assertTrue(PublicKey.verifySignature(publicKey.toByteArray(), msg, signature));
    assertTrue(publicKey.verifySignature(msg, signature));

    final byte[] prefixSignature = signer.sign(Arrays.copyOf(utf8, msg.length()));
    assertFalse(publicKey.verifySignature(utf8, prefixSignature));
    assertFalse(PublicKey.verifySignature(publicKey.toJavaPublicKey(), msg, prefixSignature));
    assertFalse(PublicKey.verifySignature(publicKey.toByteArray(), 0, msg, prefixSignature));
    assertFalse(PublicKey.verifySignature(publicKey.toByteArray(), msg, prefixSignature));
    assertFalse(publicKey.verifySignature(msg, prefixSignature));
  }

  /// Two generated key pairs differ and neither private half is all zero: the private half is
  /// drawn from the secure random source rather than left as the zero-filled buffer. The library
  /// offers no seam for a seeded source here, so this draws from the real one; a collision or an
  /// all-zero draw has probability around 2^-256, which makes the outcome deterministic in
  /// practice. Each public half is the key the private half derives, checked by the library's
  /// own validation and by constructing a signer.
  @Test
  void generatedKeyPairsAreDistinctWithNonZeroPrivateHalves() {
    final byte[] first = Signer.generatePrivateKeyPairBytes();
    final byte[] second = Signer.generatePrivateKeyPairBytes();
    assertEquals(KEY_LENGTH << 1, first.length);
    assertEquals(KEY_LENGTH << 1, second.length);
    assertFalse(Arrays.equals(first, second));

    final byte[] zeroKey = new byte[KEY_LENGTH];
    for (final byte[] keyPair : new byte[][]{first, second}) {
      assertFalse(Arrays.equals(keyPair, 0, KEY_LENGTH, zeroKey, 0, KEY_LENGTH));
      final var signer = Signer.createFromKeyPair(keyPair);
      assertArrayEquals(Arrays.copyOfRange(keyPair, KEY_LENGTH, KEY_LENGTH << 1), signer.publicKey().toByteArray());
    }
  }

  /// Two generated private keys differ, are [Signer#KEY_LENGTH] bytes, and are not all zero,
  /// for the same reason and with the same probability argument as the key-pair test above.
  @Test
  void generatedPrivateKeysAreDistinctAndNonZero() {
    final byte[] first = Signer.generatePrivateKeyBytes();
    final byte[] second = Signer.generatePrivateKeyBytes();
    assertEquals(KEY_LENGTH, first.length);
    assertEquals(KEY_LENGTH, second.length);
    assertFalse(Arrays.equals(first, second));
    assertFalse(Arrays.equals(new byte[KEY_LENGTH], first));
    assertFalse(Arrays.equals(new byte[KEY_LENGTH], second));
  }

  /// A 64-byte key pair whose public half differs from the key its private half derives, here
  /// by one bit, is rejected with an [IllegalStateException] naming the derived key, both by
  /// [Signer#createFromKeyPair(byte[])] and by [Signer#validateKeyPair(byte[])]. Accepting it
  /// would produce a signer whose advertised public key cannot verify its signatures.
  @Test
  void keyPairWithAFlippedPublicBitIsRejected() {
    final byte[] keyPair = fixedKeyPair();
    keyPair[KEY_LENGTH] ^= 1;

    final var fromKeyPair = assertThrowsExactly(IllegalStateException.class, () -> Signer.createFromKeyPair(keyPair));
    assertTrue(fromKeyPair.getMessage().endsWith(" <> " + FIXED_PUBLIC_KEY), fromKeyPair.getMessage());

    final var validated = assertThrowsExactly(IllegalStateException.class, () -> Signer.validateKeyPair(keyPair));
    assertTrue(validated.getMessage().endsWith(" <> " + FIXED_PUBLIC_KEY), validated.getMessage());
  }

  /// The split `(publicKey, privateKey)` form rejects a public key that the private key does not
  /// derive, whether it is one bit away from the right key or a valid key of another pair.
  @Test
  void splitKeyPairWithANonMatchingPublicKeyIsRejected() {
    final byte[] keyPair = fixedKeyPair();
    final byte[] privateKey = Arrays.copyOfRange(keyPair, 0, KEY_LENGTH);

    final byte[] flipped = Arrays.copyOfRange(keyPair, KEY_LENGTH, KEY_LENGTH << 1);
    flipped[KEY_LENGTH - 1] ^= (byte) 0x80;
    assertThrowsExactly(IllegalStateException.class, () -> Signer.createFromKeyPair(flipped, privateKey));

    final byte[] unrelated = Base58.decode(UNRELATED_PUBLIC_KEY);
    assertThrowsExactly(IllegalStateException.class, () -> Signer.createFromKeyPair(unrelated, privateKey));
  }

  /// The split `(publicKey, privateKey)` byte form accepts a matching pair and returns a signer
  /// that advertises that public key and signs with that private key.
  @Test
  void splitKeyPairWithTheMatchingPublicKeyCreatesAWorkingSigner() {
    final byte[] keyPair = fixedKeyPair();
    final byte[] publicKey = Arrays.copyOfRange(keyPair, KEY_LENGTH, KEY_LENGTH << 1);
    final byte[] privateKey = Arrays.copyOfRange(keyPair, 0, KEY_LENGTH);

    final var signer = Signer.createFromKeyPair(publicKey, privateKey);
    assertEquals(FIXED_PUBLIC_KEY, signer.publicKey().toBase58());
    final byte[] message = "split key pair".getBytes(UTF_8);
    assertTrue(PublicKey.verifySignature(Base58.decode(FIXED_PUBLIC_KEY), 0, message, 0, message.length, signer.sign(message)));
  }

  /// The JCA form wraps the given keys as they are: the signer returns the given [PublicKey]
  /// and [PrivateKey] instances, and its signatures verify under that public key.
  @Test
  void javaKeyPairCreatesASignerForTheGivenKeys() {
    final byte[] keyPair = fixedKeyPair();
    final var publicKey = PublicKey.fromBase58Encoded(FIXED_PUBLIC_KEY);
    final PrivateKey privateKey = KeyPairSigner.generatePrivateKey(Arrays.copyOfRange(keyPair, 0, KEY_LENGTH));

    final var signer = Signer.createFromKeyPair(publicKey, privateKey);
    assertSame(publicKey, signer.publicKey());
    assertSame(privateKey, signer.privateKey());
    final byte[] message = "java key pair".getBytes(UTF_8);
    assertTrue(publicKey.verifySignature(message, signer.sign(message)));
  }

  @Test
  void failedOutputWriteDoesNotPoisonTheSigner() {
    final var signer = Signer.createFromPrivateKey(new byte[KEY_LENGTH]);
    final byte[] rejectedMessage = "rejected signing attempt".getBytes(UTF_8);

    assertThrows(
        IllegalArgumentException.class,
        () -> signer.sign(rejectedMessage, 0, rejectedMessage.length, -1)
    );

    final byte[] nextMessage = "independent next message".getBytes(UTF_8);
    final byte[] signature = signer.sign(nextMessage);
    assertTrue(PublicKey.verifySignature(signer.publicKey().toJavaPublicKey(), nextMessage, signature));
  }

  /// This injected post-update provider failure is the sole genuine reset pin for the
  /// returning-signature overload; invalid update ranges are rejected before buffering.
  @Test
  void providerFailureAfterUpdateDoesNotPoisonTheSigner() throws InvalidKeyException {
    final PrivateKey privateKey = KeyPairSigner.generatePrivateKey(new byte[KEY_LENGTH]);
    final var signature = new ResetSensitiveSignature();
    signature.initSign(privateKey);
    final var signer = new KeyPairSigner(PublicKey.NONE, privateKey, signature);
    final byte[] message = "provider failure".getBytes(UTF_8);

    final var failure = assertThrows(RuntimeException.class, () -> signer.sign(message));
    assertInstanceOf(SignatureException.class, failure.getCause());
    assertArrayEquals(new byte[Transaction.SIGNATURE_LENGTH], signer.sign(message));
  }

  @Test
  void resetFailureRetainsTheSigningFailureForDiagnosis() throws InvalidKeyException {
    final PrivateKey privateKey = KeyPairSigner.generatePrivateKey(new byte[KEY_LENGTH]);
    final var signature = new ResetSensitiveSignature(true);
    signature.initSign(privateKey);
    final var signer = new KeyPairSigner(PublicKey.NONE, privateKey, signature);

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> signer.sign("provider failure".getBytes(UTF_8))
    );
    final var resetFailure = assertInstanceOf(ProviderException.class, failure.getCause());
    assertEquals("deterministic provider reset failure", resetFailure.getMessage());
    assertEquals(1, failure.getSuppressed().length);
    final var signingFailure = assertInstanceOf(SignatureException.class, failure.getSuppressed()[0]);
    assertEquals("deterministic provider failure after update", signingFailure.getMessage());
  }

  @Test
  void outputBufferSigningCoversTheRequestedMessage() {
    final var signer = Signer.createFromPrivateKey(new byte[KEY_LENGTH]);
    final byte[] message = "output-buffer message".getBytes(UTF_8);
    final byte[] signed = new byte[Transaction.SIGNATURE_LENGTH + message.length];
    System.arraycopy(message, 0, signed, Transaction.SIGNATURE_LENGTH, message.length);

    assertEquals(
        Transaction.SIGNATURE_LENGTH,
        signer.sign(signed, Transaction.SIGNATURE_LENGTH, message.length, 0)
    );
    assertTrue(PublicKey.verifySignature(
        signer.publicKey().toJavaPublicKey(),
        message,
        Arrays.copyOf(signed, Transaction.SIGNATURE_LENGTH)
    ));
  }

  private static final class ResetSensitiveSignature extends Signature {

    private final boolean failReset;
    private boolean initialized;
    private boolean failNextSign = true;
    private int initializationCount;

    private ResetSensitiveSignature() {
      this(false);
    }

    private ResetSensitiveSignature(final boolean failReset) {
      super("test-reset-sensitive-ed25519");
      this.failReset = failReset;
    }

    @Override
    protected void engineInitVerify(final java.security.PublicKey publicKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected void engineInitSign(final PrivateKey privateKey) {
      if (failReset && initializationCount++ > 0) {
        throw new ProviderException("deterministic provider reset failure");
      }
      initialized = true;
    }

    @Override
    protected void engineUpdate(final byte b) throws SignatureException {
      if (!initialized) {
        throw new SignatureException("signature was not reset");
      }
    }

    @Override
    protected void engineUpdate(final byte[] data, final int offset, final int length) throws SignatureException {
      if (!initialized) {
        throw new SignatureException("signature was not reset");
      }
    }

    @Override
    protected byte[] engineSign() throws SignatureException {
      if (failNextSign) {
        failNextSign = false;
        initialized = false;
        throw new SignatureException("deterministic provider failure after update");
      }
      if (!initialized) {
        throw new SignatureException("signature was not reset");
      }
      return new byte[Transaction.SIGNATURE_LENGTH];
    }

    @Override
    protected boolean engineVerify(final byte[] signature) {
      throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void engineSetParameter(final String parameter, final Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected Object engineGetParameter(final String parameter) {
      throw new UnsupportedOperationException();
    }
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
    final var keyPair = fixedKeyPair();
    final var password = "correct horse battery staple";
    final char[] passwordChars = password.toCharArray();
    final var props = encryptedProperties(keyPair, passwordChars, minPBKDF2());
    final var signer = Signer.fromProperties(props, passwordChars);
    verifySigner(keyPair, signer);
    assertArrayEquals(password.toCharArray(), passwordChars);
  }

  @Test
  @ResourceLock("argon2id")
  void argon2EncryptedBase64KeyPairFromProperties() {
    final var keyPair = fixedKeyPair();
    final var password = "correct horse battery staple";
    final char[] passwordChars = password.toCharArray();
    final var props = encryptedProperties(keyPair, passwordChars, KeyDerivation.defaultArgon2id());
    final var signer = Signer.fromProperties(props, passwordChars);
    verifySigner(keyPair, signer);
    assertArrayEquals(password.toCharArray(), passwordChars);
  }

  @Test
  void encryptedFromPropertiesRequiresPassword() {
    final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), minPBKDF2());
    assertThrows(IllegalArgumentException.class, () -> Signer.fromProperties(props, null));
  }

  @Test
  void encryptedFromPropertiesWrongPassword() {
    final var props = encryptedProperties(fixedKeyPair(), "right".toCharArray(), minPBKDF2());
    assertThrows(IllegalStateException.class, () -> Signer.fromProperties(props, "wrong".toCharArray()));
  }

  @Test
  void encryptedFromPropertiesMissingPropertyFails() {
    for (final var missing : new String[]{"kdf", "iterations", "salt", "iv", "secret", "pubKey"}) {
      // the subject is the refusal, which runs before any derivation; the file is written at the
      // cheapest accepted cost so the loop does not derive at default cost per property
      final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), minPBKDF2());
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
      final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), KeyDerivation.defaultArgon2id());
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
    final var props = encryptedProperties(fixedKeyPair(), "right".toCharArray(), KeyDerivation.defaultArgon2id());
    assertThrows(IllegalStateException.class, () -> Signer.fromProperties(props, "wrong".toCharArray()));
  }

  @Test
  void rejectsWeakPbkdf2Iterations() {
    final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), minPBKDF2());
    props.setProperty("iterations", "1");
    assertThrows(IllegalArgumentException.class, () -> Signer.fromProperties(props, "pw".toCharArray()));
  }

  @Test
  void rejectsExcessivePbkdf2Iterations() {
    final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), minPBKDF2());
    props.setProperty("iterations", Integer.toString(Integer.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> Signer.fromProperties(props, "pw".toCharArray()));
  }

  @Test
  void rejectsNonNumericIterations() {
    final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), minPBKDF2());
    props.setProperty("iterations", "not-a-number");
    assertThrows(IllegalArgumentException.class, () -> Signer.fromProperties(props, "pw".toCharArray()));
  }

  @Test
  void rejectsShortSalt() {
    final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), minPBKDF2());
    props.setProperty("salt", java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
    final var runtimeEx = assertThrows(IllegalStateException.class, () -> Signer.fromProperties(props, "pw".toCharArray()));
    assertInstanceOf(AEADBadTagException.class, runtimeEx.getCause());
  }

  @Test
  void rejectsWrongLengthIv() {
    final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), minPBKDF2());
    props.setProperty("iv", java.util.Base64.getEncoder().encodeToString(new byte[8]));
    final var runtimeEx = assertThrows(IllegalStateException.class, () -> Signer.fromProperties(props, "pw".toCharArray()));
    assertInstanceOf(AEADBadTagException.class, runtimeEx.getCause());
  }

  @Test
  void rejectsExcessiveArgon2Memory() {
    final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), KeyDerivation.defaultArgon2id());
    props.setProperty("memoryKB", Integer.toString(Integer.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> Signer.fromProperties(props, "pw".toCharArray()));
  }

  @Test
  void rejectsWeakArgon2Memory() {
    final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), KeyDerivation.defaultArgon2id());
    props.setProperty("memoryKB", "8");
    assertThrows(IllegalArgumentException.class, () -> Signer.fromProperties(props, "pw".toCharArray()));
  }

  @Test
  void rejectsExcessiveArgon2Parallelism() {
    final var props = encryptedProperties(fixedKeyPair(), "pw".toCharArray(), KeyDerivation.defaultArgon2id());
    props.setProperty("parallelism", Integer.toString(Integer.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> Signer.fromProperties(props, "pw".toCharArray()));
  }

  @Test
  void encryptedFromPropertiesLiteral() throws java.io.IOException {
    final var propsText = """
        pubKey=Dbu5gpwRWZZRSCms8eey3PmV3eVem6pcZLtdWWsdWYue
        kdf=PBKDF2WithHmacSHA512
        iterations=2100000
        aad=uzzg6oC0WBDSM6cdRsBW2q8dxla2VCglyJICg0xBXRE=
        salt=HEsb3WnvalAtEbsqgPg83A==
        cipher=AES/GCM/NoPadding
        iv=mY6NLUs5sXU5FoIQ
        secret=mymz2cNTT1lKAy30ukG6+6atjeyxUFurerbiLdcEoIzc5F0K9t7KHTvJPfH/gFI8SYMetmwRye5G9uYCaWKW523sAK8zXtPc8WfsplvWl7g=
        """;
    final var props = new Properties();
    props.load(new StringReader(propsText));
    final var signer = Signer.fromProperties(props, "asdf".toCharArray());
    assertEquals("Dbu5gpwRWZZRSCms8eey3PmV3eVem6pcZLtdWWsdWYue", signer.publicKey().toBase58());
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
