package software.sava.core.programs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// `Discriminator.data()` hands out a **copy**, not the record's array. Nothing reached
/// the accessor before, so the copy was uncovered rather than argued — and an accessor
/// that returns the backing array instead would let any caller rewrite a discriminator
/// another caller is still matching against.
///
/// Asserted in both directions, per the copy-on-write rule: the returned bytes must
/// equal the input, *and* writing through the returned array must not reach the record.
final class DiscriminatorTests {

  private static final byte[] ANCHOR_EIGHT = {1, 2, 3, 4, 5, 6, 7, 8};

  @Test
  void dataReturnsTheDiscriminatorBytes() {
    final var discriminator = Discriminator.createDiscriminator(ANCHOR_EIGHT.clone());

    assertArrayEquals(ANCHOR_EIGHT, discriminator.data());
  }

  @Test
  void dataReturnsACopyTheCallerCannotWriteThrough() {
    final var discriminator = Discriminator.createDiscriminator(ANCHOR_EIGHT.clone());

    final byte[] first = discriminator.data();
    assertNotNull(first, "an accessor that hands back null is not a defensive copy");
    first[0] = (byte) 0xFF;

    assertArrayEquals(ANCHOR_EIGHT, discriminator.data(), "writing through the returned array must not reach the record");
    assertNotSame(first, discriminator.data(), "each call returns its own copy");
  }

  /// **Pins current behaviour, which is asymmetric.** `createDiscriminator(byte[])`
  /// stores the caller's array as-is, so a later write by the caller *is* visible through
  /// the discriminator — even though `data()` copies on the way out and the
  /// `(data, offset, length)` overloads copy on the way in. Reported rather than
  /// changed: callers may rely on either half, and a defensive copy here is an
  /// additive fix for the owner to make deliberately.
  @Test
  void theSingleArgFactoryAliasesTheCallersArray() {
    final byte[] mutable = ANCHOR_EIGHT.clone();
    final var aliasing = Discriminator.createDiscriminator(mutable);

    mutable[0] = (byte) 0xFF;

    assertEquals((byte) 0xFF, aliasing.data()[0], "createDiscriminator(byte[]) does not copy — see the note above");
  }

  /// The ranged overloads *do* copy, which is the half a caller is more likely to assume
  /// holds everywhere.
  @Test
  void theRangedFactoryCopiesTheCallersArray() {
    final byte[] mutable = ANCHOR_EIGHT.clone();
    final var copied = Discriminator.createDiscriminator(mutable, 0, ANCHOR_EIGHT.length);

    mutable[0] = (byte) 0xFF;

    assertArrayEquals(ANCHOR_EIGHT, copied.data(), "the ranged overload copies into a fresh array");
  }

  @Test
  void toIntArrayReadsEveryByteUnsigned() {
    final var discriminator = Discriminator.createDiscriminator(new byte[]{0, (byte) 0x80, (byte) 0xFF, 1, 2, 3, 4, 5});

    assertArrayEquals(new int[]{0, 128, 255, 1, 2, 3, 4, 5}, discriminator.toIntArray());
  }
}
