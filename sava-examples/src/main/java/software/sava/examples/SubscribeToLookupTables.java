package software.sava.examples;

import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;

import java.net.http.HttpClient;

public final class SubscribeToLookupTables {

  /// A failed handshake is reported through the returned future — with no established socket
  /// there may be no onError to retry it — so a durable client watches the future and tries
  /// again; the implementation's reconnect throttle paces the recursion.
  private static void connect(final SolanaRpcWebsocket webSocket) {
    final var attempt = webSocket.connect();
    if (attempt != null) {
      attempt.whenComplete((_, throwable) -> {
        if (throwable != null) {
          System.err.println("Connect failed, retrying: " + throwable);
          connect(webSocket);
        }
      });
    }
  }

  static void main() throws InterruptedException {
    try (final var httpClient = HttpClient.newHttpClient()) {

      final var webSocket = SolanaRpcWebsocket.build()
          .uri(SolanaNetwork.MAIN_NET.getWebSocketEndpoint())
          .webSocketBuilder(httpClient)
          .commitment(Commitment.CONFIRMED)
          .onOpen(_ -> System.out.println("Websocket connected"))
          // The examples are de-facto documentation, so they carry the minimal wiring a
          // long-lived consumer needs: a server-side close or a transport error reconnects —
          // logging alone would leave the instance without a successor. A failed Ping send also
          // retires the current transport and enters onError; onPingError observes that same
          // failure after the recovery policy runs. Failed text sends remain observable through
          // onSendTextError, and eligible subscription work is paced for retry. One failure this
          // wiring cannot cover: if the internal check loop itself dies, the instance invokes
          // onError and then closes itself — the reconnect started here is cancelled and
          // connect() returns null from then on — so a supervisor that must survive even that
          // builds a replacement instance.
          .onClose((ws, statusCode, reason) -> {
            System.out.format("%d: %s — reconnecting%n", statusCode, reason);
            connect(ws);
          })
          .onError((ws, throwable) -> {
            throwable.printStackTrace(System.err);
            connect(ws);
          })
          .onSendTextError((_, throwable) -> throwable.printStackTrace(System.err))
          .onPingError((_, throwable) -> throwable.printStackTrace(System.err))
          .create();

      webSocket.programSubscribe(
          SolanaAccounts.MAIN_NET.addressLookupTableProgram(),
          System.out::println,
          accountInfo -> {
            final var table = AddressLookupTable.read(accountInfo.pubKey(), accountInfo.data());
            System.out.println(table);
          }
      );

      try (webSocket) {
        connect(webSocket);
        Thread.sleep(Integer.MAX_VALUE);
      }
    }
  }
}
