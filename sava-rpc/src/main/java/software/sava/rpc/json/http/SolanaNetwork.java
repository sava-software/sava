package software.sava.rpc.json.http;

import java.net.URI;

public enum SolanaNetwork {

  DEV_NET("https://api.devnet.solana.com"),
  TEST_NET("https://api.testnet.solana.com"),
  MAIN_NET("https://api.mainnet-beta.solana.com");

  private final URI endpoint;
  private final URI webSocketEndpoint;

  SolanaNetwork(final String endpoint) {
    this.endpoint = URI.create(endpoint);
    this.webSocketEndpoint = URI.create((this.endpoint.getScheme().equals("https") ? "wss" : "ws") + "://" + this.endpoint.getHost());
  }

  public URI getEndpoint() {
    return endpoint;
  }

  public URI getWebSocketEndpoint() {
    return webSocketEndpoint;
  }
}
