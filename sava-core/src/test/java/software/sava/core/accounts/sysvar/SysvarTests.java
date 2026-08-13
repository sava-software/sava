package software.sava.core.accounts.sysvar;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.Base64;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Fixtures are main-net sysvar accounts captured at slot 432872595 via getMultipleAccounts.
final class SysvarTests {

  @Test
  void clock() {
    final byte[] data = Base64.getDecoder().decode("kxzNGQAAAABfPFZqAAAAAOoDAAAAAAAA6wMAAAAAAAAzSlZqAAAAAA==");
    assertEquals(Clock.BYTES, data.length);

    final var clock = Clock.read(data);
    assertEquals(432872595L, clock.slot());
    assertEquals(1784036447L, clock.epochStartTimestamp());
    assertEquals(1002L, clock.epoch());
    assertEquals(1003L, clock.leaderScheduleEpoch());
    assertEquals(1784039987L, clock.unixTimestamp());

    final byte[] written = new byte[Clock.BYTES];
    assertEquals(Clock.BYTES, clock.write(written, 0));
    assertArrayEquals(data, written);
  }

  @Test
  void epochRewards() {
    final byte[] data = Base64.getDecoder().decode("""
        6FJ+GAAAAAArAQAAAAAAAOpnvqJOxkGk1k3flJB8QnqTBZFakobZO4Y/nYulJqbVVQwykjvxe282cwIAAAAAACmaSKSIdQAAUgoxpIh1AAAA""");
    assertEquals(EpochRewards.BYTES, data.length);

    final var epochRewards = EpochRewards.read(data);
    assertEquals(410931944L, epochRewards.distributionStartingBlockHeight());
    assertEquals(299L, epochRewards.numPartitions());
    assertArrayEquals(
        Base64.getDecoder().decode("6me+ok7GQaTWTd+UkHxCepMFkVqShtk7hj+di6UmptU="),
        epochRewards.parentBlockHash()
    );
    assertEquals(new BigInteger("2961927942218846368304213"), epochRewards.totalPoints());
    assertEquals(129229732223529L, epochRewards.totalRewards());
    assertEquals(129229730679378L, epochRewards.distributedRewards());
    assertFalse(epochRewards.active());
  }

