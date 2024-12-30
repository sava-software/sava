package software.sava.examples;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.client.SolanaRpcClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

public final class FetchTablesByAuthority {


  public static void main(final String[] args) {
    final var rpcEndpoint = "https://mainnet.helius-rpc.com/?api-key=";
    final var authority = PublicKey.fromBase58Encoded("");
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var rpcClient = SolanaRpcClient.createClient(URI.create(rpcEndpoint), httpClient);

      final var tableAccountInfoList = rpcClient.getProgramAccounts(
          SolanaAccounts.MAIN_NET.addressLookupTableProgram(),
          List.of(
              Filter.createMemCompFilter(AddressLookupTable.AUTHORITY_OFFSET, authority)
          )
      ).join();

      for (final var accountInfo : tableAccountInfoList) {
        final var table = AddressLookupTable.read(accountInfo.pubKey(), accountInfo.data());
        System.out.println(table);
      }
    }
  }
}
