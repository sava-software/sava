package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Contract tests for the shared Solana request helpers. These call the helper
/// directly so null, empty, and populated collections remain distinguishable
/// without a request fixture choosing one case on the caller's behalf.
final class BaseSolanaJsonRpcClientTests {

  private static final PublicKey KEY =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");

  @Test
  void joinKeysEncodesNullEmptyAndPresentCollections() {
    assertAll(
        () -> assertEquals("[]", BaseSolanaJsonRpcClient.joinKeys(null)),
        () -> assertEquals("[]", BaseSolanaJsonRpcClient.joinKeys(List.<PublicKey>of())),
        () -> assertEquals("[\"" + KEY.toBase58() + "\"]",
            BaseSolanaJsonRpcClient.joinKeys(List.of(KEY)))
    );
  }
}
