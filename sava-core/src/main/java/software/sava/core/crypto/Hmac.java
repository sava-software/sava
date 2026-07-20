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

  /// Computes HMAC-SHA512 of `data` under `key`.
  ///
  /// @deprecated the previous signature was `hmacSHA512(key, seed)` but keyed the
  /// [Mac] with its *second* argument and authenticated the first, so every call
  /// written against the parameter names produced a MAC with the two swapped. The
  /// roles now match the names, which silently changes the result of any existing
  /// call — pass the arguments in the other order to keep the old output. No
  /// caller in this repository used it. Scheduled for removal: use [#hmacSHA512()]
  /// and drive the [Mac] directly, which is the only way to control key handling
  /// and reuse.
  ///
  /// @throws RuntimeException if the JCE rejects the key, or `HmacSHA512` is
  /// unavailable from the configured provider.
  @Deprecated(forRemoval = true)
  public static byte[] hmacSHA512(final byte[] key, final byte[] data) {
    final var mac = hmacSHA512();
    final var keySpec = new SecretKeySpec(key, HMAC_SHA512);
    try {
      mac.init(keySpec);
      return mac.doFinal(data);
    } catch (final InvalidKeyException e) {
      throw new RuntimeException("Unable to perform HmacSHA512.", e);
    }
  }

  private Hmac() {
  }
}
