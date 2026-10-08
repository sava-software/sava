package software.sava.core.accounts.pbkdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.vanity.FixedSeedSecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class PBKDFEncryptionTest {

  /// Arbitrary fixed seed for the salt and IV draws; the oracle for those bytes is a second
  /// `java.util.Random` on the same seed.
  private static final long SEED = 8675309L;

  private static final byte[] AAD = PublicKey.fromBase58Encoded("4bcoVWVXfw6xKsEYdM6s7AeZQMgDG958kK5Uzhc2sw37").toByteArray();

  // Use the minimum supported PBKDF2 iteration count so these tests' key derivation stays fast.
  private static KeyDerivation minPBKDF2() {
    return KeyDerivation.createPBKDF2WithHmacSHA512(PBKDF2WithHmacSHA512.MIN_ITERATIONS);
  }

  /// A 64-byte secret drawn from its own seeded stream, so the entropy stub handed to
  /// `encrypt` sees exactly the draws the subject makes and nothing else.
  private static byte[] secret64() {
    final var secret = new byte[64];
    new Random(42L).nextBytes(secret);
    return secret;
  }

  private static boolean allZero(final byte[] bytes) {
    for (final byte b : bytes) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }

  /// A [KeyDerivation] that hands back a fresh copy of a fixed, non-zero key on every call
  /// and keeps both the copy and the inputs it was given, so a test can look at the key
  /// array after the subject is done with it.
  private static final class RecordingKeyDerivation implements KeyDerivation {

    private final byte[] key;
    private final List<byte[]> handedOut = new ArrayList<>();
    private final List<byte[]> salts = new ArrayList<>();
    private final List<char[]> passwords = new ArrayList<>();
    private final List<Integer> keyBits = new ArrayList<>();

    private RecordingKeyDerivation(final byte[] key) {
      this.key = key;
    }

    @Override
    public byte[] derive(final char[] password, final byte[] salt, final int keyBits) {
      passwords.add(password.clone());
      salts.add(salt.clone());
      this.keyBits.add(keyBits);
      final var copy = key.clone();
      handedOut.add(copy);
      return copy;
    }

    @Override
    public String toJson() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String toPropertiesString() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void addProperties(final Properties properties) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int iterations() {
      return 7;
    }
  }

  /// A 256-bit key whose bytes are all non-zero and distinct, so "all zero afterwards"
  /// can only mean the subject wiped it.
  private static byte[] distinctKey() {
    final var key = new byte[32];
    for (int i = 0; i < key.length; ++i) {
      key[i] = (byte) (0xA0 + i);
    }
    return key;
  }

  @Test
  void roundTrip() {
    final var secureRandom = new FixedSeedSecureRandom(SEED);
    final var secret = secret64();
    final var expectedSecret = Arrays.copyOf(secret, secret.length);
    final var kdf = minPBKDF2();
    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(), secureRandom, secret, kdf, AAD
    );

    assertEquals(PBKDFEncryption.SALT_BYTES, encrypted.salt().length);
    assertEquals(PBKDFEncryption.IV_BYTES, encrypted.iv().length);
    assertFalse(Arrays.equals(expectedSecret, encrypted.cipherText()));

    final byte[] decrypted = encrypted.decrypt("correct horse".toCharArray());
    assertArrayEquals(expectedSecret, decrypted);
  }

  /// The salt and IV are the first sixteen and the next twelve bytes the caller's
  /// `SecureRandom` produces, in that order, and nothing else. The oracle is a second
  /// `java.util.Random` on the same seed: an envelope whose salt or IV was never filled
  /// would carry zeros, and one that reused a draw would not match the stream.
  @Test
  void saltAndIvAreTheCallersRandomDrawsInOrder() {
    final var expectedStream = new Random(SEED);
    final var expectedSalt = new byte[16];
    expectedStream.nextBytes(expectedSalt);
    final var expectedIv = new byte[12];
    expectedStream.nextBytes(expectedIv);

    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(),
        new FixedSeedSecureRandom(SEED),
        secret64(),
        new RecordingKeyDerivation(distinctKey()),
        AAD
    );

    assertArrayEquals(expectedSalt, encrypted.salt());
    assertArrayEquals(expectedIv, encrypted.iv());
    assertFalse(allZero(encrypted.salt()));
    assertFalse(allZero(encrypted.iv()));
  }

  /// Two encryptions of the same data under the same password must not share a salt, an
  /// IV or a ciphertext: GCM under a repeated (key, IV) pair leaks the XOR of the
  /// plaintexts and lets the authentication key be recovered. Both calls draw from one
  /// seeded source, as a caller reusing its `SecureRandom` would.
  @Test
  void repeatedEncryptionDrawsFreshSaltIvAndCiphertext() {
    final var secureRandom = new FixedSeedSecureRandom(SEED);
    final var kdf = new RecordingKeyDerivation(distinctKey());
    final var first = PBKDFEncryption.encrypt("correct horse".toCharArray(), secureRandom, secret64(), kdf, AAD);
    final var second = PBKDFEncryption.encrypt("correct horse".toCharArray(), secureRandom, secret64(), kdf, AAD);

    assertFalse(Arrays.equals(first.salt(), second.salt()));
    assertFalse(Arrays.equals(first.iv(), second.iv()));
    assertFalse(Arrays.equals(first.cipherText(), second.cipherText()));
    assertFalse(allZero(first.salt()));
    assertFalse(allZero(first.iv()));
    assertFalse(allZero(second.salt()));
    assertFalse(allZero(second.iv()));
  }

  /// The envelope is plain AES-256-GCM with a 128-bit tag, keyed by PBKDF2-HMAC-SHA512 of
  /// the password over the envelope's salt, with the AAD bound in. The oracle is the JCE
  /// itself driven directly, so a file this class writes can be opened by any standard
  /// implementation and nothing about the layout is private to sava.
  @Test
  void envelopeIsStandardAesGcmUnderThePbkdf2Key() throws GeneralSecurityException {
    final var password = "correct horse".toCharArray();
    final var secret = secret64();
    final var encrypted = PBKDFEncryption.encrypt(
        password,
        new FixedSeedSecureRandom(SEED),
        secret,
        minPBKDF2(),
        AAD
    );

    final var keySpec = new PBEKeySpec(password, encrypted.salt(), PBKDF2WithHmacSHA512.MIN_ITERATIONS, 256);
    final byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(keySpec).getEncoded();
    final var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, encrypted.iv()));
    cipher.updateAAD(AAD);
    assertArrayEquals(cipher.doFinal(secret), encrypted.cipherText());
  }

  /// An envelope written without AAD opens without AAD, and treating a null AAD as "none"
  /// must not reach `Cipher.updateAAD(null)` on either side.
  @Test
  void nullAadRoundTrips() {
    final var secret = secret64();
    final var kdf = new RecordingKeyDerivation(distinctKey());
    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(), new FixedSeedSecureRandom(SEED), secret, kdf, null
    );
    assertNull(encrypted.aad());

    assertArrayEquals(secret, PBKDFEncryption.decrypt(
        "correct horse".toCharArray(), kdf, null, encrypted.salt(), encrypted.iv(), encrypted.cipherText()
    ));
  }

  /// The key `encrypt` derives is zeroed before `encrypt` returns: the caller never sees
  /// it, so leaving it on the heap only widens the window for a memory disclosure. The
  /// derivation is also asked for exactly the envelope's salt, the caller's password and
  /// a 256-bit key.
  @Test
  void encryptWipesTheDerivedKey() {
    final var key = distinctKey();
    final var kdf = new RecordingKeyDerivation(key);
    final var secret = secret64();
    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(), new FixedSeedSecureRandom(SEED), secret, kdf, AAD
    );

    assertEquals(1, kdf.handedOut.size());
    assertArrayEquals(new byte[32], kdf.handedOut.getFirst());
    assertArrayEquals(encrypted.salt(), kdf.salts.getFirst());
    assertArrayEquals("correct horse".toCharArray(), kdf.passwords.getFirst());
    assertEquals(256, kdf.keyBits.getFirst());

    // The wiped array was the real key: the original bytes still open the envelope.
    assertArrayEquals(secret, PBKDFEncryption.decrypt(key.clone(), AAD, encrypted.iv(), encrypted.cipherText()));
  }

  /// The password-based `decrypt` zeroes the key it derived before returning, for the
  /// same reason `encrypt` does.
  @Test
  void passwordDecryptWipesTheDerivedKey() {
    final var kdf = new RecordingKeyDerivation(distinctKey());
    final var secret = secret64();
    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(), new FixedSeedSecureRandom(SEED), secret, kdf, AAD
    );

    final byte[] decrypted = PBKDFEncryption.decrypt(
        "correct horse".toCharArray(), kdf, AAD, encrypted.salt(), encrypted.iv(), encrypted.cipherText()
    );

    assertArrayEquals(secret, decrypted);
    assertEquals(2, kdf.handedOut.size());
    assertArrayEquals(new byte[32], kdf.handedOut.get(1));
    assertArrayEquals(encrypted.salt(), kdf.salts.get(1));
  }

  /// The derived key is wiped on the failing path too: a decryption that fails authentication
  /// throws through the same `finally`, and the key it derived must not outlive the call.
  @Test
  void passwordDecryptWipesTheDerivedKeyWhenAuthenticationFails() {
    final var kdf = new RecordingKeyDerivation(distinctKey());
    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(), new FixedSeedSecureRandom(SEED), secret64(), kdf, AAD
    );
    final byte[] otherAad = {1, 2, 3};

    assertThrows(IllegalStateException.class, () -> PBKDFEncryption.decrypt(
        "correct horse".toCharArray(), kdf, otherAad, encrypted.salt(), encrypted.iv(), encrypted.cipherText()
    ));

    assertEquals(2, kdf.handedOut.size());
    assertArrayEquals(new byte[32], kdf.handedOut.get(1));
  }

  /// The derived key is wiped when decryption fails before the cipher is ever initialised. A
  /// null IV throws from the `GCMParameterSpec` constructor, which Java evaluates before
  /// `Cipher.init` is called, so the JCE never sees the key and the `finally` wipe is the only
  /// zeroing on that path; the JCE's own zeroing of a raw key array at decrypt `init` (pinned by
  /// `theJceZeroesARawKeyArrayAtDecryptInit`) covers only the paths that reach `init`.
  @Test
  void passwordDecryptWipesTheDerivedKeyWhenTheCipherIsNeverInitialised() {
    final var kdf = new RecordingKeyDerivation(distinctKey());
    final byte[] salt = new byte[16];
    Arrays.fill(salt, (byte) 5);

    assertThrows(IllegalArgumentException.class, () -> PBKDFEncryption.decrypt(
        "correct horse".toCharArray(), kdf, AAD, salt, null, new byte[16]
    ));

    assertEquals(1, kdf.handedOut.size());
    assertArrayEquals(new byte[32], kdf.handedOut.getFirst(), "the derived key must not outlive the failed call");
  }

  /// An empty AAD binds nothing, exactly like no AAD: an envelope sealed with either opens with
  /// the other. This is the oracle for accepting the `aad.length > 0` boundary mutants, which
  /// route an empty array through `Cipher.updateAAD`, a call the JCE treats as a no-op.
  @Test
  void emptyAadIsTheSameAsNoAad() {
    final var kdf = new RecordingKeyDerivation(distinctKey());
    final var secret = secret64();
    for (final byte[] sealedWith : new byte[][]{null, new byte[0]}) {
      final var encrypted = PBKDFEncryption.encrypt(
          "correct horse".toCharArray(), new FixedSeedSecureRandom(SEED), secret, kdf, sealedWith
      );
      for (final byte[] openedWith : new byte[][]{null, new byte[0]}) {
        assertArrayEquals(secret, PBKDFEncryption.decrypt(
            "correct horse".toCharArray(), kdf, openedWith, encrypted.salt(), encrypted.iv(), encrypted.cipherText()
        ));
      }
    }
  }

  /// The JDK fact behind accepting the password-decrypt wipe as redundant: SunJCE zeroes a key
  /// array that a `SecretKey` hands out by reference while initialising AES/GCM for decryption,
  /// and leaves it alone for encryption. The key here is the test's own, so this pins the JDK,
  /// not sava; if it ever fails, that acceptance no longer holds.
  @Test
  void theJceZeroesARawKeyArrayAtDecryptInit() throws GeneralSecurityException {
    record RawKey(byte[] key) implements SecretKey {
      @Override
      public String getAlgorithm() {
        return "AES";
      }

      @Override
      public String getFormat() {
        return "RAW";
      }

      @Override
      public byte[] getEncoded() {
        return key;
      }
    }
    final byte[] iv = new byte[12];
    Arrays.fill(iv, (byte) 7);

    final byte[] encryptKey = distinctKey();
    final var encrypting = Cipher.getInstance("AES/GCM/NoPadding");
    encrypting.init(Cipher.ENCRYPT_MODE, new RawKey(encryptKey), new GCMParameterSpec(128, iv));
    assertArrayEquals(distinctKey(), encryptKey, "encryption must leave the key array alone");

    final byte[] decryptKey = distinctKey();
    final var decrypting = Cipher.getInstance("AES/GCM/NoPadding");
    decrypting.init(Cipher.DECRYPT_MODE, new RawKey(decryptKey), new GCMParameterSpec(128, iv));
    assertArrayEquals(new byte[32], decryptKey, "decryption initialisation zeroes the key array");
  }

  /// A missing password is rejected before any entropy is drawn or key derived, with a
  /// message that names the problem: null and empty share one message.
  @Test
  void nullOrEmptyPasswordIsRejected() {
    for (final char[] password : new char[][]{null, new char[0]}) {
      final var secureRandom = new FixedSeedSecureRandom(SEED);
      final var kdf = new RecordingKeyDerivation(distinctKey());
      final var ex = assertThrows(
          IllegalArgumentException.class,
          () -> PBKDFEncryption.encrypt(password, secureRandom, secret64(), kdf, AAD)
      );
      assertEquals("Password must not be null or empty.", ex.getMessage());
      assertTrue(kdf.handedOut.isEmpty());
    }
  }

  /// A password that is all `'\0'` is what a caller's own wipe leaves behind; encrypting
  /// under it would silently produce a file anyone can open, so it is rejected.
  @Test
  void zeroedPasswordIsRejected() {
    final var kdf = new RecordingKeyDerivation(distinctKey());
    final var ex = assertThrows(
        IllegalArgumentException.class,
        () -> PBKDFEncryption.encrypt(
            new char[]{'\0', '\0', '\0'}, new FixedSeedSecureRandom(SEED), secret64(), kdf, AAD
        )
    );
    assertEquals("Password has been zeroed out; it may have already been destroyed.", ex.getMessage());
    assertTrue(kdf.handedOut.isEmpty());
  }

  /// Only an entirely zeroed password is refused: a password that merely starts with
  /// `'\0'` is a real password and encrypts.
  @Test
  void passwordWithALeadingNulIsAccepted() {
    final var password = new char[]{'\0', 'x'};
    final var kdf = new RecordingKeyDerivation(distinctKey());
    final var secret = secret64();
    final var encrypted = PBKDFEncryption.encrypt(
        password, new FixedSeedSecureRandom(SEED), secret, kdf, AAD
    );
    assertArrayEquals(password, kdf.passwords.getFirst());
    assertArrayEquals(secret, PBKDFEncryption.decrypt(
        password, kdf, AAD, encrypted.salt(), encrypted.iv(), encrypted.cipherText()
    ));
  }

  /// [KeyDerivation#defaultPBKDF2WithHmacSHA512] is PBKDF2 at the documented default
  /// iteration count for new key files.
  @Test
  void defaultPbkdf2UsesTheDefaultIterationCount() {
    final var kdf = assertInstanceOf(PBKDF2WithHmacSHA512.class, KeyDerivation.defaultPBKDF2WithHmacSHA512());
    assertEquals(PBKDF2WithHmacSHA512.DEFAULT_ITERATIONS, kdf.iterations());
  }

  private static SecretKey newRawSecretKey(final byte[] keyBytes) throws ReflectiveOperationException {
    final var type = Class.forName(PBKDFEncryption.class.getName() + "$RawSecretKey");
    final var constructor = type.getDeclaredConstructor(byte[].class);
    constructor.setAccessible(true);
    return (SecretKey) constructor.newInstance((Object) keyBytes);
  }

  /// `RawSecretKey` is a raw AES key over the caller's array: `isDestroyed` is true exactly
  /// when every byte is zero, a key that merely starts with a zero byte is live, and
  /// `destroy` zeroes the array it was built over. It is private and nothing in the library
  /// calls `destroy`, so reflection is the only way to reach the contract.
  @Test
  void rawSecretKeyDestroyZeroesTheKeyAndReportsIt() throws ReflectiveOperationException, DestroyFailedException {
    final var keyBytes = distinctKey();
    final var key = newRawSecretKey(keyBytes);
    assertEquals("AES", key.getAlgorithm());
    assertEquals("RAW", key.getFormat());
    assertSame(keyBytes, key.getEncoded());
    assertFalse(key.isDestroyed());

    key.destroy();
    assertArrayEquals(new byte[32], keyBytes);
    assertTrue(key.isDestroyed());

    final var leadingZero = new byte[32];
    leadingZero[31] = 1;
    assertFalse(newRawSecretKey(leadingZero).isDestroyed());
    assertTrue(newRawSecretKey(new byte[32]).isDestroyed());
  }

  @Test
  void wrongAadFails() {
    final var secureRandom = new FixedSeedSecureRandom(SEED);
    final var encrypted = PBKDFEncryption.encrypt(
        "right".toCharArray(), secureRandom, "some-secret".getBytes(StandardCharsets.UTF_8), minPBKDF2(), AAD
    );
    final var tamperedAad = "tampered-public-key".getBytes(StandardCharsets.UTF_8);
    final var runtimeEx = assertThrows(IllegalStateException.class, () -> PBKDFEncryption.decrypt(
            "right".toCharArray(),
            minPBKDF2(),
            tamperedAad,
            encrypted.salt(),
            encrypted.iv(),
            encrypted.cipherText()
        )
    );
    assertInstanceOf(AEADBadTagException.class, runtimeEx.getCause());
  }

  @Test
  void wrongPasswordFails() {
    final var secureRandom = new FixedSeedSecureRandom(SEED);
    final var encrypted = PBKDFEncryption.encrypt(
        "right".toCharArray(),
        secureRandom,
        "some-secret".getBytes(StandardCharsets.UTF_8),
        minPBKDF2(),
        AAD
    );
    final var runtimeEx = assertThrows(IllegalStateException.class, () -> encrypted.decrypt("wrong".toCharArray()));
    assertInstanceOf(AEADBadTagException.class, runtimeEx.getCause());
  }

  // Argon2id is memory-hard; run it sequentially so concurrent tests don't exhaust heap.
  @Test
  @ResourceLock("argon2id")
  void argon2RoundTrip() {
    final var secureRandom = new FixedSeedSecureRandom(SEED);
    final var secret = secret64();
    final byte[] expectedSecret = Arrays.copyOf(secret, secret.length);
    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(), secureRandom, secret, KeyDerivation.defaultArgon2id(), AAD
    );

    final var kdf = assertInstanceOf(Argon2id.class, encrypted.keyDerivation());

    assertEquals(Argon2id.ARGON2_MEMORY_KB, kdf.memoryKB());
    assertEquals(Argon2id.ARGON2_PARALLELISM, kdf.parallelism());
    assertEquals(Argon2id.ARGON2_ITERATIONS, kdf.iterations());
    assertEquals(PBKDFEncryption.SALT_BYTES, encrypted.salt().length);
    assertEquals(PBKDFEncryption.IV_BYTES, encrypted.iv().length);
    assertFalse(Arrays.equals(expectedSecret, encrypted.cipherText()));

    final byte[] decrypted = encrypted.decrypt("correct horse".toCharArray());
    assertArrayEquals(expectedSecret, decrypted);
  }
}
