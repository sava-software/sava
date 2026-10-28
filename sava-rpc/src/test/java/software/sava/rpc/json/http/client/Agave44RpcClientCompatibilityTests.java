package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.Tx;
import software.sava.rpc.json.http.response.TxStatus;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/// Existing third-party implementations need not implement the Agave 4.4 methods, and must
/// receive an explicit asynchronous failure when asked to honor options they do not support.
final class Agave44RpcClientCompatibilityTests {

  @Test
  void newOptionsFailExplicitlyWhileAnExistingImplementationStillServesOldCalls() {
    final var signatures = List.of("existing-signature");
    final var statuses = Map.of("existing-signature",
        new TxStatus(null, 48, OptionalInt.empty(), null, Commitment.FINALIZED));
    final var statusList = List.copyOf(statuses.values());
    final var transaction = new Tx(91, OptionalLong.empty(), null, new byte[]{1, 2, 3}, 1, OptionalInt.empty());
    final var schedule = Map.of(PublicKey.NONE, new long[]{3, 7});

    // The handler stands in for an implementation of only the pre-4.4 abstract methods.
    // Invoke real interface default bodies, including their dispatch to that old surface.
    final var client = (SolanaRpcClient) Proxy.newProxyInstance(
        SolanaRpcClient.class.getClassLoader(), new Class<?>[]{SolanaRpcClient.class},
        (proxy, method, args) -> {
          if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
          }
          return switch (method.getName()) {
            case "defaultCommitment" -> Commitment.CONFIRMED;
            case "getSignatureStatuses" -> CompletableFuture.completedFuture(statuses);
            case "getSigStatusList" -> CompletableFuture.completedFuture(statusList);
            case "getTransaction" -> CompletableFuture.completedFuture(transaction);
            case "getLeaderSchedule" -> CompletableFuture.completedFuture(schedule);
            default -> throw new AssertionError("Unexpected call: " + method);
          };
        });

    assertSame(statuses, client.getSignatureStatuses(signatures).join());
    assertSame(statusList, client.getSigStatusList(signatures).join());
    assertSame(transaction, client.getTransaction("existing-signature").join());
    assertSame(schedule, client.getLeaderSchedule().join());

    assertUnsupported(() -> client.getSignatureStatuses(Commitment.CONFIRMED, signatures, true, BigInteger.ZERO));
    assertUnsupported(() -> client.getSigStatusList(Commitment.CONFIRMED, signatures, true, BigInteger.ZERO));
    assertUnsupported(() -> client.getTransaction(Commitment.CONFIRMED, "existing-signature", BigInteger.ZERO));
    assertUnsupported(() -> client.getLeaderSchedule(Commitment.CONFIRMED, null, PublicKey.NONE, true));
  }

  private static void assertUnsupported(final Supplier<CompletableFuture<?>> request) {
    final var future = assertDoesNotThrow(request::get);
    assertTrue(future.isCompletedExceptionally());
    final var exception = assertThrows(CompletionException.class, future::join);
    assertInstanceOf(UnsupportedOperationException.class, exception.getCause());
  }
}
