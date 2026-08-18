package software.sava.core.programs;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;

import java.util.List;

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

  /// Neither matching overload was reached by any test, so the arithmetic that positions the
  /// comparison window — `data.length - offset`, `offset + length` — and the `>=` that decides
  /// whether the window is even wide enough were all unobserved.
  ///
  /// Each assertion below pins one degree of freedom: a non-zero offset so a sign error in either
  /// term relocates the window, an exact-width match so the boundary cannot be tightened to `>`,
  /// and a one-byte-short buffer so the guard cannot be removed outright.
  @Test
  void equalsBoundsItsWindowByTheEndOfTheArray() {
    final var discriminator = Discriminator.createDiscriminator(ANCHOR_EIGHT.clone());
    final byte[] framed = {(byte) 0xEE, (byte) 0xEE, 1, 2, 3, 4, 5, 6, 7, 8};

    assertTrue(discriminator.equals(framed, 2), "the eight bytes begin at offset 2");
    assertFalse(discriminator.equals(framed, 1), "shifted by one, they do not");
    assertFalse(discriminator.equals(framed, 3), "nor shifted the other way");

    // Exactly wide enough: the boundary is >=, so tightening it to > must break this.
    assertTrue(discriminator.equals(ANCHOR_EIGHT.clone(), 0));
    // One byte short of the discriminator: the width guard is the only thing rejecting it.
    assertFalse(discriminator.equals(new byte[]{1, 2, 3, 4, 5, 6, 7}, 0));
    assertFalse(discriminator.equals(framed, framed.length), "an empty window matches nothing");
  }

  /// The `Predicate<Instruction>` half of this interface, which is the form a stream filter uses.
  ///
  /// It reads an instruction's data out of the buffer that instruction was parsed from, so it must
  /// bound itself by that instruction's own length rather than by the enclosing array — otherwise a
  /// discriminator longer than the payload is completed by whatever bytes follow it, and where those
  /// are attacker influenced the match can be forged. `DiscriminatorBoundsTests` covers this against
  /// real serialized transactions; the case is repeated here because that class lives in
  /// `software.sava.core.tx` and so is outside this interface's own mutation suite.
  @Test
  void testBoundsItselfByTheInstructionRatherThanTheBuffer() {
    final byte[] buffer = {1, 2, 3, 4, 5, 6, 7, 8, 9};
    final var eight = Discriminator.createDiscriminator(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

    // An instruction owning all eight bytes matches; the same bytes with a shorter instruction
    // window do not, even though the buffer still holds them.
    assertTrue(eight.test(instructionOver(buffer, 0, 8)));
    assertFalse(eight.test(instructionOver(buffer, 0, 7)));
    assertFalse(eight.test(instructionOver(buffer, 1, 8)));
  }

  private static Instruction instructionOver(final byte[] data, final int offset, final int len) {
    return Instruction.createInstruction(
        AccountMeta.createRead(PublicKey.createPubKey(new byte[PublicKey.PUBLIC_KEY_LENGTH])),
        List.of(),
        data, offset, len
    );
  }

  /// The region overload takes its width from the caller instead of the array, so a discriminator
  /// longer than the region must not be completed by whatever follows it.
  @Test
  void equalsBoundsItsWindowByTheGivenLength() {
    final var discriminator = Discriminator.createDiscriminator(ANCHOR_EIGHT.clone());
    final byte[] framed = {(byte) 0xEE, (byte) 0xEE, 1, 2, 3, 4, 5, 6, 7, 8};

    assertTrue(discriminator.equals(framed, 2, 8), "the region is exactly the discriminator");
    assertFalse(discriminator.equals(framed, 2, 7), "one byte narrower and the trailing 8 is not ours");
    assertFalse(discriminator.equals(framed, 1, 8), "same width, wrong start");

    // The array is long enough either way; only the declared region decides the outcome, which is
    // the whole difference between this overload and the two-argument one.
    assertTrue(discriminator.equals(framed, 2));
    assertFalse(discriminator.equals(framed, 2, 4));

    assertThrows(IndexOutOfBoundsException.class, () -> discriminator.equals(framed, 4, 8));
    assertThrows(IndexOutOfBoundsException.class, () -> discriminator.equals(framed, -1, 8));
  }
}
