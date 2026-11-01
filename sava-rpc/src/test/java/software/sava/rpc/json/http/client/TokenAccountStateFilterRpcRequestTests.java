package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.accounts.token.Token2022Account;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.accounts.token.extensions.AccountType;
import software.sava.core.accounts.token.extensions.ImmutableOwner;
import software.sava.core.accounts.token.extensions.TransferHookAccount;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.response.AccountInfo;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.client.ParseRpcResponseTests.readFileString;

/// Agave's `tokenAccountState` filter through the public [ProgramAccountsRequest.Builder]: the
/// body sava sends, and what a public node returned for exactly that body.
///
/// Both responses were captured on 2026-11-01, between 17:30 and 17:32 UTC, from
/// `https://api.devnet.solana.com` (node `ams261`), whose `getVersion` reported `solana-core`
/// 4.4.0-beta.1, feature set 3993424257, by POSTing the body each test registers. They are
/// verbatim, trailing newline included. The mints were found through the token balances of a
/// recent devnet block and its payers' token accounts; a devnet reset would leave them
/// unreproducible, which an offline replay does not need.
///
/// The oracle for every returned account is the node's own predicate, spl-generic-token's
/// `token_2022::Account::valid_account_data` (2.0.1, the version Agave locks), restated from its
/// constants rather than from sava's parsers.
final class TokenAccountStateFilterRpcRequestTests extends RpcRequestTests {

  private static final PublicKey TOKEN_PROGRAM =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey TOKEN_2022_PROGRAM =
      PublicKey.fromBase58Encoded("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");

  private static final int SPL_TOKEN_ACCOUNT_LENGTH = 165;
  private static final int SPL_TOKEN_MULTISIG_LENGTH = 355;
  private static final int SPL_TOKEN_ACCOUNT_STATE_OFFSET = 108;
  private static final byte ACCOUNTTYPE_ACCOUNT = 2;

