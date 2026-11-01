package software.sava.core.rpc;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FilterTests {

  @Test
  void dataSizeFilterJson() {
    assertEquals("""
        {"dataSize":165}""", Filter.createDataSizeFilter(165).toJson());
  }

  @Test
  void memCmpPublicKeyFilterJson() {
    final var key = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
    assertEquals("""
        {"memcmp":{"offset":32,"bytes":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"}}""", Filter.createMemCompFilter(32, key).toJson());
  }

  @Test
  void memCmpBytesFilterJson() {
    final byte[] discriminator = {1};
    assertEquals("""
        {"memcmp":{"offset":0,"bytes":"2"}}""", Filter.createMemCompFilter(0, discriminator).toJson());
  }

  @Test
  void memCmpTwoPublicKeysFilterJson() {
    final var key = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
    final byte[] data = new byte[PublicKey.PUBLIC_KEY_LENGTH << 1];
    key.write(data, 0);
    key.write(data, PublicKey.PUBLIC_KEY_LENGTH);
    final var expected = Filter.createMemCompFilter(8, data).toJson();
    assertEquals(expected, Filter.createMemCompFilter(8, key, key).toJson());
  }

  @Test
  void memCmpMaxLength() {
    final byte[] max = new byte[Filter.MAX_MEM_COMP_LENGTH];
    Arrays.fill(max, (byte) 1);
    Filter.createMemCompFilter(0, max);

    final byte[] tooLong = new byte[Filter.MAX_MEM_COMP_LENGTH + 1];
    assertThrows(IllegalStateException.class, () -> Filter.createMemCompFilter(0, tooLong));
  }

  /// Agave's `RpcFilterType::TokenAccountState` is a unit variant under
  /// `#[serde(rename_all = "camelCase")]`, which serde writes as a bare string, quotes included,
  /// where the other filters are objects.
  @Test
  void tokenAccountStateFilterJson() {
    assertEquals("\"tokenAccountState\"", Filter.tokenAccountState().toJson());
  }

  @Test
  void tokenAccountStateFilterIsOneSharedValue() {
    final var filter = Filter.tokenAccountState();
    assertSame(filter, Filter.tokenAccountState());
    assertEquals(filter, Filter.tokenAccountState());
    assertEquals(filter, new TokenAccountStateFilter(), "a record without components equals any instance");
    assertEquals(filter.hashCode(), new TokenAccountStateFilter().hashCode());
    assertNotEquals(Filter.createDataSizeFilter(165), filter);
    assertNotEquals(filter, Filter.createDataSizeFilter(165));
  }
}
