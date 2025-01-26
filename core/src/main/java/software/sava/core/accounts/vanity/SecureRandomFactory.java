package software.sava.core.accounts.vanity;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public interface SecureRandomFactory {

  SecureRandomFactory DEFAULT = SecureRandom::getInstanceStrong;

  SecureRandom createSecureRandom() throws NoSuchAlgorithmException;
}
