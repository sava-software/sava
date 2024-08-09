package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;

public final class SolanaRpcWebsocketBuilder implements SolanaRpcWebsocket.Builder {

  private URI wsUri;
  private WebSocket.Builder webSocketBuilder;
  private long reConnect = 3_000;
  private long writeOrPingDelay = 15_000;
  private long subscriptionAndPingCheckDelay = 200;
  private SolanaAccounts solanaAccounts = SolanaAccounts.MAIN_NET;
  private Commitment commitment = Commitment.CONFIRMED;

  SolanaRpcWebsocketBuilder() {
  }

  @Override
  public SolanaRpcWebsocket create() {
    return new SolanaJsonRpcWebsocket(
        wsUri, solanaAccounts, commitment,
        webSocketBuilder.connectTimeout(Duration.ofMillis(reConnect)),
        new Timings(reConnect, writeOrPingDelay, subscriptionAndPingCheckDelay)
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
  public void uri(final URI uri) {
    this.wsUri = uri;
  }

  @Override
  public void webSocketBuilder(final WebSocket.Builder webSocketBuilder) {
    this.webSocketBuilder = webSocketBuilder;
  }

  @Override
  public void reConnect(final long reConnect) {
    this.reConnect = reConnect;
  }

  @Override
  public void writeOrPingDelay(final long writeOrPingDelay) {
    this.writeOrPingDelay = writeOrPingDelay;
  }

  @Override
  public void subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay) {
    this.subscriptionAndPingCheckDelay = subscriptionAndPingCheckDelay;
  }

  @Override
  public void commitment(final Commitment commitment) {
    this.commitment = commitment;
  }

  @Override
  public void solanaAccounts(final SolanaAccounts solanaAccounts) {
    this.solanaAccounts = solanaAccounts;
  }
}
