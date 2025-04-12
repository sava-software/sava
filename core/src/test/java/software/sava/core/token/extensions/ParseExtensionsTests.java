package software.sava.core.token.extensions;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.accounts.token.Token2022;
import software.sava.core.accounts.token.Token2022Account;
import software.sava.core.accounts.token.extensions.*;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.token.extensions.ExtensionType.*;

final class ParseExtensionsTests {

  @Test
  void confidentialTokenAccount() {
    final byte[] data = Base64.getDecoder().decode("""
        RxdtkYpXhCDhXf92mJjxs3oZ3ex+alJvU1LnfB+xzfxhoty+GQ/aYo6Ekj8G1Njo9gjTTa9EWq9e338fGgvWKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgcAAAAFACcBASSV8fcw/nQLyJBayJHB+UCYPPPoNkClfQLx/iFBqvQVAmXmv7UFCY11M7oWWvTE/0mEsdyVHE3tkaMweLfcQFFEB5pvlUiDRJIiIijWYBxatPJAihLSrmvMypcRrCDCCC4mK0ztqaB12QEFnjXJkSvFIHVAsMcR8gIvEke8w3xPjEzQLztO6GgsHghnlrlLSF6lAOvS1NlIbW+Qlh+wlEX6oDJPu4alTqmSccdS4kDNLEOlkK/xR6CvDLcuskcDUfT1b9LH7qUJe2HcQsUi6f6xQ16KSVvXbfltklVYjx5+RWcFUwYZ+XswiBOuPy2ZjKHbPBY1fahZCigCqTg2IcrV7X8xAQEBAAAAAAAAAAAAAQAAAAAAAQAAAAAAAAABAAAAAAAAAA==
        """.stripTrailing());

    final var address = PublicKey.fromBase58Encoded("A9JXuXgm62QG3kTT5waRdEMiGw1w7TY2ovs8MoFWetmZ");
    final var account = Token2022Account.read(address, data);

    assertEquals(AccountType.Account, account.type());

    final var tokenAccount = account.tokenAccount();
    assertEquals(address, tokenAccount.address());
    assertEquals(PublicKey.fromBase58Encoded("5nWfeifw56n8mhpLp4b3BHW2foLdbF9PCo4Y6MJPdyEB"), tokenAccount.mint());
    assertEquals(PublicKey.fromBase58Encoded("7a8aotmeu1J11XCBBmXUorDeTDbVmxfBY23C4GUxvqVy"), tokenAccount.owner());
    assertEquals(0, tokenAccount.amount());
    assertEquals(0, tokenAccount.delegateOption());
    assertNull(tokenAccount.delegate());
    assertEquals(AccountState.Initialized, tokenAccount.state());
    assertEquals(0, tokenAccount.isNativeOption());
    assertEquals(0, tokenAccount.isNative());
    assertEquals(0, tokenAccount.delegatedAmount());
    assertEquals(0, tokenAccount.closeAuthorityOption());
    assertNull(tokenAccount.closeAuthority());

    final var extensions = account.extensions();
    assertEquals(2, extensions.size());

    final var immutableOwner = (ImmutableOwner) extensions.get(ImmutableOwner);
    assertNotNull(immutableOwner);

    final var confidentialTransferAccount = (ConfidentialTransferAccount) extensions.get(ConfidentialTransferAccount);
    assertTrue(confidentialTransferAccount.approved());
    assertEquals(PublicKey.fromBase58Encoded("3TpHnXSnhyqK9re84Nvxg4iYsqqWpiZYdDSK3LvajNMS"), confidentialTransferAccount.elgamalPubkey());

    assertArrayEquals(
        Base64.getDecoder().decode("""
            AmXmv7UFCY11M7oWWvTE/0mEsdyVHE3tkaMweLfcQFFEB5pvlUiDRJIiIijWYBxatPJAihLSrmvMypcRrCDCCA=="""),
        confidentialTransferAccount.pendingBalanceLo()
    );
    assertArrayEquals(
        Base64.getDecoder().decode("""
            LiYrTO2poHXZAQWeNcmRK8UgdUCwxxHyAi8SR7zDfE+MTNAvO07oaCweCGeWuUtIXqUA69LU2Uhtb5CWH7CURQ=="""),
        confidentialTransferAccount.pendingBalanceHi()
    );
    assertArrayEquals(
        Base64.getDecoder().decode("""
            +qAyT7uGpU6pknHHUuJAzSxDpZCv8Uegrwy3LrJHA1H09W/Sx+6lCXth3ELFIun+sUNeiklb1235bZJVWI8efg=="""),
        confidentialTransferAccount.availableBalance()
    );
    assertArrayEquals(
        Base64.getDecoder().decode("""
            RWcFUwYZ+XswiBOuPy2ZjKHbPBY1fahZCigCqTg2IcrV7X8x"""),
        confidentialTransferAccount.decryptableAvailableBalance()
    );

    assertTrue(confidentialTransferAccount.allowConfidentialCredits());
    assertTrue(confidentialTransferAccount.allowNonConfidentialCredits());
    assertEquals(1, confidentialTransferAccount.pendingBalanceCreditCounter());
    assertEquals(65536, confidentialTransferAccount.maximumPendingBalanceCreditCounter());
    assertEquals(1, confidentialTransferAccount.expectedPendingBalanceCreditCounter());
    assertEquals(1, confidentialTransferAccount.actualPendingBalanceCreditCounter());
  }

