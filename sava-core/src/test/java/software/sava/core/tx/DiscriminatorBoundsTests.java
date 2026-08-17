package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.programs.Discriminator;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.programs.Discriminator.createDiscriminator;

/// A discriminator must match only within the instruction that carries it. The comparison used to
/// be bounded by the end of the whole serialized transaction, so a discriminator longer than an
/// instruction's payload was compared against the bytes that follow it — the next instruction's
/// account indices and data, or the trailing signature slots for the last one.
///
/// The oracle throughout is [Instruction#beginsWith], which has always bounded itself by the
/// instruction's own `len`: the filters and [Discriminator#test(Instruction)] must agree with it on
/// the same bytes.
final class DiscriminatorBoundsTests {

  private static PublicKey key(final int fill) {
    final byte[] key = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(key, (byte) fill);
    return key.length == 0 ? null : PublicKey.createPubKey(key);
  }

  private static final byte[] BLOCK_HASH = new byte[Transaction.BLOCK_HASH_LENGTH];

  /// Two instructions whose payloads are adjacent in the serialized message: a 4-byte first
  /// instruction followed by one whose data begins with the four bytes that complete an 8-byte
  /// Anchor-sized discriminator.
  private static Transaction twoAdjacentInstructions() {
    final var program = SolanaAccounts.MAIN_NET.systemProgram();
    final var account = AccountMeta.createWrite(key(9));
    final var first = Instruction.createInstruction(
        program, List.of(account), new byte[]{1, 2, 3, 4}
    );
    final var second = Instruction.createInstruction(
        program, List.of(account), new byte[]{5, 6, 7, 8, (byte) 0xAA}
    );
    final var tx = Transaction.createTx(key(1), List.of(first, second));
    tx.setRecentBlockHash(BLOCK_HASH);
    return tx;
  }

  /// An 8-byte discriminator made of the first instruction's whole 4-byte payload plus the 4 bytes
  /// that physically follow it in the serialized message. Those trailing bytes are the *second*
  /// instruction's header — its program index, account count, account index and data length — so
  /// nothing in the transaction actually begins with all eight, yet an unbounded comparison
  /// starting at the first instruction's data offset matches them exactly.
  private static Discriminator spanningDiscriminator(final byte[] serialized, final Instruction first) {
    assertEquals(4, first.len(), "the discriminator must be longer than the payload it targets");
    return createDiscriminator(Arrays.copyOfRange(serialized, first.offset(), first.offset() + 8));
  }

  @Test
  void filtersDoNotMatchAcrossAnInstructionBoundary() {
    final var tx = twoAdjacentInstructions();
    final byte[] serialized = tx.serialized();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(serialized);
    final var accounts = skeleton.parseAccounts();

    final var all = skeleton.parseInstructions(accounts);
    assertEquals(2, all.length);
    assertArrayEquals(new byte[]{1, 2, 3, 4}, all[0].copyData());
    assertArrayEquals(new byte[]{5, 6, 7, 8, (byte) 0xAA}, all[1].copyData());

    final var spanning = spanningDiscriminator(serialized, all[0]);
    // The trap is live: the eight bytes exist contiguously, they just are not one instruction's.
    assertTrue(spanning.equals(serialized, all[0].offset()), "unbounded, this is a match");
    assertFalse(all[0].beginsWith(spanning.data()), "bounded by the instruction, it is not");

    assertEquals(0, skeleton.filterInstructions(accounts, spanning).length);
    assertEquals(0, skeleton.filterInstructionsWithoutAccounts(spanning).length);
  }

  @Test
  void predicateAgreesWithBeginsWithOnTheSameBytes() {
    final var tx = twoAdjacentInstructions();
    final byte[] serialized = tx.serialized();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(serialized);
    final var first = skeleton.parseInstructionsWithoutAccounts()[0];
    final var spanning = spanningDiscriminator(serialized, first);

    assertFalse(first.beginsWith(spanning.data()), "the standing oracle");
    assertFalse(spanning.test(first), "Discriminator#test must agree with it");
  }

  /// The bound is `>=`, not `>`: a discriminator exactly as long as the instruction's data is a
  /// legitimate match and must survive.
  @Test
  void aDiscriminatorFillingTheWholePayloadStillMatches() {
    final var tx = twoAdjacentInstructions();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    final var accounts = skeleton.parseAccounts();
    final var exact = createDiscriminator(new byte[]{1, 2, 3, 4});

    final var matched = skeleton.filterInstructions(accounts, exact);
    assertEquals(1, matched.length);
    assertArrayEquals(new byte[]{1, 2, 3, 4}, matched[0].copyData());
    assertEquals(1, skeleton.filterInstructionsWithoutAccounts(exact).length);
    assertTrue(exact.test(skeleton.parseInstructionsWithoutAccounts()[0]));
  }

  /// The two-argument overload keeps its own contract — bounded by the end of the array — because
  /// it is the right one for a standalone payload. Only the region-aware overload is new.
  @Test
  void theRegionOverloadIsWhatNarrowsTheComparison() {
    final byte[] buffer = {1, 2, 3, 4, 5, 6, 7, 8};
    final var eight = createDiscriminator(buffer.clone());
    assertTrue(eight.equals(buffer, 0), "bounded by the array, so it matches");
    assertTrue(eight.equals(buffer, 0, 8));
    assertFalse(eight.equals(buffer, 0, 4), "bounded by the region, so it does not");
    assertThrows(IndexOutOfBoundsException.class, () -> eight.equals(buffer, 4, 8));
  }
}