  @Test
  void epochRewardsActiveUsesFinalWireByteAndRoundTripsAtNonZeroOffset() {
    final int offset = 5;
    final int activeOffset = offset + EpochRewards.BYTES - 1;
    final int distributedRewardsOffset = activeOffset - Long.BYTES;
    final byte[] data = new byte[offset + EpochRewards.BYTES];

    data[distributedRewardsOffset + 1] = 1;
    assertFalse(EpochRewards.read(data, offset).active(),
        "distributedRewards bytes must not determine active");

    data[distributedRewardsOffset + 1] = 0;
    data[activeOffset] = 1;
    assertTrue(EpochRewards.read(data, offset).active(),
        "the final wire byte determines active");

    final byte[] parentBlockHash = Base64.getDecoder().decode(
        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    final var expected = new EpochRewards(
        PublicKey.NONE,
        410_931_944L,
        299L,
        parentBlockHash,
        new BigInteger("2961927942218846368304213"),
        129_229_732_223_529L,
        129_229_730_679_378L,
        true
    );
    final byte[] written = new byte[offset + EpochRewards.BYTES];
    assertEquals(EpochRewards.BYTES, expected.write(written, offset));

    final var actual = EpochRewards.read(PublicKey.NONE, written, offset);
    assertEquals(expected.address(), actual.address());
    assertEquals(
        expected.distributionStartingBlockHeight(),
        actual.distributionStartingBlockHeight()
    );
    assertEquals(expected.numPartitions(), actual.numPartitions());
    assertArrayEquals(expected.parentBlockHash(), actual.parentBlockHash());
    assertEquals(expected.totalPoints(), actual.totalPoints());
    assertEquals(expected.totalRewards(), actual.totalRewards());
    assertEquals(expected.distributedRewards(), actual.distributedRewards());
    assertEquals(expected.active(), actual.active());

    final var inactive = new EpochRewards(
        expected.address(),
        expected.distributionStartingBlockHeight(),
        expected.numPartitions(),
        expected.parentBlockHash(),
        expected.totalPoints(),
        expected.totalRewards(),
        expected.distributedRewards(),
        false
    );
    assertEquals(EpochRewards.BYTES, inactive.write(written, offset));
    assertEquals(0, written[activeOffset], "writing inactive clears the final wire byte");
    assertFalse(EpochRewards.read(PublicKey.NONE, written, offset).active());
  }

  @Test
  void epochRewardsTotalPointsIsTheUnsignedRustU128Domain() {
    final byte[] data = new byte[EpochRewards.BYTES];
    final int totalPointsOffset = Long.BYTES + Long.BYTES + 32;
    java.util.Arrays.fill(data, totalPointsOffset, totalPointsOffset + 16, (byte) 0xFF);

    final var expected = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
    final var rewards = EpochRewards.read(data);
    assertEquals(expected, rewards.totalPoints());

    final byte[] written = new byte[EpochRewards.BYTES];
    assertEquals(EpochRewards.BYTES, rewards.write(written, 0));
    assertArrayEquals(data, written);

    final var negative = new EpochRewards(
        PublicKey.NONE,
        0,
        0,
        new byte[32],
        BigInteger.ONE.negate(),
        0,
        0,
        false
    );
    assertThrows(IllegalArgumentException.class, () -> negative.write(new byte[EpochRewards.BYTES], 0));
  }

  @Test
  void rent() {
    final byte[] data = Base64.getDecoder().decode("MBsAAAAAAAAAAAAAAADwPzI=");
    assertEquals(Rent.BYTES, data.length);

    final var rent = Rent.read(data);
    assertEquals(6960L, rent.lamportsPerByteYear());
    assertEquals(1.0, rent.exemptionThreshold());
    assertEquals(50, rent.burnPercent());

    assertEquals(890_880L, rent.minimumBalance(0));
    assertEquals(2_039_280L, rent.minimumBalance(165));

    final byte[] written = new byte[Rent.BYTES];
    assertEquals(Rent.BYTES, rent.write(written, 0));
    assertArrayEquals(data, written);
  }

  @Test
  void rentMatchesCurrentSolanaIntegerPathsAndBounds() {
    final var simd0194 = new Rent(null, 6_960L, 1.0, 50);
    final var current = new Rent(null, 6_960L, 2.0, 50);
    assertEquals((128L + Rent.MAX_PERMITTED_DATA_LENGTH) * 6_960L,
        simd0194.minimumBalance(Rent.MAX_PERMITTED_DATA_LENGTH));
    assertEquals(2 * (128L + Rent.MAX_PERMITTED_DATA_LENGTH) * 6_960L,
        current.minimumBalance(Rent.MAX_PERMITTED_DATA_LENGTH));

    assertThrows(IllegalArgumentException.class, () -> current.minimumBalance(-1));
    assertThrows(IllegalArgumentException.class,
        () -> current.minimumBalance(Rent.MAX_PERMITTED_DATA_LENGTH + 1));
    assertThrows(IllegalArgumentException.class,
        () -> new Rent(null, 1_759_197_129_868L, 1.0, 50).minimumBalance(0));
    assertThrows(IllegalArgumentException.class,
        () -> new Rent(null, 879_598_564_934L, 2.0, 50).minimumBalance(0));
  }

  @Test
  void epochSchedule() {
    final byte[] data = Base64.getDecoder().decode("gJcGAAAAAACAlwYAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    assertEquals(EpochSchedule.BYTES, data.length);

    final var epochSchedule = EpochSchedule.read(data);
    assertEquals(432_000L, epochSchedule.slotsPerEpoch());
    assertEquals(432_000L, epochSchedule.leaderScheduleSlotOffset());
    assertFalse(epochSchedule.warmup());
    assertEquals(0L, epochSchedule.firstNormalEpoch());
    assertEquals(0L, epochSchedule.firstNormalSlot());

    final byte[] written = new byte[EpochSchedule.BYTES];
    assertEquals(EpochSchedule.BYTES, epochSchedule.write(written, 0));
    assertArrayEquals(data, written);
  }

  @Test
  void lastRestartSlot() {
    final byte[] data = Base64.getDecoder().decode("KL6wDgAAAAA=");
    assertEquals(LastRestartSlot.BYTES, data.length);
    assertEquals(246_464_040L, LastRestartSlot.read(data).lastRestartSlot());
  }

  @Test
  void stakeHistory() {
    final byte[] data = readFixture("stakeHistory.b64");
    final var stakeHistory = StakeHistory.read(data);

    final var entries = stakeHistory.entries();
    assertEquals(StakeHistory.MAX_ENTRIES, entries.length);
    assertEquals(new StakeHistoryEntry(1001L, 429023881115486895L, 2778198923647242L, 5915395642511380L), entries[0]);
    assertEquals(new StakeHistoryEntry(1000L, 428061830089917206L, 2735681549682863L, 1867022092164017L), entries[1]);
    assertEquals(1001L - (entries.length - 1), entries[entries.length - 1].epoch());

    assertEquals(data.length, stakeHistory.l());
    final byte[] written = new byte[data.length];
    assertEquals(data.length, stakeHistory.write(written, 0));
    assertArrayEquals(data, written);
  }

  @Test
  void slotHashes() {
    final byte[] data = readFixture("slotHashes.b64");
    final var slotHashes = SlotHashes.read(data);

    final var entries = slotHashes.slotHashes();
    assertEquals(SlotHashes.MAX_ENTRIES, entries.length);
    assertEquals(432872594L, entries[0].slot());
    assertArrayEquals(
        Base64.getDecoder().decode("h1H3s6jQfgWlHAZhMK0/VxdgOoYhjy0HE73TqqFOwL0="),
        entries[0].hash()
    );

    assertEquals(data.length, slotHashes.l());
    final byte[] written = new byte[data.length];
    assertEquals(data.length, slotHashes.write(written, 0));
    assertArrayEquals(data, written);
  }

  /// The u64 entry count is untrusted account data; a count the remaining bytes cannot
  /// hold must throw before it sizes the entry array — not surface later as a huge
  /// allocation, a NegativeArraySizeException from the int cast, or an
  /// ArrayIndexOutOfBoundsException mid-walk.
  @Test
  void slotHashesRejectsCorruptCount() {
    final byte[] oneEntry = new byte[Long.BYTES + SlotHash.BYTES];

    // count claims one entry more than the bytes present
    ByteUtil.putInt64LE(oneEntry, 0, 2L);
    assertThrows(IllegalArgumentException.class, () -> SlotHashes.read(oneEntry));

    // top bit set: unsigned it is enormous, signed it is negative — both invalid
    ByteUtil.putInt64LE(oneEntry, 0, Long.MIN_VALUE);
    assertThrows(IllegalArgumentException.class, () -> SlotHashes.read(oneEntry));

    // the count the data can hold still parses
    ByteUtil.putInt64LE(oneEntry, 0, 1L);
    assertEquals(1, SlotHashes.read(oneEntry).slotHashes().length);

    // a trailing partial entry does not count toward the bound
    final byte[] truncated = new byte[Long.BYTES + SlotHash.BYTES - 1];
    ByteUtil.putInt64LE(truncated, 0, 1L);
    assertThrows(IllegalArgumentException.class, () -> SlotHashes.read(truncated));
  }

  @Test
  void stakeHistoryRejectsCorruptCount() {
    final byte[] oneEntry = new byte[Long.BYTES + StakeHistoryEntry.BYTES];

    ByteUtil.putInt64LE(oneEntry, 0, 2L);
    assertThrows(IllegalArgumentException.class, () -> StakeHistory.read(oneEntry));

    ByteUtil.putInt64LE(oneEntry, 0, Long.MIN_VALUE);
    assertThrows(IllegalArgumentException.class, () -> StakeHistory.read(oneEntry));

    ByteUtil.putInt64LE(oneEntry, 0, 1L);
    assertEquals(1, StakeHistory.read(oneEntry).entries().length);

    final byte[] truncated = new byte[Long.BYTES + StakeHistoryEntry.BYTES - 1];
    ByteUtil.putInt64LE(truncated, 0, 1L);
    assertThrows(IllegalArgumentException.class, () -> StakeHistory.read(truncated));
  }

  private static byte[] readFixture(final String fileName) {
    try (final var in = SysvarTests.class.getResourceAsStream("/sysvars/" + fileName)) {
      return Base64.getDecoder().decode(new String(Objects.requireNonNull(in).readAllBytes()).strip());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
