package software.sava.core.accounts.token;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class TokenStateRoundTripTests {

  private static PublicKey key(final int fill) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(bytes, (byte) fill);
    return PublicKey.createPubKey(bytes);
  }

  @Test
  void mintRoundTrip() {
    final var address = key(1);
    final var mint = new Mint(address, key(2), 1_000_000_000L, 9, true, key(3));
    final byte[] data = new byte[Mint.BYTES];
    assertEquals(Mint.BYTES, mint.write(data, 0));
    assertEquals(mint, Mint.read(address, data));
  }

  @Test
  void mintWriteAtOffset() {
    final var address = key(1);
    final var mint = new Mint(address, key(2), 42L, 6, true, key(3));
    final int offset = 17;
    final byte[] data = new byte[offset + Mint.BYTES + 5];
    Arrays.fill(data, (byte) 0xFF);
    assertEquals(Mint.BYTES, mint.write(data, offset));

    for (int i = 0; i < offset; ++i) {
      assertEquals((byte) 0xFF, data[i], "byte before offset overwritten at " + i);
    }
    for (int i = offset + Mint.BYTES; i < data.length; ++i) {
      assertEquals((byte) 0xFF, data[i], "byte after mint overwritten at " + i);
    }

    final byte[] mintData = Arrays.copyOfRange(data, offset, offset + Mint.BYTES);
    assertEquals(mint, Mint.read(address, mintData));
  }

  @Test
  void mintNullAuthorities() {
    final var address = key(1);
    final var mint = new Mint(address, null, 0L, 0, true, null);
    // Dirty buffer: the COption tags must be written explicitly, not assumed zero.
    final byte[] data = new byte[Mint.BYTES];
    Arrays.fill(data, (byte) 0xFF);
    assertEquals(Mint.BYTES, mint.write(data, 0));

    final var parsed = Mint.read(address, data);
    assertNull(parsed.mintAuthority());
    assertNull(parsed.freezeAuthority());
    assertEquals(0L, parsed.supply());
    assertEquals(0, parsed.decimals());
    assertTrue(parsed.initialized());
  }

  @Test
  void tokenAccountRoundTrip() {
    final var address = key(4);
    final var tokenAccount = new TokenAccount(
        address, key(5), key(6), 42L,
        1, key(7),
        AccountState.Initialized,
        1, 2_039_280L, 21L,
        1, key(8)
    );
    final int offset = 11;
    final byte[] data = new byte[offset + TokenAccount.BYTES];
    assertEquals(TokenAccount.BYTES, tokenAccount.write(data, offset));
    assertEquals(tokenAccount, TokenAccount.read(address, data, offset));
  }

  @Test
  void tokenAccountNoDelegateNoCloseAuthority() {
    final var address = key(4);
    final var tokenAccount = new TokenAccount(
        address, key(5), key(6), 42L,
        0, null,
        AccountState.Frozen,
        0, 0L, 0L,
        0, null
    );
    final byte[] data = new byte[TokenAccount.BYTES];
    assertEquals(TokenAccount.BYTES, tokenAccount.write(data, 0));

    final var parsed = TokenAccount.read(address, data, 0);
    assertEquals(0, parsed.delegateOption());
    assertNull(parsed.delegate());
    assertEquals(AccountState.Frozen, parsed.state());
    assertEquals(0, parsed.closeAuthorityOption());
    assertNull(parsed.closeAuthority());
    assertEquals(tokenAccount, parsed);
  }
}
