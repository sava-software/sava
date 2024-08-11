/**
 * Copyright (c) 2018 orogvany
 * <p>
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 * <p>
 * https://github.com/orogvany/BIP32-Ed25519-java/blob/master/src/main/java/com/github/orogvany/bip32/wallet/HdKeyGenerator.java
 */
package software.sava.core.crypto.bip32.wallet;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import software.sava.core.crypto.Hash;
import software.sava.core.crypto.Hmac;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class SolanaHdKeyGenerator {

  private static final String CURVE = "ed25519 seed";
  private static final long HARDENED = 0x80000000L;
  private static final long COIN_TYPE = 501;
  private static final long HARDENED_COIN_TYPE = COIN_TYPE | HARDENED;
  private static final long PURPOSE = 44;
  private static final long HARDENED_PURPOSE = PURPOSE | HARDENED;

  private static final X9ECParameters SECP = CustomNamedCurves.getByName("secp256k1");
  private static final String MASTER_PATH = "m";

  public static HdAddress getPrivateKeyFromBip44SeedWithChange(final byte[] seed) {
    final var masterAddress = getAddressFromSeed(seed);
    final var purposeAddress = getHardenedPurposeAddress(masterAddress); // 55H
    final var coinTypeAddress = getHardenedCoinAddress(purposeAddress); // 501H
    final var accountAddress = getHardenedAddress(coinTypeAddress); //0H
    return getHardenedAddress(accountAddress);
  }

  public static HdAddress getPrivateKeyFromBip44Seed(final byte[] seed) {
    final var masterAddress = getAddressFromSeed(seed);
    final var purposeAddress = getHardenedPurposeAddress(masterAddress); // 55H
    final var coinTypeAddress = getHardenedCoinAddress(purposeAddress); // 501H
    return getHardenedAddress(coinTypeAddress);
  }

  public static HdAddress getHardenedCoinAddress(final HdAddress parent) {
    return getAddress(parent, HARDENED_COIN_TYPE);
  }

  public static HdAddress getHardenedPurposeAddress(final HdAddress parent) {
    return getAddress(parent, HARDENED_PURPOSE);
  }

  public static HdAddress getHardenedAddress(final HdAddress parent) {
    return getAddress(parent, HARDENED);
  }

  private static HdAddress getAddress(final HdAddress parent, final long child) {
    final var privateKey = software.sava.core.crypto.bip32.wallet.HdKey.build();
    final var publicKey = software.sava.core.crypto.bip32.wallet.HdKey.build();
    final boolean isHardened = (child & HARDENED) == HARDENED;
    final byte[] xChain = parent.privateKey().getChainCode();

    final byte[] I;
    if (isHardened) {
      //If so (hardened child): let I = HMAC-SHA512(Key = cpar, Data = 0x00 || ser256(kpar) || ser32(i)). (Note: The 0x00 pads the private key to make it 33 bytes long.)
      final var kpar = parse256(parent.privateKey().getKeyData());
      byte[] data = append(new byte[]{0}, ser256(kpar));
      data = append(data, ser32(child));
      I = Hmac.hmacSHA512(data, xChain);
    } else {
      //I = HMAC-SHA512(Key = cpar, Data = serP(point(kpar)) || ser32(i))
      final byte[] key = parent.publicKey().getKeyData();
      final byte[] xPubKey = append(key, ser32(child));
      I = Hmac.hmacSHA512(xPubKey, xChain);
    }

    final byte[] IL = Arrays.copyOfRange(I, 0, 32);
    final byte[] IR = Arrays.copyOfRange(I, 32, 64);
    //The returned child key ki is parse256(IL) + kpar (mod n).
    final var parse256 = parse256(IL);
    final var kpar = parse256(parent.privateKey().getKeyData());
    final var childSecretKey = parse256.add(kpar).mod(getN());

    final byte[] childNumber = ser32(child);

    privateKey.setVersion(parent.privateKey().getVersion());
    privateKey.setDepth(parent.privateKey().getDepth() + 1);
    privateKey.setChildNumber(childNumber);
    privateKey.setChainCode(IR);
    privateKey.setKey(IL);
    privateKey.setKeyData(append(new byte[]{0}, IL));

    final var point = point(childSecretKey);
    publicKey.setVersion(parent.publicKey().getVersion());
    publicKey.setDepth(parent.publicKey().getDepth() + 1);

    publicKey.setChildNumber(childNumber);
    publicKey.setChainCode(IR);
    publicKey.setKeyData(serP(point));
    publicKey.setKey(generatePublicKey(IL));

    final byte[] h160 = Hash.h160(parent.publicKey().getRawKey());
    final byte[] childFingerprint = new byte[]{h160[0], h160[1], h160[2], h160[3]};
    publicKey.setFingerprint(childFingerprint);
    privateKey.setFingerprint(childFingerprint);

    return new HdAddress(privateKey.createKey(), publicKey.createKey(), getPath(parent.path(), child, isHardened));
  }

  private static String getPath(final String parentPath, final long child, final boolean isHardened) {
    return (parentPath == null ? MASTER_PATH : parentPath) + "/" + child + (isHardened ? "H" : "");
  }

  private static HdAddress getAddressFromSeed(final byte[] seed) {
    final var publicKey = software.sava.core.crypto.bip32.wallet.HdKey.build();
    final var privateKey = software.sava.core.crypto.bip32.wallet.HdKey.build();

    final byte[] I = Hmac.hmacSHA512(seed, CURVE.getBytes(StandardCharsets.UTF_8));

    final byte[] IL = Arrays.copyOfRange(I, 0, 32);
    final byte[] IR = Arrays.copyOfRange(I, 32, 64);

    final var masterSecretKey = parse256(IL);

    //In case IL is 0 or >=n, the master key is invalid.
    if (masterSecretKey.compareTo(BigInteger.ZERO) == 0 || masterSecretKey.compareTo(getN()) > 0) {
      throw new RuntimeException("The master key is invalid");
    }

    privateKey.setDepth(0);
    privateKey.setFingerprint(new byte[]{0, 0, 0, 0});
    privateKey.setChildNumber(new byte[]{0, 0, 0, 0});
    privateKey.setChainCode(IR);
    privateKey.setKeyData(append(new byte[]{0}, IL));
    privateKey.setKey(IL);

    final var point = point(masterSecretKey);
    publicKey.setDepth(0);
    publicKey.setFingerprint(new byte[]{0, 0, 0, 0});
    publicKey.setChildNumber(new byte[]{0, 0, 0, 0});
    publicKey.setChainCode(IR);
    publicKey.setKeyData(serP(point));
    publicKey.setKey(generatePublicKey(IL));

    return new HdAddress(privateKey.createKey(), publicKey.createKey(), MASTER_PATH);
  }

  private static byte[] generatePublicKey(final byte[] secretKey) {
    final byte[] publicKey = new byte[32];
    Ed25519.generatePublicKey(secretKey, 0, publicKey, 0);
    return publicKey;
  }

  /**
   * serP(P): serializes the coordinate pair P = (x,y) as a byte sequence using
   * SEC1's compressed form: (0x02 or 0x03) || ser256(x), where the header byte
   * depends on the parity of the omitted y coordinate.
   *
   * @param p point
   * @return serialized point
   */
  private static byte[] serP(final ECPoint p) {
    return p.getEncoded(true);
  }

  /**
   * point(p): returns the coordinate pair resulting from EC point multiplication
   * (repeated application of the EC group operation) of the secp256k1 base point
   * with the integer p.
   *
   * @param p input
   * @return point
   */
  private static ECPoint point(final BigInteger p) {
    return SECP.getG().multiply(p);
  }

  /**
   * get curve N
   *
   * @return N
   */
  private static BigInteger getN() {
    return SECP.getN();
  }

  /**
   * ser32(i): serialize a 32-bit unsigned integer i as a 4-byte sequence,
   * most significant byte first.
   * <p>
   * Prefer long type to hold unsigned ints.
   *
   * @return ser32(i)
   */
  private static byte[] ser32(final long i) {
    final byte[] ser = new byte[4];
    ser[0] = (byte) (i >> 24);
    ser[1] = (byte) (i >> 16);
    ser[2] = (byte) (i >> 8);
    ser[3] = (byte) (i);
    return ser;
  }

  /**
   * ser256(p): serializes the integer p as a 32-byte sequence, most
   * significant byte first.
   *
   * @param p big integer
   * @return 32 byte sequence
   */
  private static byte[] ser256(final BigInteger p) {
    final byte[] byteArray = p.toByteArray();
    final byte[] ret = new byte[32];

    if (byteArray.length <= ret.length) {
      System.arraycopy(byteArray, 0, ret, ret.length - byteArray.length, byteArray.length);
    } else {
      System.arraycopy(byteArray, byteArray.length - ret.length, ret, 0, ret.length);
    }

    return ret;
  }

  /**
   * parse256(p): interprets a 32-byte sequence as a 256-bit number, most
   * significant byte first.
   *
   * @param p bytes
   * @return 256 bit number
   */
  private static BigInteger parse256(final byte[] p) {
    return new BigInteger(1, p);
  }

  /**
   * Append two byte arrays
   *
   * @param a first byte array
   * @param b second byte array
   * @return bytes appended
   */
  private static byte[] append(final byte[] a, final byte[] b) {
    final byte[] c = new byte[a.length + b.length];
    System.arraycopy(a, 0, c, 0, a.length);
    System.arraycopy(b, 0, c, a.length, b.length);
    return c;
  }

  private SolanaHdKeyGenerator() {
  }
}
