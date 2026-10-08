package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Defensive tests for the ownership invariants this library's websocket registries rely
/// on. Each releases, cancels or installs a registration only through the registration that
/// owns the slot, the server id, or the in-flight cancellation gate in question. The peer
/// frames drive the shapes where two registrations, or two phases of one, meet the same key
/// or id; the oracles are the public subscribe results, the handles `onSub` hands out, the
/// frames on the wire and the retention seams, never the branch structure that keeps them
/// apart.
@ExtendWith(QuietWsLogging.class)
final class RegistryOwnershipInvariantTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  private static final PublicKey ACCOUNT =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey PROGRAM =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final String SIGNATURE =
      "2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b";

  private static SolanaJsonRpcWebsocket websocket() {
    return websocket(new TestClock());
  }

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
        _ -> {
        },
        (_, _, _) -> {
        },
        null,
        null,
        null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws,
                           final RecordingWebSocket socket,
                           final String json) {
    ws.onText(socket, json, true);
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
  }

  private static String grant(final long subId, final long requestId) {
    return "{\"jsonrpc\":\"2.0\",\"result\":" + subId + ",\"id\":" + requestId + '}';
  }

  private static String requestDefect(final long requestId) {
    return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"},\"id\":"
        + requestId + '}';
  }

  private static String accountNotification(final long subId) {
    return """
        {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":"""
        + subId + "}}";
  }

  /// Release casts a registration with no `Channel` to `GenericSubscription`. Every handle
  /// the engine builds, of every kind, is collected through `onSub` once its request is
  /// sent: only the generic one lacks a channel. A request-defect rejection then releases
  /// each kind, generic included, delivering only the server's errors and retaining
  /// nothing.
  @Test
  void onlyGenericRegistrationsLackAChannel() {
    try (final var ws = websocket()) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      final var handles = new ArrayList<Subscription<?>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT, handles::add, _ -> {
      }));
      assertTrue(ws.logsSubscribe(Commitment.CONFIRMED, ACCOUNT, handles::add, _ -> {
      }));
      assertTrue(ws.signatureSubscribe(Commitment.CONFIRMED, SIGNATURE, handles::add, _ -> {
      }));
      assertTrue(ws.programSubscribe(Commitment.CONFIRMED, PROGRAM, List.of(), handles::add, _ -> {
      }));
      assertTrue(ws.keyedProgramSubscribe(
          Commitment.CONFIRMED, "keyed", PROGRAM, List.of(), handles::add, _ -> {
          }));
      assertTrue(ws.slotSubscribe(handles::add, _ -> {
      }));
      assertTrue(ws.rootSubscribe(handles::add, _ -> {
      }));
      assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification", "foo", "\"p\"",
          JsonIterator::readLong, handles::add, _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // requests 2 through 9 on the wire

      assertEquals(8, handles.size(), handles::toString);
      for (final var handle : handles) {
        assertEquals(handle instanceof GenericSubscription<?>, handle.channel() == null,
            handle::toString);
      }

      for (long requestId = 2; requestId <= 9; ++requestId) {
        feed(ws, socket, requestDefect(requestId));
      }
      assertEquals(8, errors.size(), errors::toString);
      for (final var error : errors) {
        assertEquals((long) JsonRpcException.INVALID_PARAMS,
            assertInstanceOf(JsonRpcException.class, error).code());
      }
      assertEquals(0, ws.retainedRegistrations(), "every kind released its own slot");
    }
  }

  @FunctionalInterface
  private interface Registration {

    boolean subscribe(SolanaJsonRpcWebsocket ws, Consumer<Object> delivered);
  }

  /// A predecessor cancelled while its request is on the wire leaves the pending map in the
  /// same locked call that frees its slot. Its late terminal rejection therefore finds
  /// nothing pending to release, and the successor registered under the same slot keeps it
  /// and is still served once granted.
  private static void assertLateRejectionLeavesTheSuccessor(final String kind,
                                                            final Registration registration,
                                                            final Predicate<SolanaJsonRpcWebsocket> unregister,
                                                            final String successorNotification)
      throws InterruptedException {
    try (final var ws = websocket()) {
      assertTrue(registration.subscribe(ws, _ -> {
      }), kind);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // predecessor request 2
      assertTrue(unregister.test(ws), kind); // cancelled in flight
      final var delivered = new ArrayList<>();
      assertTrue(registration.subscribe(ws, delivered::add), kind);
      ws.checkCycle(0L); // successor request 3

      feed(ws, socket, requestDefect(2));

      assertFalse(registration.subscribe(ws, _ -> {
      }), kind + ": the successor still owns the slot");
      feed(ws, socket, grant(60, 3));
      feed(ws, socket, successorNotification);
      assertEquals(1, delivered.size(), kind + ": the successor is still served");
    }
  }

  @Test
  void aPredecessorsLateRejectionCannotReleaseItsSuccessor() throws InterruptedException {
    assertLateRejectionLeavesTheSuccessor("slot",
        (ws, delivered) -> ws.slotSubscribe(delivered::accept),
        SolanaJsonRpcWebsocket::slotUnsubscribe,
        """
            {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":60}}""");
    assertLateRejectionLeavesTheSuccessor("root",
        (ws, delivered) -> ws.rootSubscribe(delivered::accept),
        SolanaJsonRpcWebsocket::rootUnsubscribe,
        """
            {"jsonrpc":"2.0","method":"rootNotification","params":{"result":2,"subscription":60}}""");
    assertLateRejectionLeavesTheSuccessor("generic",
        (ws, delivered) -> ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification", "k",
            "\"p\"", JsonIterator::readLong, null, delivered::accept),
        ws -> ws.unsubscribe("fooNotification", "k"),
        """
            {"jsonrpc":"2.0","method":"fooNotification","params":{"result":9,"subscription":60}}""");
  }

  /// A cancellation names a server id only when the unsubscribed registration owns it on
  /// the current connection. The two shapes in which a handle loses its id while staying
  /// registered: adoption of a successor transport, and a casualty replay after a predating
  /// cancellation. Unsubscribing in either window must not name the dead id; the request
  /// still on the wire is compensated when its own grant arrives.
  @Test
  void anUnsubscribeCancelsOnlyTheIdItsRegistrationWasGranted() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var handle = new AtomicReference<Subscription<?>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT, handle::set, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      feed(ws, first, grant(700, 2));
      assertEquals(BigInteger.valueOf(700), handle.get().subId());

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1); // the replay is due on adoption
      final var second = new RecordingWebSocket();
      ws.onOpen(second); // 700 died with the first transport
      assertEquals(1L, framesContaining(second, "\"id\":2,\"method\":\"accountSubscribe\""),
          second.sentText::toString);
      assertTrue(ws.accountUnsubscribe(ACCOUNT)); // before the second transport confirms
      ws.checkCycle(0L);
      feed(ws, second, grant(800, 2)); // the cancelled request's own grant

      assertEquals(0L, framesContaining(second, "\"params\":[700]"), second.sentText::toString);
      assertEquals(1L, framesContaining(second, "\"method\":\"accountUnsubscribe\",\"params\":[800]"),
          second.sentText::toString);
    }

    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // predecessor request 2
      assertTrue(ws.accountUnsubscribe(ACCOUNT)); // tombstoned in flight
      final var handle = new AtomicReference<Subscription<?>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT, handle::set, _ -> {
      }));
      ws.checkCycle(0L); // successor request 3 precedes the compensation
      feed(ws, socket, grant(700, 2)); // compensation 4 cancels 700
      feed(ws, socket, grant(700, 3)); // id reuse: the successor maps 700
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":4}"); // casualty replay
      assertNull(handle.get().subId());

      assertTrue(ws.accountUnsubscribe(ACCOUNT)); // before the replay is granted
      ws.checkCycle(0L);

      assertEquals(1L, framesContaining(socket, "\"params\":[700]"),
          () -> "only the compensation may name the casualty's dead id: " + socket.sentText);
    }
  }

  /// One wire cancellation per subscription id at a time. A second obligation for an id
  /// whose cancellation is unanswered waits in the queue, and a maintenance pass that meets
  /// the held gate leaves it there; the answer releases exactly one more frame.
  @Test
  void aGatedIdGetsNoSecondCancellationFromTheFlush() throws InterruptedException {
    try (final var ws = websocket()) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":77}}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"rootNotification","params":{"result":2,"subscription":77}}""");
      ws.checkCycle(0L); // the flush meets the gate cancellation 2 holds

      assertEquals(1L, framesContaining(socket, "\"params\":[77]"), socket.sentText::toString);

      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":false,\"id\":2}");
      ws.checkCycle(0L);

      assertEquals(2L, framesContaining(socket, "\"params\":[77]"), socket.sentText::toString);
      assertTrue(socket.sentText.getLast().contains("\"method\":\"rootUnsubscribe\""),
          socket.sentText::toString);
    }
  }

  /// The one shape in which a pending registration meets its own former id: a casualty
  /// replay re-granted the id an id-reusing peer gave it before. It is installed exactly as
  /// a first grant is (no collision report, its handle confirmed, notifications delivered),
  /// and the connection counts it once, as installed rather than also pending.
  @Test
  void aCasualtyReGrantedItsOldIdIsInstalledWithoutACollision() throws InterruptedException {
    try (final var ws = websocket()) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // predecessor request 2
      assertTrue(ws.accountUnsubscribe(ACCOUNT)); // tombstoned in flight
      final var handle = new AtomicReference<Subscription<?>>();
      final var delivered = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT, handle::set, delivered::add));
      ws.checkCycle(0L); // successor request 3 precedes the compensation
      feed(ws, socket, grant(700, 2)); // compensation 4 cancels 700
      feed(ws, socket, grant(700, 3)); // id reuse: the successor maps 700
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":4}"); // casualty replay
      assertNull(handle.get().subId());

      ws.checkCycle(0L); // request 3 replayed
      feed(ws, socket, grant(700, 3)); // re-granted the same id

      assertTrue(errors.isEmpty(), () -> "a replay granted its own old id is no collision: " + errors);
      assertEquals(BigInteger.valueOf(700), handle.get().subId());
      assertEquals(2, ws.retainedRegistrations(),
          "the durable key plus one connection entry: installed, not also pending");
      feed(ws, socket, accountNotification(700));
      assertEquals(1, delivered.size());
    }
  }
}
