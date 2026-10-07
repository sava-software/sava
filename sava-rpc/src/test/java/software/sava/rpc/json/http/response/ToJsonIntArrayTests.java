package software.sava.rpc.json.http.response;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.*;

/// `JsonUtil.toJsonIntArray` presizes its builder through `jsonIntArrayCapacity`: four
/// chars per byte (`255,`) plus the brackets, clamped at the VM's array limit. The
/// rendering's oracle is an independent one, a `StringJoiner` over `Byte.toUnsignedInt`,
/// which presizes nothing; the hint's oracle is its arithmetic at the boundary where the
/// clamp takes over.
final class ToJsonIntArrayTests {

  private static String reference(final byte[] data) {
    final var joiner = new StringJoiner(",", "[", "]");
    for (final byte b : data) {
      joiner.add(Integer.toString(Byte.toUnsignedInt(b)));
    }
    return joiner.toString();
  }

  /// Below the int wrap the hint only sizes the builder, so the output must match the
  /// reference whatever the hint. A byte renders in one to three digits, so `n` bytes take
  /// `n + 1` chars plus their digits: all three-digit values come to `4n + 1`, one under
  /// the hint, and one, two or three two-digit values among them to `4n`, `4n - 1` and
  /// `4n - 2`, where a hint a few chars smaller would have to regrow or would fit exactly.
  /// All zeros sit far under it, and a mixed and a seeded random fill in between. Every
  /// length up to 512 under each pattern, plus one large array that regrows a small hint
  /// many times.
  @Test
  void outputMatchesAnIndependentRenderingAcrossCapacityShapes() {
    final var random = new Random(0x5A7A_2026_1007L);
    for (int length = 1; length <= 512; ++length) {
      for (int pattern = 0; pattern < 7; ++pattern) {
        final byte[] data = new byte[length];
        for (int i = 0; i < length; ++i) {
          data[i] = switch (pattern) {
            case 0 -> 0;
            case 1 -> (byte) 255;
            case 2 -> (byte) (i < 1 ? 99 : 200);
            case 3 -> (byte) (i < 2 ? 99 : 200);
            case 4 -> (byte) (i < 3 ? 99 : 200);
            case 5 -> (byte) (i % 3 == 0 ? 9 : i % 3 == 1 ? 10 : 100);
            default -> (byte) random.nextInt(256);
          };
        }
        final int width = length;
        final int shape = pattern;
        assertEquals(reference(data), JsonUtil.toJsonIntArray(data),
            () -> width + " bytes, pattern " + shape);
      }
    }
    final byte[] large = new byte[1 << 18];
    random.nextBytes(large);
    assertEquals(reference(large), JsonUtil.toJsonIntArray(large));
  }

  /// The hint is four chars per element plus the brackets, computed in `long` and clamped
  /// at the VM's array limit, so the top of the domain neither overflows nor wraps; the
  /// builder grows past a clamped hint if the rendering needs it. Before the clamp (measured
  /// 2026-10-07 on JDK 25.0.2 with a 6 GiB heap), 2^29 - 1 zero bytes asked for a 2^31 - 2
  /// char builder and threw `OutOfMemoryError: Requested array size exceeds VM limit`, and
  /// 2^29 wrapped to `NegativeArraySizeException: -2147483646`, though both render to a
  /// representable `String` of 2^30 - 1 and 2^30 + 1 chars. The last unclamped length and
  /// the first clamped one sit two elements apart.
  @Test
  void theCapacityHintIsFourPerElementPlusBracketsAndClampsAtTheVmLimit() {
    assertEquals(6, JsonUtil.jsonIntArrayCapacity(1));
    assertEquals(4 * 512 + 2, JsonUtil.jsonIntArrayCapacity(512));
    assertEquals(2_147_483_638, JsonUtil.jsonIntArrayCapacity(536_870_909), "last unclamped");
    assertEquals(Integer.MAX_VALUE - 8, JsonUtil.jsonIntArrayCapacity(536_870_910), "first clamped");
    assertEquals(Integer.MAX_VALUE - 8, JsonUtil.jsonIntArrayCapacity((1 << 29) - 1), "old VM-limit");
    assertEquals(Integer.MAX_VALUE - 8, JsonUtil.jsonIntArrayCapacity(1 << 29), "the old wrap");
    assertEquals(Integer.MAX_VALUE - 8, JsonUtil.jsonIntArrayCapacity(Integer.MAX_VALUE));
    int previous = 0;
    for (final int length : new int[]{0, 1, 2, 1 << 10, 1 << 20, 1 << 28, (1 << 29) - 1, 1 << 29, Integer.MAX_VALUE}) {
      final int capacity = JsonUtil.jsonIntArrayCapacity(length);
      assertTrue(capacity >= previous, () -> "non-decreasing at " + length);
      previous = capacity;
    }
  }
}
