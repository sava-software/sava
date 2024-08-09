package software.sava.core.crypto.bip32;

import software.sava.core.crypto.Hmac;
import software.sava.core.encoding.ByteUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class PBKDF2SHA512 {

  public static byte[] derive(final String password, final String salt, final int iterationCount, final int keyLength) {
    try {
      final var key = new SecretKeySpec(password.getBytes(UTF_8), "HmacSHA512");
      final var mac = Hmac.hmacSHA512();
      mac.init(key);

      final byte[] baS = salt.getBytes(UTF_8);
      final byte[] baU = new byte[baS.length + 4];
      System.arraycopy(baS, 0, baU, 0, baS.length);

      final byte[] secret = new byte[keyLength];
      for (int i = 1, from = 0, to = 64; from < secret.length; i++) {
        ByteUtil.putInt32BE(baU, baS.length, i);
        final byte[] out = F(mac, baU, iterationCount);

        System.arraycopy(out, 0, secret, from, to - from);
        from = to;
        to = Math.min(out.length, to + 64);
      }
      return secret;
    } catch (final InvalidKeyException e) {
      throw new RuntimeException(e);
    }
  }

  private static byte[] F(final Mac mac, final byte[] baU, final int c) {
    final byte[] U_XOR = mac.doFinal(baU);
    byte[] U_LAST = U_XOR;
    for (int j = 1; j < c; j++) {
      U_LAST = mac.doFinal(U_LAST);
      for (int k = 0; k < U_XOR.length; k++) {
        U_XOR[k] = (byte) (U_XOR[k] ^ U_LAST[k]);
      }
    }
    return U_XOR;
  }

  private PBKDF2SHA512() {
  }
}