package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Explicitly keyed program subscriptions are the opt-in multi-filter shape. Their caller keys
/// form a namespace separate from legacy program-address registrations, survive reconnect, and
/// identify exactly one registration for cancellation.
@ExtendWith(QuietWsLogging.class)
final class KeyedProgramSubscriptionTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey PROGRAM =
      PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock,
        new RecordingExecutor(),
        null,
        null,
        (_, _, _) -> {
        },
        null, null, null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws,
                           final RecordingWebSocket socket,
                           final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  /// Models a third-party implementation compiled before the keyed defaults existed: abstract
  /// calls are handled by the proxy, while interface defaults execute their real bodies.
  private static SolanaRpcWebsocket unsupportedImplementation() {
    return (SolanaRpcWebsocket) Proxy.newProxyInstance(
        SolanaRpcWebsocket.class.getClassLoader(),
        new Class<?>[]{SolanaRpcWebsocket.class},
        (proxy, method, args) -> {
          if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
          } else if (method.getName().equals("defaultCommitment")) {
            return Commitment.CONFIRMED;
          } else {
            throw new AssertionError("Unexpected abstract call: " + method);
          }
        }
    );
  }

  @Test
  void implementationsWithoutTheAdditiveCapabilityFailClearly() {
    final var ws = unsupportedImplementation();
    final var subscribeFailure = assertThrows(UnsupportedOperationException.class, () ->
        ws.keyedProgramSubscribe("oracle-mappings", PROGRAM, List.of(), _ -> {
        })
    );
    assertEquals("Keyed program subscriptions are not supported by this implementation.",
        subscribeFailure.getMessage());

    final var unsubscribeFailure = assertThrows(UnsupportedOperationException.class, () ->
        ws.keyedProgramUnsubscribe("oracle-mappings")
    );
    assertEquals("Keyed program subscriptions are not supported by this implementation.",
        unsubscribeFailure.getMessage());
  }

  @Test
  void subscriptionKeysAreRequired() {
    try (final var ws = websocket(new TestClock())) {
      assertThrows(IllegalArgumentException.class, () ->
          ws.keyedProgramSubscribe(null, PROGRAM, List.of(), _ -> {
          })
      );
      assertThrows(IllegalArgumentException.class, () ->
          ws.keyedProgramSubscribe("", PROGRAM, List.of(), _ -> {
          })
      );
      assertThrows(IllegalArgumentException.class, () -> ws.keyedProgramUnsubscribe(null));
      assertThrows(IllegalArgumentException.class, () -> ws.keyedProgramUnsubscribe(""));
    }
  }

  @Test
  void commitmentIsTheSecondIdentityDimension() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.keyedProgramSubscribe(
          "shared-key", PROGRAM, List.of(Filter.createDataSizeFilter(165)), _ -> {
          }
      ));
      assertTrue(ws.keyedProgramSubscribe(
          Commitment.FINALIZED, "shared-key", PROGRAM, List.of(Filter.createDataSizeFilter(80)), _ -> {
          }
      ));
      assertFalse(ws.keyedProgramSubscribe(
          Commitment.FINALIZED, "shared-key", PROGRAM, List.of(Filter.createDataSizeFilter(99)), _ -> {
          }
      ));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(socket.sentText.getFirst().contains("\"commitment\":\"confirmed\""), socket.sentText.getFirst());
      assertTrue(socket.sentText.getLast().contains("\"commitment\":\"finalized\""), socket.sentText.getLast());

      assertFalse(ws.keyedProgramUnsubscribe(Commitment.PROCESSED, "shared-key"));
      assertTrue(ws.keyedProgramUnsubscribe(Commitment.FINALIZED, "shared-key"));
      assertTrue(ws.keyedProgramUnsubscribe("shared-key"), "the default-commitment registration remains");
    }
  }

  @Test
  void distinctKeysSubscribeToTheSameProgramAndCommitmentWithDifferentFilters() {
    try (final var ws = websocket(new TestClock())) {
      final var oracleSub = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var configSub = new AtomicReference<Subscription<AccountInfo<byte[]>>>();

      assertTrue(ws.keyedProgramSubscribe(
          "oracle-mappings", PROGRAM, List.of(Filter.createDataSizeFilter(165)), oracleSub::set, _ -> {
          }
      ));
      assertTrue(ws.keyedProgramSubscribe(
          "configuration", PROGRAM, List.of(Filter.createDataSizeFilter(80)), configSub::set, _ -> {
          }
      ));
      assertFalse(ws.keyedProgramSubscribe(
          "oracle-mappings", PROGRAM, List.of(Filter.createDataSizeFilter(999)), oracleSub::set, _ -> {
          }
      ), "the caller key, not filters, owns the registration slot");
      assertFalse(ws.keyedProgramSubscribe(
          "configuration", PROGRAM, List.of(Filter.createDataSizeFilter(999)), _ -> {
          }
      ), "the four-argument convenience overload must preserve duplicate rejection");

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(List.of(
          """
              {"jsonrpc":"2.0","id":2,"method":"programSubscribe","params":["GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc",{"commitment":"confirmed","encoding":"base64","filters":[{"dataSize":165}]}]}""",
          """
              {"jsonrpc":"2.0","id":3,"method":"programSubscribe","params":["GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc",{"commitment":"confirmed","encoding":"base64","filters":[{"dataSize":80}]}]}"""
      ), socket.sentText);
      assertEquals("oracle-mappings", oracleSub.get().key());
      assertEquals(PROGRAM, oracleSub.get().publicKey());
      assertEquals("configuration", configSub.get().key());
      assertEquals(PROGRAM, configSub.get().publicKey());
    }
  }

  @Test
  void nullAndEmptyFilterListsBothOmitFiltersFromTheRequest() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.keyedProgramSubscribe("null-filters", PROGRAM, null, _ -> {
      }));
      assertTrue(ws.keyedProgramSubscribe("empty-filters", PROGRAM, List.of(), _ -> {
      }));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(2, socket.sentText.size());
      assertFalse(socket.sentText.getFirst().contains("\"filters\""), socket.sentText.getFirst());
      assertFalse(socket.sentText.getLast().contains("\"filters\""), socket.sentText.getLast());
    }
  }

  @Test
  void keyedUnsubscribeRemovesOnlyTheExactRegistration() {
    final var oracleReceived = new ArrayList<AccountInfo<byte[]>>();
    final var configReceived = new ArrayList<AccountInfo<byte[]>>();
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.keyedProgramSubscribe(
          "oracle-mappings", PROGRAM, List.of(Filter.createDataSizeFilter(165)), oracleReceived::add
      ));
      assertTrue(ws.keyedProgramSubscribe(
          "configuration", PROGRAM, List.of(Filter.createDataSizeFilter(80)), configReceived::add
      ));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":101,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":102,"id":3}""");

      assertTrue(ws.keyedProgramUnsubscribe("oracle-mappings"));
      assertFalse(ws.keyedProgramUnsubscribe("oracle-mappings"));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      assertEquals("""
          {"jsonrpc":"2.0","id":4,"method":"programUnsubscribe","params":[101]}""", socket.sentText.getLast());

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"programNotification","params":{"result":{"context":{"slot":5208469},"value":{"pubkey":"H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq","account":{"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,"owner":"GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc","rentEpoch":636,"space":80}}},"subscription":102}}""");
      assertTrue(oracleReceived.isEmpty(), "the removed registration receives nothing");
      assertEquals(1, configReceived.size(), "the other key remains live");
      assertTrue(ws.keyedProgramUnsubscribe("configuration"), "the other registry slot remains present");
    }
  }

  @Test
  void bothKeyedRegistrationsReplayOnReconnect() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.keyedProgramSubscribe(
          "oracle-mappings", PROGRAM, List.of(Filter.createDataSizeFilter(165)), _ -> {
          }
      ));
      assertTrue(ws.keyedProgramSubscribe(
          "configuration", PROGRAM, List.of(Filter.createDataSizeFilter(80)), _ -> {
          }
      ));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertEquals(2, first.sentText.size());

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);

      assertTrue(first.aborted, "the replacement retires the old transport");
      assertEquals(first.sentText, second.sentText, "both durable requests replay unchanged");
    }
  }

  @Test
  void keyedNamespaceDoesNotChangeLegacyProgramDuplicateIdentity() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.programSubscribe(PROGRAM, List.of(Filter.createDataSizeFilter(165)), _ -> {
      }));
      assertFalse(ws.programSubscribe(PROGRAM, List.of(Filter.createDataSizeFilter(80)), _ -> {
      }), "legacy identity remains program plus commitment");

      assertTrue(ws.keyedProgramSubscribe(
          PROGRAM.toBase58(), PROGRAM, List.of(Filter.createDataSizeFilter(80)), _ -> {
          }
      ), "a caller key equal to the legacy map key still occupies a separate namespace");

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(2, socket.sentText.size());
      assertTrue(socket.sentText.getFirst().contains("\"dataSize\":165"), socket.sentText.getFirst());
      assertTrue(socket.sentText.getLast().contains("\"dataSize\":80"), socket.sentText.getLast());
    }
  }

  @Test
  void requestDefectRejectionFreesTheKeyedSlot() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.keyedProgramSubscribe(
          "oracle-mappings", PROGRAM, List.of(Filter.createDataSizeFilter(165)), _ -> {
          }
      ));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":2}""");

      assertTrue(ws.keyedProgramSubscribe(
          "oracle-mappings", PROGRAM, List.of(Filter.createDataSizeFilter(80)), _ -> {
          }
      ), "a corrected request can reclaim the rejected registration's key");
    }
  }

  @Test
  void closeReleasesKeyedRegistrations() {
    final var ws = websocket(new TestClock());
    assertTrue(ws.keyedProgramSubscribe(
        "oracle-mappings", PROGRAM, List.of(Filter.createDataSizeFilter(165)), _ -> {
        }
    ));
    assertTrue(ws.keyedProgramSubscribe(
        "configuration", PROGRAM, List.of(Filter.createDataSizeFilter(80)), _ -> {
        }
    ));
    assertEquals(2, ws.retainedRegistrations());

    ws.close();

    assertEquals(0, ws.retainedRegistrations());
  }
}
