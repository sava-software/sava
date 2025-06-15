package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class SolanaRpcWebsocketBuilder implements SolanaRpcWebsocket.Builder {

  private URI wsUri;
  private WebSocket.Builder webSocketBuilder;
  private long reConnectDelay = 3_000;
  private long pingDelay = 15_000;
  private long subscriptionAndPingCheckDelay = 2_000;
  private SolanaAccounts solanaAccounts = SolanaAccounts.MAIN_NET;
  private Commitment commitment = Commitment.CONFIRMED;
  private Consumer<SolanaRpcWebsocket> onOpen;
  private SolanaRpcWebsocket.OnClose onClose;
  private BiConsumer<SolanaRpcWebsocket, Throwable> onError;

  SolanaRpcWebsocketBuilder() {
  }

  @Override
  public SolanaRpcWebsocket create() {
    return new SolanaJsonRpcWebsocket(
        wsUri, solanaAccounts, commitment,
        webSocketBuilder.connectTimeout(Duration.ofMillis(reConnectDelay)),
        new Timings(reConnectDelay, pingDelay, subscriptionAndPingCheckDelay),
        onOpen,
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
    return reConnectDelay;
  }

  @Override
  public long writeOrPingDelay() {
    return pingDelay;
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
  public SolanaRpcWebsocket.Builder reConnectDelay(final long reConnectDelay) {
    this.reConnectDelay = reConnectDelay;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.Builder pingDelay(final long pingDelay) {
    this.pingDelay = pingDelay;
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
  public Consumer<SolanaRpcWebsocket> onOpen() {
    return onOpen;
  }

  @Override
  public SolanaRpcWebsocket.Builder onOpen(final Consumer<SolanaRpcWebsocket> onOpen) {
    this.onOpen = onOpen;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.OnClose onClose() {
    return onClose;
  }

  @Override
  public SolanaRpcWebsocket.Builder onClose(final SolanaRpcWebsocket.OnClose onClose) {
    this.onClose = onClose;
    return this;
  }

  @Override
  public BiConsumer<SolanaRpcWebsocket, Throwable> onError() {
    return onError;
  }

  @Override
  public SolanaRpcWebsocket.Builder onError(final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
    this.onError = onError;
    return this;
  }
}
