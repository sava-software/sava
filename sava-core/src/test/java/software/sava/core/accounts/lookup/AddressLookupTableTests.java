package software.sava.core.accounts.lookup;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

final class AddressLookupTableTests {

  private void validate7BouqALT(final PublicKey expectedAddress, final AddressLookupTable alt) {
    assertEquals(expectedAddress, alt.address());
    assertEquals(4, alt.discriminator().length);
    assertEquals(Long.parseUnsignedLong("18446744073709551615"), alt.deactivationSlot());
    assertEquals(282549996, alt.lastExtendedSlot());
    assertEquals(60, alt.lastExtendedSlotStartIndex());
    assertNull(alt.authority());
    assertEquals(74, alt.numAccounts());
    assertEquals(0, alt.indexOf(SolanaAccounts.MAIN_NET.tokenProgram()));
    assertEquals(42, alt.indexOf(PublicKey.fromBase58Encoded("GTq8bjCKaPXYgcSV2mj5iqEdAHgvaZbTz8HYcUatYQTg")));
    assertEquals(73, alt.indexOf(PublicKey.fromBase58Encoded("CWUjm2fFPLsRtmPRDQgeFB9uwg5N4A2KFStAYj1oMkk7")));
  }

  @Test
  void testNoAuthorityParsing() {
    final byte[] data = Base64.getDecoder().decode(
        "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"
    );
    final var address = PublicKey.fromBase58Encoded("7BuoqMBFuAk9xkd5KguaXP8w1brsyBNdDP7EuHDra8s7");
    var alt = AddressLookupTable.FACTORY.apply(address, data);
    validate7BouqALT(address, alt);
    assertArrayEquals(data, alt.data());

    alt = AddressLookupTable.readWithoutReverseLookup(address, data);
    validate7BouqALT(address, alt);
    assertArrayEquals(data, alt.data());

    alt = alt.withReverseLookup();
    validate7BouqALT(address, alt);
    assertArrayEquals(data, alt.data());
  }
}
