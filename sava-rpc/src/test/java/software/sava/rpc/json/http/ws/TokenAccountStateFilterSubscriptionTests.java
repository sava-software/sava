package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Agave's `programSubscribe` runs its filters through the same `verify_filters` as
/// `getProgramAccounts`, so `tokenAccountState` is valid there too. Both program-subscription paths
/// join each filter's JSON into the frame, and this one must arrive as a bare string member.
@ExtendWith(QuietWsLogging.class)
final class TokenAccountStateFilterSubscriptionTests {

  private static final PublicKey TOKEN_2022_PROGRAM =
      PublicKey.fromBase58Encoded("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");
  private static final PublicKey MINT =
      PublicKey.fromBase58Encoded("DbXcVKGeuhnmmi3BhkztB6zs2mEsNkwjnjH1tudQkws");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static SolanaJsonRpcWebsocket websocket() {
    return new SolanaJsonRpcWebsocket(
        URI.create("wss://localhost"), SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        null, TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null
    );
  }

  @Test
  void programSubscribeSendsTheFilterAsABareString() {
    try (final var ws = websocket()) {
      assertTrue(ws.programSubscribe(TOKEN_2022_PROGRAM,
          List.of(TokenAccount.createMintFilter(MINT), Filter.tokenAccountState()), _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("""
          {"jsonrpc":"2.0","id":2,"method":"programSubscribe","params":["TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",\
          {"commitment":"confirmed","encoding":"base64",\
          "filters":[{"memcmp":{"offset":0,"bytes":"DbXcVKGeuhnmmi3BhkztB6zs2mEsNkwjnjH1tudQkws"}},"tokenAccountState"]}]}"""
      ), socket.sentText);
    }
  }

  @Test
  void keyedProgramSubscribeSendsTheFilterAsABareString() {
    try (final var ws = websocket()) {
      assertTrue(ws.keyedProgramSubscribe("token-2022", TOKEN_2022_PROGRAM,
          List.of(Filter.tokenAccountState()), _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("""
          {"jsonrpc":"2.0","id":2,"method":"programSubscribe","params":["TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",\
          {"commitment":"confirmed","encoding":"base64","filters":["tokenAccountState"]}]}"""
      ), socket.sentText);
    }
  }
}