  @Test
  void payPalExtensionsTest() {
    final byte[] data = Base64.getDecoder().decode("""
        AQAAAN1MSGyQ+LbwB8ME7ySB+AUYa+j9X1Ks0QJct5ufZ/8hLuljgmRzAAAGAQEAAAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQMAIAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKwwAIAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKwEAbAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKxeFMmHvarhTKmfwU4ZarTEpP88HzxIKtbmhVwZUjcArAAAAAAAAAABdAgAAAAAAAAAAAAAAAAAAAABdAgAAAAAAAAAAAAAAAAAAAAAEAEEAF4UyYe9quFMqZ/BThlqtMSk/zwfPEgq1uaFXBlSNwCsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAIEAF4UyYe9quFMqZ/BThlqtMSk/zwfPEgq1uaFXBlSNwCscN+ZDO3ME3YJzeuQNm4vzxJ9bDmxJqNUzKLPlBpAcVwEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADgBAABeFMmHvarhTKmfwU4ZarTEpP88HzxIKtbmhVwZUjcArAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAASAEAAF4UyYe9quFMqZ/BThlqtMSk/zwfPEgq1uaFXBlSNwCsXkkg7bIoqh7dHHYFPlZH5OVyECpzj2fTVun06S4p0nhMArgAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKxeSSDtsiiqHt0cdgU+Vkfk5XIQKnOPZ9NW6fTpLinSeCgAAAFBheVBhbCBVU0QFAAAAUFlVU0RPAAAAaHR0cHM6Ly90b2tlbi1tZXRhZGF0YS5wYXhvcy5jb20vcHl1c2RfbWV0YWRhdGEvcHJvZC9zb2xhbmEvcHl1c2RfbWV0YWRhdGEuanNvbgAAAAA=
        """.stripTrailing());

    final var token2022 = Token2022.read(
        PublicKey.fromBase58Encoded("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo"),
        data
    );

    final var extensions = token2022.extensions();
    assertEquals(8, extensions.size());

    final var mintCloseAuthority = (MintCloseAuthority) extensions.get(MintCloseAuthority);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", mintCloseAuthority.closeAuthority().toBase58());

    final var permanentDelegate = (PermanentDelegate) extensions.get(PermanentDelegate);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", permanentDelegate.delegate().toBase58());

    final var transferFeeConfig = (TransferFeeConfig) extensions.get(TransferFeeConfig);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", transferFeeConfig.transferFeeConfigAuthority().toBase58());
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", transferFeeConfig.withdrawWithheldAuthority().toBase58());
    assertEquals(0, transferFeeConfig.withheldAmount());

    final var newTransferFee = transferFeeConfig.newerTransferFee();
    assertEquals(605, newTransferFee.epoch());
    assertEquals(0, newTransferFee.maximumFee());
    assertEquals(0, newTransferFee.transferFeeBasisPoints());

    final var olderTransferFee = transferFeeConfig.olderTransferFee();
    assertEquals(605, olderTransferFee.epoch());
    assertEquals(0, olderTransferFee.maximumFee());
    assertEquals(0, olderTransferFee.transferFeeBasisPoints());

    final var confidentialTransferMint = (ConfidentialTransferMint) extensions.get(ConfidentialTransferMint);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", confidentialTransferMint.authority().toBase58());
    assertFalse(confidentialTransferMint.autoApproveNewAccounts());
    assertEquals(PublicKey.NONE, confidentialTransferMint.auditorElGamalKey());

    final var confidentialTransferFeeConfig = (ConfidentialTransferFeeConfig) extensions.get(ConfidentialTransferFeeConfig);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", confidentialTransferFeeConfig.authority().toBase58());
    assertEquals("HDfmQztzBN2Cc3rkDZuL88SfWw5sSajVMyiz5QaQHFc=", confidentialTransferFeeConfig.withdrawWithheldAuthorityElgamalPubkey().toBase64());
    assertTrue(confidentialTransferFeeConfig.harvestToMintEnabled());
    assertEquals(
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==",
        Base64.getEncoder().encodeToString(confidentialTransferFeeConfig.withheldAmount())
    );

    final var transferHook = (TransferHook) extensions.get(TransferHook);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", transferHook.authority().toBase58());

    final var metadataPointer = (MetadataPointer) extensions.get(MetadataPointer);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", metadataPointer.authority().toBase58());
    assertEquals("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", metadataPointer.metadataAddress().toBase58());

    final var tokenMetadata = (TokenMetadata) extensions.get(TokenMetadata);
    assertEquals("2apBGMsS6ti9RyF5TwQTDswXBWskiJP2LD4cUEDqYJjk", tokenMetadata.updateAuthority().toBase58());
    assertEquals("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", tokenMetadata.mint().toBase58());
    assertEquals("PayPal USD", tokenMetadata.name());
    assertEquals("PYUSD", tokenMetadata.symbol());
    assertEquals(
        "https://token-metadata.paxos.com/pyusd_metadata/prod/solana/pyusd_metadata.json",
        tokenMetadata.uri()
    );
    final var additionalMetadata = tokenMetadata.additionalMetadata();
    assertTrue(additionalMetadata.isEmpty());
  }
}
