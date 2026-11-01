package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.DataSlice;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.RpcEncoding;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.client.ParseRpcResponseTests.readFileString;

/// `getProgramAccounts` with agave's `sortResults` option, which defaults to sorting by public key
/// when omitted. The round trip replays `getProgramAccounts-nativeLoader-unsorted-devnet.json`, the
/// verbatim answer of `https://api.devnet.solana.com` on 2026-11-01 to exactly this body (the
/// pool's `getVersion` said 4.4.0-beta.1, the answering node's context 4.3.0-beta.2): the fifteen
/// accounts the native loader owns, small enough to commit whole.
final class ProgramAccountsSortRpcRequestTests extends RpcRequestTests {

  private static final PublicKey NATIVE_LOADER = PublicKey.fromBase58Encoded("NativeLoader1111111111111111111111111111111");

  private static String json(final ProgramAccountsRequest<?> request) {
    return request.toJson(7, Commitment.CONFIRMED);
  }

  @Test
  void sortResultsIsOmittedUntilSetAndRenderedLast() {
    final var builder = ProgramAccountsRequest.build().programId(NATIVE_LOADER)
        .dataSlice(new DataSlice(0, 4))
        .filters(List.of(Filter.createDataSizeFilter(14)));
    assertEquals("""
            {"jsonrpc":"2.0","id":7,"method":"getProgramAccounts","params":["NativeLoader1111111111111111111111111111111",{"withContext":true,"encoding":"base64","commitment":"confirmed","dataSlice":{"offset":0,"length":4},"filters":[{"dataSize":14}]}]}""",
        json(builder.createRequest()));
    assertNull(builder.createRequest().sortResults());

    assertEquals("""
            {"jsonrpc":"2.0","id":7,"method":"getProgramAccounts","params":["NativeLoader1111111111111111111111111111111",{"withContext":true,"encoding":"base64","commitment":"confirmed","dataSlice":{"offset":0,"length":4},"filters":[{"dataSize":14}],"sortResults":false}]}""",
        json(builder.sortResults(false).createRequest()));
    assertEquals(Boolean.FALSE, builder.createRequest().sortResults());

    assertEquals("""
            {"jsonrpc":"2.0","id":7,"method":"getProgramAccounts","params":["NativeLoader1111111111111111111111111111111",{"withContext":true,"encoding":"base64","commitment":"confirmed","dataSlice":{"offset":0,"length":4},"filters":[{"dataSize":14}],"sortResults":true}]}""",
        json(builder.sortResults(true).createRequest()));
    assertEquals(Boolean.TRUE, builder.createRequest().sortResults());
  }

  @Test
  void anExternalRequestLeavesSortingToTheNodeByDefault() {
    final ProgramAccountsRequest<byte[]> request = new ProgramAccountsRequest<>() {
      @Override
      public String toJson(final long requestId, final Commitment commitment) {
        return "";
      }

      @Override
      public java.time.Duration requestTimeout() {
        return null;
      }

      @Override
      public PublicKey programId() {
        return NATIVE_LOADER;
      }

      @Override
      public Commitment commitment() {
        return null;
      }

      @Override
      public java.math.BigInteger minContextSlot() {
        return null;
      }

      @Override
      public java.util.Collection<Filter> filters() {
        return null;
      }

      @Override
      @SuppressWarnings("removal")
      public int dataSliceLength() {
        return 0;
      }

      @Override
      @SuppressWarnings("removal")
      public int dataSliceOffset() {
        return 0;
      }

      @Override
      public RpcEncoding encoding() {
        return RpcEncoding.base64;
      }

      @Override
      public java.util.function.BiFunction<PublicKey, byte[], byte[]> factory() {
        return null;
      }
    };
    assertNull(request.sortResults());
  }

  @Test
  void unsortedNativeLoaderAccountsRoundTrip() {
    registerRequest("""
            {"jsonrpc":"2.0","id":1,"method":"getProgramAccounts","params":["NativeLoader1111111111111111111111111111111",{"withContext":true,"encoding":"base64","commitment":"confirmed","sortResults":false}]}""",
        readFileString("getProgramAccounts-nativeLoader-unsorted-devnet.json"));
    final var accounts = rpcClient.getProgramAccounts(
        ProgramAccountsRequest.build().programId(NATIVE_LOADER).sortResults(false).createRequest()
    ).join();
    assertEquals(15, accounts.size());

    final var system = accounts.getFirst();
    assertEquals(PublicKey.fromBase58Encoded("11111111111111111111111111111111"), system.pubKey());
    assertEquals("system_program", new String(system.data(), UTF_8));
    assertEquals(14, system.space());
    assertTrue(system.executable());
    assertEquals(1L, system.lamports());
    assertEquals(517_570_657L, system.context().slot());

    // the node answered a 4.3.0-beta.2 context behind a 4.4.0-beta.1 pool
    assertEquals("4.3.0-beta.2", system.context().apiVersion());

    final var secp256r1 = accounts.get(10);
    assertEquals(PublicKey.fromBase58Encoded("Secp256r1SigVerify1111111111111111111111111"), secp256r1.pubKey());
    assertEquals(0, secp256r1.data().length);
    assertEquals(java.math.BigInteger.ZERO, secp256r1.rentEpoch());

    // the one non-program account the loader owns comes last, after every program it loads
    final var stray = accounts.getLast();
    assertEquals(PublicKey.fromBase58Encoded("CKTJ2tDvDMs3XF6GwCqMQB879E2svMnK2sXPZiMev35X"), stray.pubKey());
    assertFalse(stray.executable());
    assertEquals(890_880L, stray.lamports());
  }
}
