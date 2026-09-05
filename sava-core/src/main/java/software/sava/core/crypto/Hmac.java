package software.sava.core.crypto;

import javax.crypto.Mac;
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

  private Hmac() {
  }
}
