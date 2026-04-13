package software.sava.helius.rpc.json;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.helius.rpc.json.request.SortOrder;
import software.sava.helius.rpc.json.request.Status;
import software.sava.helius.rpc.json.request.TokenAccounts;
import software.sava.helius.rpc.json.request.TransactionsForAddressRequest;
import software.sava.rpc.json.http.request.Commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HeliusRpcTests {

  private static final PublicKey VOTE_PROGRAM = PublicKey.fromBase58Encoded("Vote111111111111111111111111111111111111111");

  @Test
  void testMinimalRequest() {
    final var request = TransactionsForAddressRequest.build(VOTE_PROGRAM)
        .createRequest();

    final String json = request.toJson(1, "signatures");
    assertEquals(
        """
            {"jsonrpc":"2.0","id":1,"method":"getTransactionsForAddress","params":["Vote111111111111111111111111111111111111111",{"encoding":"base64","transactionDetails":"signatures"}]}""",
        json
    );
  }

  @Test
  void testRequestWithAllTopLevelFields() {
    final var request = TransactionsForAddressRequest.build(VOTE_PROGRAM)
        .sortOrder(SortOrder.desc)
        .commitment(Commitment.FINALIZED)
        .minContextSlot(1000)
        .limit(50)
        .paginationToken("1053:13")
        .maxSupportedTransactionVersion(0)
        .createRequest();

    final String json = request.toJson(1, "signatures");
    assertEquals(
        """
            {"jsonrpc":"2.0","id":1,"method":"getTransactionsForAddress","params":["Vote111111111111111111111111111111111111111",\
            {"encoding":"base64",\
            "transactionDetails":"signatures",\
            "sortOrder":"desc",\
            "commitment":"finalized",\
            "minContextSlot":1000,\
            "limit":50,\
            "paginationToken":"1053:13",\
            "maxSupportedTransactionVersion":0}]}""",
        json
    );
  }

  @Test
  void testRequestWithFilters() {
    final var request = TransactionsForAddressRequest.build(VOTE_PROGRAM)
        .limit(50)
        .sortOrder(SortOrder.desc)
        .slotFilter(1000L, null, null, 2000L)
        .blockTimeFilter(1640995200L, null, null, 1641081600L, null)
        .signatureFilter(null, "3jweEauJ3PK6SWCZ1PGjBvj8vDdWG3KpwATGy1ARAXFSDwt8GFXM7W5Ncn16wmqokgpiKRLuS83KUxyZyv2sUYv", null, null)
        .status(Status.succeeded)
        .tokenAccounts(TokenAccounts.balanceChanged)
        .createRequest();

    final String json = request.toJson(1, "signatures");
    assertEquals(
        """
            {"jsonrpc":"2.0","id":1,"method":"getTransactionsForAddress","params":["Vote111111111111111111111111111111111111111",\
            {"encoding":"base64",\
            "transactionDetails":"signatures",\
            "sortOrder":"desc",\
            "limit":50,\
            "filters":{\
            "slot":{"gte":1000,"lt":2000},\
            "blockTime":{"gte":1640995200,"lt":1641081600},\
            "signature":{"gt":"3jweEauJ3PK6SWCZ1PGjBvj8vDdWG3KpwATGy1ARAXFSDwt8GFXM7W5Ncn16wmqokgpiKRLuS83KUxyZyv2sUYv"},\
            "status":"succeeded",\
            "tokenAccounts":"balanceChanged"}}]}""",
        json
    );
  }

  @Test
  void testRequestWithOnlyFilters() {
    final var request = TransactionsForAddressRequest.build(VOTE_PROGRAM)
        .status(Status.failed)
        .createRequest();

    final String json = request.toJson(1, "full");
    assertEquals(
        """
            {"jsonrpc":"2.0","id":1,"method":"getTransactionsForAddress","params":["Vote111111111111111111111111111111111111111",\
            {"encoding":"base64","transactionDetails":"full","filters":{"status":"failed"}}]}""",
        json
    );
  }

  @Test
  void testRequestWithBlockTimeEqFilter() {
    final var request = TransactionsForAddressRequest.build(VOTE_PROGRAM)
        .blockTimeFilter(null, null, null, null, 1641038400L)
        .createRequest();

    final String json = request.toJson(1, "signatures");
    assertEquals(
        """
            {"jsonrpc":"2.0","id":1,"method":"getTransactionsForAddress","params":["Vote111111111111111111111111111111111111111",\
            {"encoding":"base64","transactionDetails":"signatures","filters":{"blockTime":{"eq":1641038400}}}]}""",
        json
    );
  }
}
