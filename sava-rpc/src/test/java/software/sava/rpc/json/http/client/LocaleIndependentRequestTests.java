package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Request bodies are JSON, so their numbers must be ASCII digits whatever the JVM's default
/// format locale; `String.format` without a locale writes that locale's digits. The default is
/// JVM-wide state, hence [Isolated]. [RpcRequestTests] compares from `"method"` on, so the
/// number checked is a parameter, written by the same formatter as the id.
@Isolated
final class LocaleIndependentRequestTests extends RpcRequestTests {

  /// Thai digits, through the Unicode numbering-system extension.
  private static final Locale NON_ASCII_DIGITS = Locale.forLanguageTag("th-TH-u-nu-thai");
  private static final PublicKey IDENTITY =
      PublicKey.fromBase58Encoded("4Qkev8aNZcqFNSRhQzwyLMFSsi94jHqE8WNVTJzTP99F");

  @Test
  void slotParameterIsWrittenInAsciiDigits() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLeaderSchedule","params":[412345678,\
        {"commitment":"confirmed","identity":"4Qkev8aNZcqFNSRhQzwyLMFSsi94jHqE8WNVTJzTP99F"}]}""", """
        {"jsonrpc":"2.0","result":{"4Qkev8aNZcqFNSRhQzwyLMFSsi94jHqE8WNVTJzTP99F":[0,1,2,3]},"id":1}""");

    final var formatLocale = Locale.getDefault(Locale.Category.FORMAT);
    final CompletableFuture<Map<PublicKey, long[]>> schedule;
    Locale.setDefault(Locale.Category.FORMAT, NON_ASCII_DIGITS);
    try {
      schedule = rpcClient.getLeaderSchedule(Commitment.CONFIRMED, 412_345_678L, IDENTITY);
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, formatLocale);
    }
    assertArrayEquals(new long[]{0, 1, 2, 3}, schedule.join().get(IDENTITY));
  }
}
