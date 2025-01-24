package software.sava.core.accounts;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.Test;
import software.sava.core.encoding.Base58;

import java.util.Arrays;

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
  void sigVerify() {
    final var msg = "sava";
    final var keyPair = Signer.generatePrivateKeyPairBytes();
    final var signer = Signer.createFromKeyPair(keyPair);
    final var signature = signer.sign(msg.getBytes());
    final boolean verified = signer.publicKey().verifySignature(msg, signature);
    assertTrue(verified);
  }
}
