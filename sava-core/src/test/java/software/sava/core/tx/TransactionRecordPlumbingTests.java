package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

/// Record-level plumbing the serialization and signing suites skip: instruction
/// prepend/append rebuilds, the version sentinel, argument guards, and the
/// `InstructionRecord` equality/rendering branches. Fixed keys, fully deterministic.
final class TransactionRecordPlumbingTests {

  private static final byte[] HASH = hash();

  private static byte[] hash() {
    final byte[] hash = new byte[Transaction.BLOCK_HASH_LENGTH];
    Arrays.fill(hash, (byte) 0xC3);
    return hash;
  }

  private static PublicKey key(final int fill) {
    final byte[] key = new byte[PUBLIC_KEY_LENGTH];
    Arrays.fill(key, (byte) fill);
    return PublicKey.createPubKey(key);
  }

  private static final PublicKey FEE_PAYER = key(10);
  private static final PublicKey PROGRAM = key(11);
  private static final PublicKey WRITE_ACCOUNT = key(12);
  private static final PublicKey READ_ACCOUNT = key(13);
  private static final PublicKey ALT_ADDRESS = key(30);

  private static Instruction instruction(final byte... data) {
    return Instruction.createInstruction(
        PROGRAM,
        List.of(AccountMeta.createWrite(WRITE_ACCOUNT), AccountMeta.createRead(READ_ACCOUNT)),
        data
    );
  }

  private static Transaction hashedTx(final List<Instruction> instructions) {
    final var tx = Transaction.createTx(AccountMeta.createFeePayer(FEE_PAYER), instructions);
    tx.setRecentBlockHash(HASH);
    return tx;
  }

  @Test
  void prependAndAppendRebuildTheInstructionList() {
    final var first = instruction((byte) 1);
    final var second = instruction((byte) 2);
    final var extra1 = instruction((byte) 3);
    final var extra2 = instruction((byte) 4);
    final var base = hashedTx(List.of(first, second));

    assertArrayEquals(
        hashedTx(List.of(extra1, first, second)).serialized(),
        base.prependIx(extra1).serialized()
    );
    assertArrayEquals(
        hashedTx(List.of(extra1, extra2, first, second)).serialized(),
        base.prependInstructions(extra1, extra2).serialized()
    );
    assertArrayEquals(
        hashedTx(List.of(extra1, extra2, first, second)).serialized(),
        base.prependInstructions(List.of(extra1, extra2)).serialized()
    );
    assertArrayEquals(
        hashedTx(List.of(first, second, extra1)).serialized(),
        base.appendIx(extra1).serialized()
    );
    // the receiver keeps its own instructions and block hash
    assertArrayEquals(hashedTx(List.of(first, second)).serialized(), base.serialized());
  }

  @Test
  void versionDistinguishesLegacyFromV0() {
    final var legacy = hashedTx(List.of(instruction((byte) 1)));
    assertEquals(TransactionRecord.VERSIONED_BIT_MASK, legacy.version());
    assertEquals(
        TransactionRecord.VERSIONED_BIT_MASK,
        TransactionSkeleton.deserializeSkeleton(legacy.serialized()).version()
    );

    final byte[] tableData = new byte[AddressLookupTable.LOOKUP_TABLE_META_SIZE + PUBLIC_KEY_LENGTH];
    ByteUtil.putInt64LE(tableData, AddressLookupTable.DEACTIVATION_SLOT_OFFSET, -1L);
    READ_ACCOUNT.write(tableData, AddressLookupTable.LOOKUP_TABLE_META_SIZE);
    final var table = AddressLookupTable.read(ALT_ADDRESS, tableData);
    final var v0 = Transaction.createTx(
        AccountMeta.createFeePayer(FEE_PAYER), List.of(instruction((byte) 1)), table);
    assertEquals(0, v0.version());
    assertEquals(0, TransactionSkeleton.deserializeSkeleton(v0.serialized()).version());
  }

  @Test
  void setRecentBlockHashRejectsMalformedHashes() {
    final var tx = hashedTx(List.of(instruction((byte) 1)));
    assertThrows(IllegalArgumentException.class, () -> tx.setRecentBlockHash((byte[]) null));
    assertThrows(IllegalArgumentException.class, () -> tx.setRecentBlockHash(new byte[31]));
    assertThrows(IllegalArgumentException.class, () -> tx.setRecentBlockHash(new byte[33]));
    assertArrayEquals(HASH, tx.recentBlockHash(), "rejected hashes must leave the payload untouched");
  }

  @Test
  void createTxRejectsEmptyInstructions() {
    final var feePayer = AccountMeta.createFeePayer(FEE_PAYER);
    assertThrows(IllegalArgumentException.class, () -> Transaction.createTx(feePayer, List.of()));
  }

  @Test
  void instructionEqualityComparesContent() {
    final var ix = instruction((byte) 1, (byte) 2);
    assertEquals(ix, instruction((byte) 1, (byte) 2));
    assertNotEquals(ix, instruction((byte) 1, (byte) 3));
    assertNotEquals(ix, instruction((byte) 1));
    assertNotEquals(ix, (Object) "not an instruction");
  }

  @Test
  void extraAccountsWithNoAccountsReturnsTheReceiver() {
    final var ix = instruction((byte) 1);
    assertSame(ix, ix.extraAccounts(List.of()));
  }

  @Test
  void instructionToStringRendersEveryBranch() {
    final var normal = instruction((byte) 1, (byte) 2);
    final var normalJson = normal.toString();
    assertTrue(normalJson.contains(PROGRAM.toBase58()), normalJson);
    assertTrue(normalJson.contains("\"data\": \"AQI=\""), normalJson);

    final var nullMeta = Instruction.createInstruction(
        PROGRAM, Arrays.asList(AccountMeta.createWrite(WRITE_ACCOUNT), null), new byte[]{1});
    assertTrue(nullMeta.toString().contains("?"), "null metas render as placeholders");

    final var emptyAccounts = Instruction.createInstruction(PROGRAM, List.of(), new byte[]{1});
    assertTrue(emptyAccounts.toString().contains("\"accounts\": []"), emptyAccounts.toString());

    final var nullAccounts = new InstructionRecord(
        AccountMeta.createInvoked(PROGRAM), null, new byte[]{1}, 0, 1);
    assertTrue(nullAccounts.toString().contains("\"accounts\": []"), nullAccounts.toString());

    final var nullData = new InstructionRecord(
        AccountMeta.createInvoked(PROGRAM), List.of(), null, 0, 0);
    assertTrue(nullData.toString().contains("\"data\": \"\""), nullData.toString());

    final var emptyData = Instruction.createInstruction(PROGRAM, List.of(), new byte[0]);
    assertTrue(emptyData.toString().contains("\"data\": \"\""), emptyData.toString());
  }
}