  /// The SPL Token capture, small enough to keep inline.
  private static final String SPL_TOKEN_RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"apiVersion":"4.4.0-beta.1","slot":517570303},"value":[{"account":{"data":["mV/FW0s1ihoi1Eanw+/S7J/v3H+B1qyJrRBMqYTe1XEWR50eCqYyKGW72SzdoiPqLPQaXun1EAHCQlTAJOpAKAAAwW/yhiMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","base64"],"executable":false,"lamports":2039280,"owner":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","rentEpoch":18446744073709551615,"space":165},"pubkey":"Df6xWpuNhJqFbaPVorunVq6cminNEWReSrcgctCzjwLR"}]},"id":1}
      """;

  /// Exactly 165 bytes, or longer than that, not the length of a multisig, with the Token-2022
  /// account-type byte `Account` at offset 165; and in both cases a state byte other than
  /// `Uninitialized`. The mint is this query's own memcmp.
  private static void assertMatchesTheNodePredicate(final PublicKey mint, final byte[] data) {
    if (data.length != SPL_TOKEN_ACCOUNT_LENGTH) {
      assertTrue(data.length > SPL_TOKEN_ACCOUNT_LENGTH && data.length != SPL_TOKEN_MULTISIG_LENGTH,
          "length " + data.length);
      assertEquals(ACCOUNTTYPE_ACCOUNT, data[SPL_TOKEN_ACCOUNT_LENGTH]);
    }
    assertNotEquals(0, data[SPL_TOKEN_ACCOUNT_STATE_OFFSET], "an uninitialized account must not match");
    assertEquals(mint, PublicKey.readPubKey(data, 0));
  }

  private static AccountInfo<byte[]> find(final List<AccountInfo<byte[]>> accounts, final String address) {
    final var key = PublicKey.fromBase58Encoded(address);
    return accounts.stream().filter(account -> account.pubKey().equals(key)).findFirst().orElseThrow();
  }

  /// A mint with one holder on the SPL Token program, whose token accounts are all 165 bytes.
  @Test
  void splTokenHolderOfAMint() {
    registerRequest("""
            {"jsonrpc":"2.0","id":1,"method":"getProgramAccounts","params":["TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",\
            {"withContext":true,"encoding":"base64","commitment":"confirmed",\
            "filters":[{"memcmp":{"offset":0,"bytes":"BKi5XZBUL49pwNDhNgeR9gJcrxfuRH48F2nCzDq4rCsz"}},"tokenAccountState"]}]}""",
        SPL_TOKEN_RESPONSE
    );
    final var mint = PublicKey.fromBase58Encoded("BKi5XZBUL49pwNDhNgeR9gJcrxfuRH48F2nCzDq4rCsz");
    final var request = ProgramAccountsRequest.build()
        .programId(TOKEN_PROGRAM)
        .filters(List.of(TokenAccount.createMintFilter(mint), Filter.tokenAccountState()))
        .createRequest();
    final var accounts = rpcClient.getProgramAccounts(request).join();

    assertEquals(1, accounts.size());
    final var account = accounts.getFirst();
    assertMatchesTheNodePredicate(mint, account.data());
    assertEquals(SPL_TOKEN_ACCOUNT_LENGTH, account.data().length);
    assertEquals(SPL_TOKEN_ACCOUNT_LENGTH, account.space());
    assertEquals(PublicKey.fromBase58Encoded("Df6xWpuNhJqFbaPVorunVq6cminNEWReSrcgctCzjwLR"), account.pubKey());
    assertEquals(517_570_303L, account.context().slot());
    assertEquals("4.4.0-beta.1", account.context().apiVersion());
    assertEquals(TOKEN_PROGRAM, account.owner());
    assertEquals(2_039_280L, account.lamports());
    assertFalse(account.executable());
    assertEquals(new BigInteger("18446744073709551615"), account.rentEpoch());

    final var tokenAccount = TokenAccount.read(account.pubKey(), account.data());
    assertEquals(account.pubKey(), tokenAccount.address());
    assertEquals(mint, tokenAccount.mint());
    assertEquals(PublicKey.fromBase58Encoded("2VyJghegXGD7rPxS5HzemSWWjv64N8v8bThJoMRmTeEs"), tokenAccount.owner());
    assertEquals(10_000_000_000_000_000L, tokenAccount.amount());
    assertEquals(AccountState.Initialized, tokenAccount.state());
    assertEquals(0, tokenAccount.delegateOption());
    assertNull(tokenAccount.delegate());
    assertEquals(0, tokenAccount.isNativeOption());
    assertEquals(0, tokenAccount.delegatedAmount());
    assertEquals(0, tokenAccount.closeAuthorityOption());
    assertNull(tokenAccount.closeAuthority());
  }

  /// A Token-2022 mint whose holders all carry extensions: a transfer-hook account entry on each,
  /// and ImmutableOwner on all but one. None is 165 bytes long, and the same node, asked about a
  /// minute after the capture with a 165-byte `dataSize` filter in place of `tokenAccountState`
  /// (and an empty `dataSlice`), returned no accounts at all.
  @Test
  void token2022HoldersWithExtensions() {
    registerRequest("""
            {"jsonrpc":"2.0","id":1,"method":"getProgramAccounts","params":["TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",\
            {"withContext":true,"encoding":"base64","commitment":"confirmed",\
            "filters":[{"memcmp":{"offset":0,"bytes":"DbXcVKGeuhnmmi3BhkztB6zs2mEsNkwjnjH1tudQkws"}},"tokenAccountState"]}]}""",
        readFileString("getProgramAccounts-tokenAccountState-token2022-devnet-4.4.0-beta.1.json")
    );
    final var mint = PublicKey.fromBase58Encoded("DbXcVKGeuhnmmi3BhkztB6zs2mEsNkwjnjH1tudQkws");
    final var request = ProgramAccountsRequest.build()
        .programId(TOKEN_2022_PROGRAM)
        .filters(List.of(TokenAccount.createMintFilter(mint), Filter.tokenAccountState()))
        .createRequest();
    final var accounts = rpcClient.getProgramAccounts(request).join();

    assertEquals(14, accounts.size());
    for (final var account : accounts) {
      assertMatchesTheNodePredicate(mint, account.data());
      assertEquals(account.data().length, account.space());
      assertEquals(TOKEN_2022_PROGRAM, account.owner());
      assertEquals(517_570_175L, account.context().slot());
      assertEquals("4.4.0-beta.1", account.context().apiVersion());
    }
    assertEquals(Set.of(171, 175), accounts.stream().map(account -> account.data().length).collect(toSet()),
        "no holder is 165 bytes, so a 165-byte dataSize filter matches none of them");

    // 171 bytes: the base state, the account type and one transfer-hook entry.
    final var hookOnly = find(accounts, "3fJzkU7GpaTY1WSKGZP2VGMW58L6XgY2bbyXfErykq1g");
    assertEquals(171, hookOnly.data().length);
    final var hookOnlyAccount = Token2022Account.read(hookOnly.pubKey(), hookOnly.data());
    assertEquals(AccountType.Account, hookOnlyAccount.type());
    assertEquals(Set.of(new TransferHookAccount(false)), hookOnlyAccount.tokenExtensions());
    final var base = TokenAccount.read(hookOnly.pubKey(), hookOnly.data());
    assertEquals(base, hookOnlyAccount.tokenAccount());
    assertEquals(mint, base.mint());
    assertEquals(PublicKey.fromBase58Encoded("GmeXDkA6tgB8P1RGNVktQkEAkmAG1a7SZJC3qKHTEkyY"), base.owner());
    assertEquals(5_600_499_901_000_000L, base.amount());
    assertEquals(AccountState.Initialized, base.state());

    // 175 bytes: ImmutableOwner as well.
    final var withImmutableOwner = find(accounts, "6rmaDV8B4Cr8oAwwkpu1Xq4SHUYrTUifDugH17qYYTRM");
    assertEquals(175, withImmutableOwner.data().length);
    final var withImmutableOwnerAccount = Token2022Account.read(withImmutableOwner.pubKey(), withImmutableOwner.data());
    assertEquals(AccountType.Account, withImmutableOwnerAccount.type());
    assertEquals(Set.of(ImmutableOwner.INSTANCE, new TransferHookAccount(false)), withImmutableOwnerAccount.tokenExtensions());
    assertEquals(mint, withImmutableOwnerAccount.tokenAccount().mint());
    assertEquals(PublicKey.fromBase58Encoded("4pGZVe8HrJib7EcaQDeSsCXsEaoph8ShqVxm37GRhoGS"), withImmutableOwnerAccount.tokenAccount().owner());
    assertEquals(20_999_500_000_000_000L, withImmutableOwnerAccount.tokenAccount().amount());
    assertEquals(AccountState.Initialized, withImmutableOwnerAccount.tokenAccount().state());
  }
}
