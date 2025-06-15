package software.sava.examples;

import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;

import java.net.http.HttpClient;

public final class SubscribeToLookupTables {

  public static void main(final String[] args) throws InterruptedException {
    try (final var httpClient = HttpClient.newHttpClient()) {

      final var webSocket = SolanaRpcWebsocket.build()
          .uri(SolanaNetwork.MAIN_NET.getWebSocketEndpoint())
          .webSocketBuilder(httpClient)
          .commitment(Commitment.CONFIRMED)
          .onOpen(ws -> System.out.println("Websocket connected"))
          .onClose((ws, statusCode, reason) -> System.out.format("%d: %s%n", statusCode, reason))
          .onError((ws, throwable) -> throwable.printStackTrace())
          .create();

      webSocket.programSubscribe(
          SolanaAccounts.MAIN_NET.addressLookupTableProgram(),
          System.out::println,
          accountInfo -> {
            final var table = AddressLookupTable.read(accountInfo.pubKey(), accountInfo.data());
            System.out.println(table);
          }
      );

      webSocket.connect();

      Thread.sleep(Integer.MAX_VALUE);
    }
  }
}
