package software.sava.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

public final class Hmac {

  private static final String HMAC_SHA512 = "HmacSHA512";

  public static Mac hmacSHA512() {
    try {
      return Mac.getInstance(HMAC_SHA512, SunCrypto.SUN_JCE_PROVIDER);
    } catch (final NoSuchAlgorithmException e) {
      throw new RuntimeException("Unable to find HmacSHA512", e);
    }
  }

  public static byte[] hmacSHA512(final byte[] key, final byte[] seed) {
    final var mac = hmacSHA512();
    final var keySpec = new SecretKeySpec(seed, HMAC_SHA512);
    try {
      mac.init(keySpec);
      return mac.doFinal(key);
    } catch (final InvalidKeyException e) {
      throw new RuntimeException("Unable to perform HmacSHA512.", e);
    }
  }

  private Hmac() {
  }
}
