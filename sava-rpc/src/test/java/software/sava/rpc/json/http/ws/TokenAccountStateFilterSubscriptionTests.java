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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
  private static final PublicKey OWNER =
      PublicKey.fromBase58Encoded("H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA");
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
          List.of(TokenAccount.createMintFilter(MINT), Filter.createTokenAccountStateFilter()), _ -> {
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
          List.of(Filter.createTokenAccountStateFilter()), _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("""
          {"jsonrpc":"2.0","id":2,"method":"programSubscribe","params":["TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",\
          {"commitment":"confirmed","encoding":"base64","filters":["tokenAccountState"]}]}"""
      ), socket.sentText);
    }
  }

  /// The token-account helpers that name the program select with the state filter, so a Token-2022
  /// account with extensions is followed; the released helpers keep their 165-byte size filter on
  /// the SPL Token program and register under that program, so both can be live at once.
  @Test
  void subscribeToTokenAccountUnderTheNamedProgramUsesTheStateFilter() {
    try (final var ws = websocket()) {
      assertTrue(ws.subscribeToTokenAccount(TOKEN_2022_PROGRAM, MINT, OWNER, _ -> {
      }));
      // keyed by the program and the commitment, so the same program is a duplicate
      assertFalse(ws.subscribeToTokenAccount(Commitment.CONFIRMED, TOKEN_2022_PROGRAM, MINT, OWNER, _ -> {
      }));
      // and another commitment under the same program is its own registration
      assertTrue(ws.subscribeToTokenAccount(Commitment.FINALIZED, TOKEN_2022_PROGRAM, MINT, OWNER, _ -> {
      }));
      // the default-commitment overload reports the duplicate it forwards into
      assertFalse(ws.subscribeToTokenAccount(TOKEN_2022_PROGRAM, MINT, OWNER, _ -> {
      }));
      // the released helper registers under the SPL Token program, a different key
      assertTrue(ws.subscribeToTokenAccount(MINT, OWNER, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("""
          {"jsonrpc":"2.0","id":2,"method":"programSubscribe","params":["TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",\
          {"commitment":"confirmed","encoding":"base64","filters":["tokenAccountState",\
          {"memcmp":{"offset":0,"bytes":"DbXcVKGeuhnmmi3BhkztB6zs2mEsNkwjnjH1tudQkws"}},\
          {"memcmp":{"offset":32,"bytes":"H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA"}}]}]}""", """
          {"jsonrpc":"2.0","id":3,"method":"programSubscribe","params":["TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",\
          {"commitment":"finalized","encoding":"base64","filters":["tokenAccountState",\
          {"memcmp":{"offset":0,"bytes":"DbXcVKGeuhnmmi3BhkztB6zs2mEsNkwjnjH1tudQkws"}},\
          {"memcmp":{"offset":32,"bytes":"H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA"}}]}]}""", """
          {"jsonrpc":"2.0","id":4,"method":"programSubscribe","params":["TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",\
          {"commitment":"confirmed","encoding":"base64","filters":[{"dataSize":165},\
          {"memcmp":{"offset":0,"bytes":"DbXcVKGeuhnmmi3BhkztB6zs2mEsNkwjnjH1tudQkws"}},\
          {"memcmp":{"offset":32,"bytes":"H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA"}}]}]}"""
      ), socket.sentText);
    }
  }

  @Test
  void subscribeToTokenAccountsUnderTheNamedProgramUsesTheStateFilter() {
    try (final var ws = websocket()) {
      assertTrue(ws.subscribeToTokenAccounts(Commitment.FINALIZED, TOKEN_2022_PROGRAM, OWNER, _ -> {
      }));
      // a different commitment is its own registration under the same program
      assertTrue(ws.subscribeToTokenAccounts(TOKEN_2022_PROGRAM, OWNER, _ -> {
      }));
      assertFalse(ws.subscribeToTokenAccounts(TOKEN_2022_PROGRAM, OWNER, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("""
          {"jsonrpc":"2.0","id":2,"method":"programSubscribe","params":["TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",\
          {"commitment":"finalized","encoding":"base64","filters":["tokenAccountState",\
          {"memcmp":{"offset":32,"bytes":"H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA"}}]}]}""", """
          {"jsonrpc":"2.0","id":3,"method":"programSubscribe","params":["TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",\
          {"commitment":"confirmed","encoding":"base64","filters":["tokenAccountState",\
          {"memcmp":{"offset":32,"bytes":"H8jbMKuUf6A5HVNFL13Bf3FLVcW7HBxmzmy2sHanTfGA"}}]}]}"""
      ), socket.sentText);
    }
  }
}
