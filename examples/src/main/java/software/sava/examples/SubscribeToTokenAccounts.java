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

  public static void main(final String[] args) throws InterruptedException {
    final var solanaAccounts = SolanaAccounts.MAIN_NET;
    final var tokenProgram = solanaAccounts.tokenProgram();
    final var tokenOwner = PublicKey.fromBase58Encoded("");
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var webSocket = SolanaRpcWebsocket.build()
          .uri(SolanaNetwork.MAIN_NET.getWebSocketEndpoint())
          .webSocketBuilder(httpClient)
          .commitment(Commitment.CONFIRMED)
          .solanaAccounts(solanaAccounts)
          .onOpen(ws -> System.out.println("Websocket connected to " + ws.endpoint()))
          .onClose((_, statusCode, reason) -> System.out.format("%d: %s%n", statusCode, reason))
          .onError((_, throwable) -> throwable.printStackTrace())
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
          });

      webSocket.connect();

      Thread.sleep(Integer.MAX_VALUE);
    }
  }
}
