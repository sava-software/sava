package software.sava.core.accounts.sysvar;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
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

  /// Offsets every write round trip runs at. Zero alone cannot tell `i - offset` from
  /// `i + offset` in the returned length, nor a write that ignores its offset.
  private static final int[] OFFSETS = {0, 7};

  /// Fills every byte a write must not touch, so a dropped or misplaced field write shows
  /// up as a sentinel where the expected wire byte should be.
  private static final byte SENTINEL = 0x5A;

  /// Bytes left after the record in each write buffer, so an over-long write is visible.
  private static final int TAIL = 3;

  /// A little-endian buffer for building the expected wire form from the upstream field
  /// order, independently of `ByteUtil`.
  private static ByteBuffer wire(final int length) {
    return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
  }

  /// `wire` placed at `offset` in a sentinel-filled buffer with a sentinel tail.
  private static byte[] atOffset(final byte[] wire, final int offset) {
    final byte[] buffer = new byte[offset + wire.length + TAIL];
    Arrays.fill(buffer, SENTINEL);
    System.arraycopy(wire, 0, buffer, offset, wire.length);
    return buffer;
  }

  /// Writes `subject` at `offset` into a sentinel-filled buffer and asserts that it returns
  /// the wire length and leaves exactly `wire` at `offset` with every other byte untouched.
  private static void assertWritesAt(final Serializable subject, final byte[] wire, final int offset) {
    final byte[] buffer = new byte[offset + wire.length + TAIL];
    Arrays.fill(buffer, SENTINEL);
    assertEquals(wire.length, subject.write(buffer, offset), () -> "write length at offset " + offset);
    assertArrayEquals(atOffset(wire, offset), buffer, () -> "written bytes at offset " + offset);
  }

  /// `Clock` is five little-endian 64-bit fields in upstream order. Every field carries a
  /// distinct non-zero value so a swapped, dropped or misplaced field is visible; the write
  /// must return the wire length at any offset, `l()` must report the same, and the
  /// address-taking `read` overload must carry the address while decoding the same fields.
  @Test
  void clockWritesItsWireLengthAtAnyOffsetAndEveryReadOverloadAgrees() {
    final var address = PublicKey.fromBase58Encoded("SysvarC1ock11111111111111111111111111111111");
    final var clock = new Clock(null, 432_872_595L, 1_784_036_447L, 1_002L, 1_003L, 1_784_039_987L);
    final byte[] wire = wire(40)
        .putLong(432_872_595L)
        .putLong(1_784_036_447L)
        .putLong(1_002L)
        .putLong(1_003L)
        .putLong(1_784_039_987L)
        .array();

    assertEquals(40, clock.l());
    for (final int offset : OFFSETS) {
      assertWritesAt(clock, wire, offset);
      assertEquals(clock, Clock.read(atOffset(wire, offset), offset));
    }
    assertEquals(clock, Clock.read(wire));
    assertEquals(
        new Clock(address, 432_872_595L, 1_784_036_447L, 1_002L, 1_003L, 1_784_039_987L),
        Clock.read(address, wire)
    );
  }

  /// `Rent` is a `u64` lamports per byte-year, an `f64` exemption threshold and a `u8` burn
  /// percent. The burn percent is above `i8` range to pin its unsigned read, and the
  /// threshold is neither integer-path value so it cannot pass for a default.
  @Test
  void rentWritesItsWireLengthAtAnyOffsetAndEveryReadOverloadAgrees() {
    final var address = PublicKey.fromBase58Encoded("SysvarRent111111111111111111111111111111111");
    final var rent = new Rent(null, 6_960L, 1.5, 200);
    final byte[] wire = wire(17)
        .putLong(6_960L)
        .putDouble(1.5)
        .put((byte) 200)
        .array();

    assertEquals(17, rent.l());
    for (final int offset : OFFSETS) {
      assertWritesAt(rent, wire, offset);
      assertEquals(rent, Rent.read(atOffset(wire, offset), offset));
    }
    assertEquals(rent, Rent.read(wire));
    assertEquals(new Rent(address, 6_960L, 1.5, 200), Rent.read(address, wire));
  }

  /// `EpochSchedule` is a `u64` slots per epoch, a `u64` leader schedule slot offset, a `bool`
  /// warmup byte, then a `u64` first normal epoch and a `u64` first normal slot. The main-net
  /// fixture has warmup off and both trailing fields zero, which cannot distinguish a dropped
  /// trailing write or a warmup byte always written as zero; this fixture has warmup on and
  /// every numeric field distinct and non-zero.
  @Test
  void epochScheduleWithWarmupWritesItsWireLengthAtAnyOffsetAndEveryReadOverloadAgrees() {
    final var address = PublicKey.fromBase58Encoded("SysvarEpochSchedu1e111111111111111111111111");
    final var epochSchedule = new EpochSchedule(null, 432_000L, 216_000L, true, 14L, 524_256L);
    final byte[] wire = wire(33)
        .putLong(432_000L)
        .putLong(216_000L)
        .put((byte) 1)
        .putLong(14L)
        .putLong(524_256L)
        .array();

    assertEquals(33, epochSchedule.l());
    for (final int offset : OFFSETS) {
      assertWritesAt(epochSchedule, wire, offset);
      assertEquals(epochSchedule, EpochSchedule.read(atOffset(wire, offset), offset));
    }
    assertEquals(epochSchedule, EpochSchedule.read(wire));
    assertEquals(
        new EpochSchedule(address, 432_000L, 216_000L, true, 14L, 524_256L),
        EpochSchedule.read(address, wire)
    );
  }

  /// `LastRestartSlot` is a single little-endian `u64`.
  @Test
  void lastRestartSlotWritesItsWireLengthAtAnyOffsetAndEveryReadOverloadAgrees() {
    final var address = PublicKey.fromBase58Encoded("SysvarLastRestartS1ot1111111111111111111111");
    final var lastRestartSlot = new LastRestartSlot(null, 246_464_040L);
    final byte[] wire = wire(8).putLong(246_464_040L).array();

    assertEquals(8, lastRestartSlot.l());
    for (final int offset : OFFSETS) {
      assertWritesAt(lastRestartSlot, wire, offset);
      assertEquals(lastRestartSlot, LastRestartSlot.read(atOffset(wire, offset), offset));
    }
    assertEquals(lastRestartSlot, LastRestartSlot.read(wire));
    assertEquals(new LastRestartSlot(address, 246_464_040L), LastRestartSlot.read(address, wire));
  }

  /// `EpochRewards`' write at a non-zero offset is pinned above; this pins the
  /// address-taking `read` overload and `l()` against the wire size.
  @Test
  void epochRewardsReadWithAddressCarriesItAndLengthIsTheWireSize() {
    final var address = PublicKey.fromBase58Encoded("SysvarEpochRewards1111111111111111111111111");
    final byte[] data = Base64.getDecoder().decode("""
        6FJ+GAAAAAArAQAAAAAAAOpnvqJOxkGk1k3flJB8QnqTBZFakobZO4Y/nYulJqbVVQwykjvxe282cwIAAAAAACmaSKSIdQAAUgoxpIh1AAAA""");

    final var epochRewards = EpochRewards.read(address, data);
    assertEquals(address, epochRewards.address());
    assertEquals(410_931_944L, epochRewards.distributionStartingBlockHeight());
    assertEquals(129_229_730_679_378L, epochRewards.distributedRewards());
    assertNull(EpochRewards.read(data).address());

    assertEquals(81, data.length);
    assertEquals(81, epochRewards.l());
  }

  /// `SlotHashes` is a `u64` entry count followed by that many (`u64` slot, 32-byte hash)
  /// pairs. Two entries with distinct slots and hashes are written at each offset; the
  /// returned length, `l()` of the collection and of an entry, and both read overloads
  /// must agree with the independently built wire form.
  @Test
  void slotHashesWriteItsWireLengthAtAnyOffsetAndEveryReadOverloadAgrees() {
    final var address = PublicKey.fromBase58Encoded("SysvarS1otHashes111111111111111111111111111");
    final byte[] newerHash = new byte[32];
    final byte[] olderHash = new byte[32];
    for (int i = 0; i < 32; ++i) {
      newerHash[i] = (byte) (i + 1);
      olderHash[i] = (byte) (0x80 + i);
    }
    final var slotHashes = new SlotHashes(null, new SlotHash[]{
        new SlotHash(432_872_594L, newerHash),
        new SlotHash(432_872_593L, olderHash)
    });
    final byte[] wire = wire(8 + 2 * 40)
        .putLong(2L)
        .putLong(432_872_594L).put(newerHash)
        .putLong(432_872_593L).put(olderHash)
        .array();

    assertEquals(40, slotHashes.slotHashes()[0].l());
    assertEquals(wire.length, slotHashes.l());
    for (final int offset : OFFSETS) {
      assertWritesAt(slotHashes, wire, offset);
      assertSlotHashes(slotHashes, SlotHashes.read(atOffset(wire, offset), offset));
    }
    assertSlotHashes(slotHashes, SlotHashes.read(wire));
    final var addressed = SlotHashes.read(address, wire);
    assertEquals(address, addressed.address());
    assertSlotHashes(slotHashes, addressed);
  }

  private static void assertSlotHashes(final SlotHashes expected, final SlotHashes actual) {
    assertEquals(expected.slotHashes().length, actual.slotHashes().length);
    for (int i = 0; i < expected.slotHashes().length; ++i) {
      assertEquals(expected.slotHashes()[i].slot(), actual.slotHashes()[i].slot());
      assertArrayEquals(expected.slotHashes()[i].hash(), actual.slotHashes()[i].hash());
    }
  }

  /// `StakeHistory` is a `u64` entry count followed by that many (epoch, effective,
  /// activating, deactivating) `u64` quadruples. Two entries with distinct non-zero fields
  /// are written at each offset; the returned length, `l()` of the collection and of an
  /// entry, and both read overloads must agree with the independently built wire form.
  @Test
  void stakeHistoryWritesItsWireLengthAtAnyOffsetAndEveryReadOverloadAgrees() {
    final var address = PublicKey.fromBase58Encoded("SysvarStakeHistory1111111111111111111111111");
    final var newer = new StakeHistoryEntry(1001L, 429023881115486895L, 2778198923647242L, 5915395642511380L);
    final var older = new StakeHistoryEntry(1000L, 428061830089917206L, 2735681549682863L, 1867022092164017L);
    final var stakeHistory = new StakeHistory(null, new StakeHistoryEntry[]{newer, older});
    final byte[] wire = wire(8 + 2 * 32)
        .putLong(2L)
        .putLong(1001L).putLong(429023881115486895L).putLong(2778198923647242L).putLong(5915395642511380L)
        .putLong(1000L).putLong(428061830089917206L).putLong(2735681549682863L).putLong(1867022092164017L)
        .array();

    assertEquals(32, newer.l());
    assertEquals(wire.length, stakeHistory.l());
    for (final int offset : OFFSETS) {
      assertWritesAt(stakeHistory, wire, offset);
      assertArrayEquals(stakeHistory.entries(), StakeHistory.read(atOffset(wire, offset), offset).entries());
    }
    assertArrayEquals(stakeHistory.entries(), StakeHistory.read(wire).entries());
    final var addressed = StakeHistory.read(address, wire);
    assertEquals(address, addressed.address());
    assertArrayEquals(stakeHistory.entries(), addressed.entries());
  }

  private static byte[] readFixture(final String fileName) {
    try (final var in = SysvarTests.class.getResourceAsStream("/sysvars/" + fileName)) {
      return Base64.getDecoder().decode(new String(Objects.requireNonNull(in).readAllBytes()).strip());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
