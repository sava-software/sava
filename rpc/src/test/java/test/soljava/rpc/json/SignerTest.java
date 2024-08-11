package test.soljava.rpc.json;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.PrivateKeyEncoding;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SignerTest {

  @Test
  void fromJson() {
    final var json = "[94,151,102,217,69,77,121,169,76,7,9,241,196,119,233,67,25,222,209,40,113,70,33,81,154,33,136,30,208,45,227,28,23,245,32,61,13,33,156,192,84,169,95,202,37,105,150,21,157,105,107,130,13,134,235,7,16,130,50,239,93,206,244,0]";
    final var acc = PrivateKeyEncoding.fromJsonArray(json.getBytes(StandardCharsets.US_ASCII));
    assertEquals("2cXAj2TagK3t6rb2CGRwyhF6sTFJgLyzyDGSWBcGd8Go", acc.publicKey().toString());
  }
}
