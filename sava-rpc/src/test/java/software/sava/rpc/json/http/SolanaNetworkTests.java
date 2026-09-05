package software.sava.rpc.json.http;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SolanaNetworkTests {

  @Test
  void exposesTheConfiguredHttpAndWebSocketEndpoints() {
    assertEquals(URI.create("https://api.devnet.solana.com"), SolanaNetwork.DEV_NET.getEndpoint());
    assertEquals(URI.create("wss://api.devnet.solana.com"), SolanaNetwork.DEV_NET.getWebSocketEndpoint());
    assertEquals(URI.create("https://api.testnet.solana.com"), SolanaNetwork.TEST_NET.getEndpoint());
    assertEquals(URI.create("wss://api.testnet.solana.com"), SolanaNetwork.TEST_NET.getWebSocketEndpoint());
    assertEquals(URI.create("https://api.mainnet-beta.solana.com"), SolanaNetwork.MAIN_NET.getEndpoint());
    assertEquals(URI.create("wss://api.mainnet-beta.solana.com"), SolanaNetwork.MAIN_NET.getWebSocketEndpoint());
  }
}
