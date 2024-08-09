package software.sava.core;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PrivateKeyEncoding;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.sava.core.accounts.Signer.KEY_LENGTH;

final class SignerTest {

  @Test
  public void generateKeyPair() {
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
  public void accountFromSecretKey() {
    final byte[] secretKey = Base58.decode("4Z7cXSyeFR8wNGMVXUE1TwtKn5D5Vu7FzEv69dokLv7KrQk7h6pu4LF8ZRR9yQBhc7uSM6RTTZtU1fmaxiNrxXrs");
    assertEquals("QqCCvshxtqMAL2CVALqiJB7uEeE5mjSPsseQdDzsRUo", Signer.createFromKeyPair(secretKey).publicKey().toString());
  }

  @Test
  public void fromBip44Mnemonic() {
    final var acc = Signer.fromBip44Mnemonic(Arrays.asList("hint", "begin", "crowd", "dolphin", "drive", "render", "finger", "above", "sponsor", "prize", "runway", "invest", "dizzy", "pony", "bitter", "trial", "ignore", "crop", "please", "industry", "hockey", "wire", "use", "side"), "");
    assertEquals("G75kGJiizyFNdnvvHxkrBrcwLomGJT2CigdXnsYzrFHv", acc.publicKey().toString());
  }

  @Test
  public void fromBip44MnemonicChange() {
    final var acc = Signer.fromBip44MnemonicWithChange(Arrays.asList("hint", "begin", "crowd", "dolphin", "drive", "render", "finger", "above", "sponsor", "prize", "runway", "invest", "dizzy", "pony", "bitter", "trial", "ignore", "crop", "please", "industry", "hockey", "wire", "use", "side"), "");
    assertEquals("AaXs7cLGcSVAsEt8QxstVrqhLhYN2iGhFNRemwYnHitV", acc.publicKey().toString());
  }

  @Test
  public void fromJson() {
    final var json = "[94,151,102,217,69,77,121,169,76,7,9,241,196,119,233,67,25,222,209,40,113,70,33,81,154,33,136,30,208,45,227,28,23,245,32,61,13,33,156,192,84,169,95,202,37,105,150,21,157,105,107,130,13,134,235,7,16,130,50,239,93,206,244,0]";
    final var acc = PrivateKeyEncoding.fromJsonArray(json.getBytes(StandardCharsets.US_ASCII));
    assertEquals("2cXAj2TagK3t6rb2CGRwyhF6sTFJgLyzyDGSWBcGd8Go", acc.publicKey().toString());
  }

  @Test
  public void fromBip39MnemonicTest() {
    final var account = Signer.fromBip39Mnemonic(Arrays.asList("iron", "make", "indoor", "where", "explain", "model", "maximum", "wonder", "toward", "salad", "fan", "try"), "");
    assertEquals("BeepMww3KwiDeEhEeZmqk4TegvJYNuDERPWm142X6Mx3", account.publicKey().toBase58());
  }

  @Test
  public void recoverPublicKey() {
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
}
