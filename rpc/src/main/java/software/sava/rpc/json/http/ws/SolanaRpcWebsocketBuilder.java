package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.function.BiConsumer;

public final class SolanaRpcWebsocketBuilder implements SolanaRpcWebsocket.Builder {

  private URI wsUri;
  private WebSocket.Builder webSocketBuilder;
  private long reConnect = 3_000;
  private long writeOrPingDelay = 15_000;
  private long subscriptionAndPingCheckDelay = 200;
  private SolanaAccounts solanaAccounts = SolanaAccounts.MAIN_NET;
  private Commitment commitment = Commitment.CONFIRMED;
  private SolanaRpcWebsocket.OnClose onClose;
  private BiConsumer<SolanaRpcWebsocket, Throwable> onError;

  SolanaRpcWebsocketBuilder() {
  }

  @Override
  public SolanaRpcWebsocket create() {
    return new SolanaJsonRpcWebsocket(
        wsUri, solanaAccounts, commitment,
        webSocketBuilder.connectTimeout(Duration.ofMillis(reConnect)),
        new Timings(reConnect, writeOrPingDelay, subscriptionAndPingCheckDelay),
        onClose,
        onError
    );
  }

  @Override
  public URI wsUri() {
    return wsUri;
  }

  @Override
  public WebSocket.Builder webSocketBuilder() {
    return webSocketBuilder;
  }

  @Override
  public long reConnect() {
    return reConnect;
  }

  @Override
  public long writeOrPingDelay() {
    return writeOrPingDelay;
  }

  @Override
  public long subscriptionAndPingCheckDelay() {
    return subscriptionAndPingCheckDelay;
  }

  @Override
  public SolanaAccounts solanaAccounts() {
    return solanaAccounts;
  }

  @Override
  public Commitment commitment() {
    return commitment;
  }

  @Override
  public SolanaRpcWebsocket.Builder uri(final URI uri) {
    this.wsUri = uri;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.Builder webSocketBuilder(final WebSocket.Builder webSocketBuilder) {
    this.webSocketBuilder = webSocketBuilder;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.Builder reConnect(final long reConnect) {
    this.reConnect = reConnect;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.Builder writeOrPingDelay(final long writeOrPingDelay) {
    this.writeOrPingDelay = writeOrPingDelay;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.Builder subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay) {
    this.subscriptionAndPingCheckDelay = subscriptionAndPingCheckDelay;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.Builder commitment(final Commitment commitment) {
    this.commitment = commitment;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.Builder solanaAccounts(final SolanaAccounts solanaAccounts) {
    this.solanaAccounts = solanaAccounts;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.Builder onClose(final SolanaRpcWebsocket.OnClose onClose) {
    this.onClose = onClose;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.Builder onError(final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
    this.onError = onError;
    return this;
  }
}
