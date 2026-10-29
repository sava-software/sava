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
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Agave 4.4 honors dataSlice for account and program subscriptions. The wire oracle is
/// an offset/length object, with empty slices canonicalized to offset zero; slicing does
/// not change the client registration key or the account's reported full space.
@ExtendWith(QuietWsLogging.class)
final class DataSliceSubscriptionTests {

  private static final PublicKey KEY =
      PublicKey.fromBase58Encoded("GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock) {
    return new SolanaJsonRpcWebsocket(
        URI.create("wss://localhost"), SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        null, TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock, new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null
    );
  }

  private static String request(final int id, final String method, final String config) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
        + "\",\"params\":[\"" + KEY + "\"," + config + "]}";
  }

  @Test
  void existingCallsKeepTheirExactRequests() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      assertTrue(ws.programSubscribe(KEY, List.of(Filter.createDataSizeFilter(165)), _ -> {
      }));
      assertTrue(ws.keyedProgramSubscribe("key", KEY, List.of(), _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of(
          request(2, "accountSubscribe", "{\"encoding\":\"base64\",\"commitment\":\"confirmed\"}"),
          request(3, "programSubscribe", "{\"commitment\":\"confirmed\",\"encoding\":\"base64\",\"filters\":[{\"dataSize\":165}]}"),
          request(4, "programSubscribe", "{\"commitment\":\"confirmed\",\"encoding\":\"base64\"}")
      ).stream().sorted().toList(), socket.sentText.stream().sorted().toList());
    }
  }

  @Test
  void sliceRequestsPreserveOffsetLengthAndZero() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(KEY, 4, 3, null, _ -> {
      }));
      assertTrue(ws.programSubscribe(Commitment.FINALIZED, KEY,
          List.of(Filter.createDataSizeFilter(165)), 8, 14, _ -> {
          }));
      assertTrue(ws.keyedProgramSubscribe("empty", KEY, null, 0, Integer.MAX_VALUE, null, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of(
          request(2, "accountSubscribe", "{\"encoding\":\"base64\",\"commitment\":\"confirmed\",\"dataSlice\":{\"offset\":3,\"length\":4}}"),
          request(3, "programSubscribe", "{\"commitment\":\"finalized\",\"encoding\":\"base64\",\"filters\":[{\"dataSize\":165}],\"dataSlice\":{\"offset\":14,\"length\":8}}"),
          request(4, "programSubscribe", "{\"commitment\":\"confirmed\",\"encoding\":\"base64\",\"dataSlice\":{\"offset\":0,\"length\":0}}")
      ).stream().sorted().toList(), socket.sentText.stream().sorted().toList());
    }
  }

  /// The delegating overloads the test above does not reach. Every pair is asymmetric, so a
  /// delegation that swapped its two int arguments would send a different window.
  @Test
  void delegatingSliceOverloadsPassLengthThenOffset() {
    final var otherProgram = PublicKey.fromBase58Encoded("4Qkev8aNZcqFNSRhQzwyLMFSsi94jHqE8WNVTJzTP99F");
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(Commitment.PROCESSED, KEY, 5, 1, _ -> {
      }));
      assertTrue(ws.programSubscribe(KEY, List.of(), 6, 2, _ -> {
      }));
      assertTrue(ws.programSubscribe(otherProgram, List.of(), 7, 3, null, _ -> {
      }));
      assertTrue(ws.keyedProgramSubscribe(Commitment.FINALIZED, "key", KEY, List.of(), 8, 4, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of(
          request(2, "accountSubscribe", "{\"encoding\":\"base64\",\"commitment\":\"processed\",\"dataSlice\":{\"offset\":1,\"length\":5}}"),
          request(3, "programSubscribe", "{\"commitment\":\"confirmed\",\"encoding\":\"base64\",\"dataSlice\":{\"offset\":2,\"length\":6}}"),
          "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"programSubscribe\",\"params\":[\"" + otherProgram
              + "\",{\"commitment\":\"confirmed\",\"encoding\":\"base64\",\"dataSlice\":{\"offset\":3,\"length\":7}}]}",
          request(5, "programSubscribe", "{\"commitment\":\"finalized\",\"encoding\":\"base64\",\"dataSlice\":{\"offset\":4,\"length\":8}}")
      ).stream().sorted().toList(), socket.sentText.stream().sorted().toList());
    }
  }

  @Test
  void emptySlicesAtDifferentOffsetsCoalesceWithoutReplacingTheConnection() {
    try (final var ws = websocket(new TestClock())) {
      final var errors = new ArrayList<RuntimeException>();
      final var first = new ArrayList<AccountInfo<byte[]>>();
      final var second = new ArrayList<AccountInfo<byte[]>>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.keyedProgramSubscribe("first", KEY, List.of(), 0, 1, first::add));
      assertTrue(ws.keyedProgramSubscribe("second", KEY, List.of(), 0, 9, second::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":71}"), true);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":71}"), true);

      assertFalse(socket.aborted, "equivalent empty slices must not escalate the connection");
      assertEquals(1, errors.size(), "the duplicate registration is reported to its consumer");
      assertInstanceOf(IllegalStateException.class, errors.getFirst());
      assertTrue(errors.getFirst().getMessage().contains("server coalesced identical params"));
      assertFalse(ws.keyedProgramUnsubscribe("second"), "the duplicate registration was released");
      assertEquals(2, socket.sentText.size(), "the shared server subscription must not be cancelled");

      ws.onText(socket, CharBuffer.wrap(
          "{\"jsonrpc\":\"2.0\",\"method\":\"programNotification\",\"params\":{\"result\":{\"context\":{\"slot\":6},\"value\":{\"pubkey\":\""
              + KEY + "\",\"account\":{\"data\":[\"\",\"base64\"],\"space\":16,\"lamports\":42,"
              + "\"owner\":\"11111111111111111111111111111111\",\"executable\":false,\"rentEpoch\":0}}},\"subscription\":71}}"), true);
      assertEquals(1, first.size(), "the first registration continues receiving the shared stream");
      assertArrayEquals(new byte[0], first.getFirst().data());
      assertTrue(second.isEmpty());
      assertTrue(ws.keyedProgramUnsubscribe("first"));
    }
  }

  @Test
  void slicesShareTheExistingRegistrationIdentityAndCancellation() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(KEY, 2, 1, _ -> {
      }));
      assertFalse(ws.accountSubscribe(KEY, 9, 7, _ -> {
      }));
      assertFalse(ws.accountSubscribe(KEY, 9, 7, _ -> {
      }, _ -> {
      }), "the callback convenience overload must report the occupied account slot");
      assertFalse(ws.accountSubscribe(KEY, _ -> {
      }));
      assertTrue(ws.accountUnsubscribe(KEY));
      assertTrue(ws.accountSubscribe(Commitment.PROCESSED, KEY, 5, 4, _ -> {
      }));
      assertFalse(ws.accountSubscribe(Commitment.PROCESSED, KEY, 9, 7, _ -> {
      }), "the commitment convenience overload must report the occupied account slot");
      assertTrue(ws.accountUnsubscribe(Commitment.PROCESSED, KEY));

      assertTrue(ws.programSubscribe(KEY, List.of(), 3, 2, _ -> {
      }));
      assertFalse(ws.programSubscribe(KEY, List.of(), 9, 8, _ -> {
      }), "the default-commitment convenience overload must report the occupied program slot");
      assertFalse(ws.programSubscribe(Commitment.CONFIRMED, KEY, List.of(), 9, 8, _ -> {
      }), "the commitment convenience overload must report the occupied program slot");
      assertFalse(ws.programSubscribe(KEY, null, 9, 8, null, _ -> {
      }));
      assertFalse(ws.programSubscribe(KEY, _ -> {
      }));
      assertTrue(ws.programUnsubscribe(KEY));

      assertTrue(ws.keyedProgramSubscribe("first", KEY, List.of(), 1, 0, _ -> {
      }));
      assertTrue(ws.keyedProgramSubscribe(Commitment.FINALIZED, "first", KEY, List.of(), 4, 3, _ -> {
      }));
      assertTrue(ws.keyedProgramSubscribe("second", KEY, List.of(), 1, 1, _ -> {
      }));
      assertFalse(ws.keyedProgramSubscribe("first", KEY, List.of(), 9, 9, _ -> {
      }));
      assertFalse(ws.keyedProgramSubscribe("first", KEY, List.of(), 9, 9, _ -> {
      }, _ -> {
      }), "the callback convenience overload must report the occupied caller key");
      assertFalse(ws.keyedProgramSubscribe(Commitment.FINALIZED, "first", KEY, List.of(), 9, 9, _ -> {
      }), "the commitment convenience overload must report the occupied caller key");
      assertTrue(ws.keyedProgramUnsubscribe("first"));
      assertFalse(ws.keyedProgramUnsubscribe("first"));
      assertTrue(ws.keyedProgramUnsubscribe(Commitment.FINALIZED, "first"));
      assertTrue(ws.keyedProgramUnsubscribe("second"));
    }
  }

  @Test
  void slicedNotificationsRetainWholeAccountMetadataAndReplayUnchanged() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var accounts = new ArrayList<AccountInfo<byte[]>>();
      final var programs = new ArrayList<AccountInfo<byte[]>>();
      final var handles = new ArrayList<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(KEY, 4, 3, handles::add, accounts::add));
      assertTrue(ws.keyedProgramSubscribe("empty", KEY, List.of(), 0, 9, handles::add, programs::add));
      assertTrue(ws.programSubscribe(KEY, List.of(), 1, 2, handles::add, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":71}"), true);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":72}"), true);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":73}"), true);
      assertEquals(3, handles.size(), "each sliced convenience overload forwards its onSub callback");
      assertTrue(handles.stream().allMatch(handle -> handle.publicKey().equals(KEY)));
      assertTrue(handles.stream().allMatch(handle -> handle.commitment() == Commitment.CONFIRMED));
      final var value = "{\"data\":[\"%s\",\"base64\"],\"space\":16,\"lamports\":42,"
          + "\"owner\":\"11111111111111111111111111111111\",\"executable\":false,\"rentEpoch\":0}";
      ws.onText(socket, CharBuffer.wrap(
          "{\"jsonrpc\":\"2.0\",\"method\":\"accountNotification\",\"params\":{\"result\":{\"context\":{\"slot\":5},\"value\":"
              + value.formatted("MzQ1Ng==") + "},\"subscription\":71}}"), true);
      ws.onText(socket, CharBuffer.wrap(
          "{\"jsonrpc\":\"2.0\",\"method\":\"programNotification\",\"params\":{\"result\":{\"context\":{\"slot\":6},\"value\":{\"pubkey\":\""
              + KEY + "\",\"account\":" + value.formatted("") + "}},\"subscription\":72}}"), true);
      assertEquals(1, accounts.size());
      assertArrayEquals(new byte[]{'3', '4', '5', '6'}, accounts.getFirst().data());
      assertEquals(16, accounts.getFirst().space());
      assertEquals(42, accounts.getFirst().lamports());
      assertEquals(1, programs.size());
      assertArrayEquals(new byte[0], programs.getFirst().data());
      assertEquals(16, programs.getFirst().space());
      assertEquals(KEY, programs.getFirst().pubKey());

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var replacement = new RecordingWebSocket();
      ws.onOpen(replacement);
      assertEquals(socket.sentText.stream().sorted().toList(), replacement.sentText.stream().sorted().toList());
      assertTrue(ws.accountUnsubscribe(KEY));
      assertTrue(ws.keyedProgramUnsubscribe("empty"));
      assertTrue(ws.programUnsubscribe(KEY));
    }
  }

  @Test
  void negativeSlicesAreRejectedBeforeRegistration() {
    try (final var ws = websocket(new TestClock())) {
      for (final int[] slice : List.of(new int[]{-1, 2}, new int[]{3, -1})) {
        assertThrows(IllegalArgumentException.class, () -> ws.accountSubscribe(KEY, slice[0], slice[1], _ -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> ws.programSubscribe(KEY, null, slice[0], slice[1], _ -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> ws.keyedProgramSubscribe("key", KEY, null, slice[0], slice[1], _ -> {
        }));
      }
      assertFalse(ws.accountUnsubscribe(KEY));
      assertFalse(ws.programUnsubscribe(KEY));
      assertFalse(ws.keyedProgramUnsubscribe("key"));
    }
  }

  @Test
  void olderImplementationsKeepTheAdditiveDefaults() {
    final var ws = (SolanaRpcWebsocket) Proxy.newProxyInstance(
        SolanaRpcWebsocket.class.getClassLoader(), new Class<?>[]{SolanaRpcWebsocket.class},
        (proxy, method, args) -> {
          if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
          } else if (method.getName().equals("defaultCommitment")) {
            return Commitment.CONFIRMED;
          }
          throw new AssertionError("Unexpected abstract call: " + method);
        }
    );
    assertThrows(UnsupportedOperationException.class, () -> ws.accountSubscribe(KEY, 0, 0, _ -> {
    }));
    assertThrows(UnsupportedOperationException.class, () -> ws.programSubscribe(KEY, null, 0, 0, _ -> {
    }));
    assertThrows(UnsupportedOperationException.class, () -> ws.keyedProgramSubscribe("key", KEY, null, 0, 0, _ -> {
    }));
  }
}
