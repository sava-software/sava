package software.sava.core.crypto;

import java.security.*;
import java.util.stream.Stream;

public final class SunCrypto {

  public static final Provider SUN_SECURITY_PROVIDER = Stream.of(Security.getProviders())
      .filter(p -> p.getName().equals("SUN"))
      .findFirst().orElseThrow();
  public static final Provider SUN_JCE_PROVIDER = Stream.of(Security.getProviders())
      .filter(p -> p.getName().equals("SunJCE"))
      .findFirst().orElseThrow();
  public static final Provider SUN_EC_PROVIDER = Stream.of(Security.getProviders())
      .filter(p -> p.getName().equals("SunEC"))
      .findFirst().orElseThrow();

  public static final SecureRandom SECURE_RANDOM;
  public static final KeyFactory EdDSA_KEY_FACTORY;
  public static final KeyFactory ED_25519_KEY_FACTORY;

  static {
    try {
      SECURE_RANDOM = SecureRandom.getInstanceStrong();
      EdDSA_KEY_FACTORY = KeyFactory.getInstance("EdDSA", SunCrypto.SUN_EC_PROVIDER);
      ED_25519_KEY_FACTORY = KeyFactory.getInstance("Ed25519", SunCrypto.SUN_EC_PROVIDER);
    } catch (final NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  private SunCrypto() {
  }
}
