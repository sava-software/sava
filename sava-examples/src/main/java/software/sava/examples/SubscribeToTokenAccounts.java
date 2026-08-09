package software.sava.examples;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;

import java.net.http.HttpClient;
import java.util.List;

public final class SubscribeToTokenAccounts {

  /// See [SubscribeToLookupTables#connect]: the returned future is the only report of a failed
  /// handshake, so a durable client watches it and retries; the reconnect throttle paces this.
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
    final var solanaAccounts = SolanaAccounts.MAIN_NET;
    final var tokenProgram = solanaAccounts.tokenProgram();
    final var tokenOwner = PublicKey.fromBase58Encoded("");
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var webSocket = SolanaRpcWebsocket.build()
          .uri(SolanaNetwork.MAIN_NET.getWebSocketEndpoint())
          .webSocketBuilder(httpClient)
          .commitment(Commitment.CONFIRMED)
          .solanaAccounts(solanaAccounts)
          .onOpen(ws -> System.out.println("Websocket connected to " + ws.endpoint().getHost()))
          // A server-side close or a transport error reconnects; wiring every callback to
          // close() meant one transient ping failure permanently terminated the client. Send
          // and ping failures are transient and only logged — the implementation retries them.
          // If the internal check loop itself dies, the instance invokes onError and then
          // closes itself — this reconnect is cancelled and connect() returns null from then
          // on — so a supervisor that must survive even that builds a replacement instance.
          .onClose((ws, statusCode, reason) -> {
            System.out.format("%d: %s — reconnecting%n", statusCode, reason);
            connect(ws);
          })
          .onError((ws, throwable) -> {
            throwable.printStackTrace(System.err);
            connect(ws);
          })
          .onPingError((_, throwable) -> throwable.printStackTrace(System.err))
          .onSendTextError((_, throwable) -> throwable.printStackTrace(System.err))
          .create();

      webSocket.programSubscribe(
          tokenProgram,
          List.of(
              Filter.createDataSizeFilter(TokenAccount.BYTES),
              Filter.createMemCompFilter(TokenAccount.OWNER_OFFSET, tokenOwner)
          ),
          accountInfo -> {
            final var tokenAccount = TokenAccount.read(accountInfo.pubKey(), accountInfo.data());
            System.out.println(tokenAccount);
          }
      );

      try (webSocket) {
        connect(webSocket);
        Thread.sleep(Integer.MAX_VALUE);
      }
    }
  }
}
