package software.sava.core.tx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.programs.Discriminator;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.meta.AccountMeta.*;
import static software.sava.core.programs.Discriminator.toDiscriminator;

/// Consistency of the v1 instruction views: every view must validate an instruction's
/// program id index, and every view must agree that an instruction's program is invoked.
final class V1InstructionViewTests {

  private static final byte[] BLOCK_HASH = blockHash();

  private static byte[] blockHash() {
    final byte[] blockHash = new byte[Transaction.BLOCK_HASH_LENGTH];
    for (int b = 0; b < blockHash.length; ++b) {
      blockHash[b] = (byte) (b + 23);
    }
    return blockHash;
  }

  /// AGENTS.md requires randomized tests to use fixed seeds: PIT re-runs the suite once per mutant,
  /// so a generated key pair would make a kill non-reproducible. The counter is reset before each
  /// test so the keys depend on neither execution order nor how many tests ran first.
  private static final AtomicInteger KEY_SEED = new AtomicInteger();

  @BeforeEach
  void resetKeySeed() {
    KEY_SEED.set(0);
  }

  private static Signer nextSigner() {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) KEY_SEED.incrementAndGet());
    return Signer.createFromPrivateKey(privateKey);
  }

  private static PublicKey newKey() {
    return nextSigner().publicKey();
  }

  private static Instruction ix(final PublicKey program, final List<AccountMeta> accounts, final int... data) {
    final byte[] ixData = new byte[data.length];
    for (int i = 0; i < data.length; ++i) {
      ixData[i] = (byte) data[i];
    }
    return Instruction.createInstruction(program, accounts, ixData);
  }

  private static byte[] serializedV1(final PublicKey feePayer, final Instruction... instructions) {
    final var tx = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstructions(List.of(instructions))
        .createTransaction();
    assertInstanceOf(V1Transaction.class, tx);
    tx.setRecentBlockHash(BLOCK_HASH);
    return tx.serialized();
  }

  /// The v1 InstructionHeaders are fixed width and contiguous at the instructions offset, so an
  /// instruction's program id index is the first byte of its header.
  private static byte[] withProgramIdIndex(final byte[] data, final int instructionIndex, final int programIdIndex) {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final byte[] corrupted = Arrays.copyOf(data, data.length);
    final int header = skeleton.instructionsOffset() + (instructionIndex * TxBuilderImpl.V1_INSTRUCTION_HEADER_LENGTH);
    assertEquals(data[header], corrupted[header]);
    corrupted[header] = (byte) programIdIndex;
    return corrupted;
  }

  private static AccountMeta findMeta(final AccountMeta[] accounts, final PublicKey publicKey) {
    for (final var account : accounts) {
      if (account != null && publicKey.equals(account.publicKey())) {
        return account;
      }
    }
    return fail("No account meta for " + publicKey.toBase58());
  }

  /// Three instructions over three included accounts: the fee payer, one writable signer and the
  /// system program, so account index 3 is the first index outside the included accounts.
  private static byte[] threeInstructionV1(final PublicKey feePayer, final PublicKey signer) {
    final var program = SolanaAccounts.MAIN_NET.systemProgram();
    return serializedV1(feePayer,
        ix(program, List.of(createWritableSigner(signer)), 1, 1, 1, 1),
        ix(program, List.of(), 2, 2, 2, 2),
        ix(program, List.of(), 3, 3, 3, 3)
    );
  }

  private static final String MALFORMED_INDEX_MESSAGE = "Program account index 3 is outside the 3 included accounts.";

  /// The filter loop as it ran before the fix, reproduced over the public wire layout: the program
  /// id index was read and validated only inside the matched branch, by
  /// `getProgramAccount(programIdIndex(header))`. Returns the number of matches.
  ///
  /// @throws IndexOutOfBoundsException only if a *matched* instruction's program index is outside
  ///                                   the included accounts, which is exactly the gap the fix closed
  private static int preFixFilterMatches(final TransactionSkeleton skeleton, final Discriminator discriminator) {
    final byte[] data = skeleton.data();
    final int numInstructions = skeleton.numInstructions();
    final int instructionsOffset = skeleton.instructionsOffset();
    final int numIncludedAccounts = skeleton.numIncludedAccounts();
    int matches = 0;
    int cursor = instructionsOffset + (numInstructions * TxBuilderImpl.V1_INSTRUCTION_HEADER_LENGTH);
    for (int i = 0, header = instructionsOffset;
         i < numInstructions;
         ++i, header += TxBuilderImpl.V1_INSTRUCTION_HEADER_LENGTH) {
      final int numIxAccounts = data[header + 1] & 0xFF;
      final int numDataBytes = ByteUtil.getInt16LE(data, header + 2) & 0xFFFF;
      final int dataOffset = cursor + numIxAccounts;
      if (discriminator.equals(data, dataOffset)) {
        final int programAccountIndex = data[header] & 0xFF;
        if (programAccountIndex >= numIncludedAccounts) {
          throw new IndexOutOfBoundsException(String.format(
              "Program account index %d is outside the %d included accounts.",
              programAccountIndex, numIncludedAccounts
          ));
        }
        ++matches;
      }
      cursor = dataOffset + numDataBytes;
    }
    return matches;
  }

  // Pins V1TransactionSkeleton#filterInstructions and #filterInstructionsWithoutAccounts calling
  // requireIncludedProgramAccount(programIdIndex(header)) at the top of the loop rather than only
  // within the matched branch. The malformed instruction is deliberately one the discriminator
  // does not match, so the pre-fix getProgramAccount(...) call inside the if branch never read it.
  @Test
  void testV1FilterValidatesUnmatchedInstructionProgramIndex() {
    final var feePayer = newKey();
    final var signer = newKey();
    final byte[] data = threeInstructionV1(feePayer, signer);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertEquals(3, skeleton.numInstructions());
    assertEquals(3, skeleton.numIncludedAccounts());

    // Only the first instruction matches, so the second is filtered out without its program index
    // ever being consulted by the matched branch.
    final var firstDiscriminator = toDiscriminator(1, 1, 1, 1);
    final var accounts = skeleton.parseAccounts();
    assertEquals(1, skeleton.filterInstructions(accounts, firstDiscriminator).length);
    assertEquals(1, skeleton.filterInstructionsWithoutAccounts(firstDiscriminator).length);

    // Corrupt the unmatched middle instruction's program id index to the first index outside the
    // included accounts.
    final byte[] corrupted = withProgramIdIndex(data, 1, 3);
    final var corruptedSkeleton = TransactionSkeleton.deserializeSkeleton(corrupted);
    // The corruption is invisible to the account views: only the instruction views can catch it.
    final var corruptedAccounts = corruptedSkeleton.parseAccounts();
    assertEquals(3, corruptedAccounts.length);
    assertEquals(feePayer, corruptedSkeleton.feePayer());

    // Negative control: the pre-fix loop validated only the matched instruction, so it returns the
    // single match over this very payload without ever reading the malformed program index.
    assertEquals(1, preFixFilterMatches(corruptedSkeleton, firstDiscriminator));

    var ex = assertThrows(IndexOutOfBoundsException.class,
        () -> corruptedSkeleton.filterInstructions(corruptedAccounts, firstDiscriminator)
    );
    assertEquals(MALFORMED_INDEX_MESSAGE, ex.getMessage());

    ex = assertThrows(IndexOutOfBoundsException.class,
        () -> corruptedSkeleton.filterInstructionsWithoutAccounts(firstDiscriminator)
    );
    assertEquals(MALFORMED_INDEX_MESSAGE, ex.getMessage());

    // The interface default delegates to filterInstructions(parseAccounts(), discriminator).
    ex = assertThrows(IndexOutOfBoundsException.class,
        () -> corruptedSkeleton.filterInstructionsWithoutTableAccounts(firstDiscriminator)
    );
    assertEquals(MALFORMED_INDEX_MESSAGE, ex.getMessage());

    // A discriminator that matches nothing at all must be rejected just the same.
    final var noDiscriminator = toDiscriminator(9, 9, 9, 9);
    assertEquals(0, skeleton.filterInstructions(accounts, noDiscriminator).length);
    assertEquals(0, preFixFilterMatches(corruptedSkeleton, noDiscriminator));
    assertThrows(IndexOutOfBoundsException.class,
        () -> corruptedSkeleton.filterInstructions(corruptedAccounts, noDiscriminator)
    );
    assertThrows(IndexOutOfBoundsException.class,
        () -> corruptedSkeleton.filterInstructionsWithoutAccounts(noDiscriminator)
    );
  }

  // Pins the whole v1 view family reporting a malformed program id index identically: the parse
  // views via requireIncludedProgramAccount (V1TransactionSkeleton#parseInstructions line
  // `requireIncludedProgramAccount(programAccountIndex)` and #getProgramAccount) and the filter
  // views via the same check hoisted to the top of their loops. Without the filter check the two
  // halves of the family disagree and the message assertions below are never reached.
  @Test
  void testV1ViewFamilyAgreesOnMalformedProgramIndex() {
    final var feePayer = newKey();
    final var signer = newKey();
    final byte[] corrupted = withProgramIdIndex(threeInstructionV1(feePayer, signer), 1, 3);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(corrupted);
    final var accounts = skeleton.parseAccounts();
    final var firstDiscriminator = toDiscriminator(1, 1, 1, 1);

    final List<View> views = List.of(
        skeleton::parseProgramAccounts,
        skeleton::parseInstructionsWithoutAccounts,
        () -> skeleton.parseInstructions(accounts),
        skeleton::parseInstructionsWithoutTableAccounts,
        skeleton::parseLegacyInstructions,
        () -> skeleton.filterInstructions(accounts, firstDiscriminator),
        () -> skeleton.filterInstructionsWithoutAccounts(firstDiscriminator)
    );
    for (final var view : views) {
      final var ex = assertThrows(IndexOutOfBoundsException.class, view::read);
      // Not a bare ArrayIndexOutOfBoundsException from accounts[programAccountIndex]: the index
      // and the account count are both named.
      assertEquals(MALFORMED_INDEX_MESSAGE, ex.getMessage());
    }
  }

  /// One of the skeleton's instruction views, so that the whole family can be exercised uniformly.
  @FunctionalInterface
  private interface View {

    Object read();
  }

  // Pins V1TransactionSkeleton#parseInstructions wrapping the program in
  // invokedProgramAccount(accounts[programAccountIndex]). parseAccounts() has no invoked indexes
  // to consult and types a program as a plain read only meta, so pre-fix parseInstructions
  // reported invoked=false while parseInstructionsWithoutTableAccounts and filterInstructions
  // reported invoked=true for the very same instruction.
  @Test
  void testV1ParsedProgramAccountsAreInvoked() {
    final var feePayer = newKey();
    final var signer = newKey();
    final var secondProgram = newKey();
    final var systemProgram = SolanaAccounts.MAIN_NET.systemProgram();

    final byte[] data = serializedV1(feePayer,
        ix(systemProgram, List.of(createWritableSigner(signer)), 1, 1, 1, 1),
        ix(secondProgram, List.of(), 2, 2, 2, 2)
    );

    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts();
    // The input to parseInstructions: neither program is marked invoked by parseAccounts().
    assertFalse(findMeta(accounts, systemProgram).invoked());
    assertFalse(findMeta(accounts, secondProgram).invoked());
    assertEquals(createRead(systemProgram), findMeta(accounts, systemProgram));
    assertEquals(createRead(secondProgram), findMeta(accounts, secondProgram));

    final var parsed = skeleton.parseInstructions(accounts);
    final var withoutTableAccounts = skeleton.parseInstructionsWithoutTableAccounts();
    final var withoutAccounts = skeleton.parseInstructionsWithoutAccounts();
    final var programAccounts = skeleton.parseProgramAccounts();
    assertEquals(2, parsed.length);
    assertEquals(parsed.length, withoutTableAccounts.length);
    assertEquals(parsed.length, withoutAccounts.length);
    assertEquals(parsed.length, programAccounts.length);

    for (int i = 0; i < parsed.length; ++i) {
      final var programId = parsed[i].programId();
      // Negative control: `accounts[programAccountIndex]` was the pre-fix program meta. Read the
      // same index off the wire to show it is a plain read only meta, so dropping the
      // invokedProgramAccount(...) wrap makes the assertion below fail.
      final int programAccountIndex = data[
          skeleton.instructionsOffset() + (i * TxBuilderImpl.V1_INSTRUCTION_HEADER_LENGTH)
          ] & 0xFF;
      assertEquals(programId.publicKey(), accounts[programAccountIndex].publicKey());
      assertFalse(accounts[programAccountIndex].invoked());
      assertTrue(programId.invoked(), "parseInstructions must mark the program invoked");
      // parseInstructionsWithoutTableAccounts resolves invoked accounts from the invoked indexes.
      assertTrue(withoutTableAccounts[i].programId().invoked());
      assertEquals(withoutTableAccounts[i].programId(), programId);
      assertEquals(createInvoked(programAccounts[i]), programId);
      // parseInstructionsWithoutAccounts creates its program meta from the public key.
      assertTrue(withoutAccounts[i].programId().invoked());
      assertEquals(withoutAccounts[i].programId(), programId);

      final var filtered = skeleton.filterInstructions(accounts, parsed[i].wrapDiscriminator(4));
      assertEquals(1, filtered.length);
      assertTrue(filtered[0].programId().invoked());
      // Full instruction equality: program meta, accounts and data must all agree.
      assertEquals(parsed[i], filtered[0]);
    }

    assertEquals(systemProgram, parsed[0].programId().publicKey());
    assertEquals(secondProgram, parsed[1].programId().publicKey());
  }

  // Pins v1 and legacy agreeing that an instruction's program is invoked: legacy has done so since
  // TransactionSkeletonImpl#parseInstructions wrapped its program in invokedProgramAccount, and the
  // v1 skeleton now shares that helper from BaseTransactionSkeleton. Reverting the v1 wrap leaves
  // the legacy view invoked and the v1 view not.
  @Test
  void testV1AndLegacyProgramInvokedViewsAgree() {
    final var feePayer = newKey();
    final var signer = newKey();
    final var secondProgram = newKey();
    final var systemProgram = SolanaAccounts.MAIN_NET.systemProgram();

    final var ix1 = ix(systemProgram, List.of(createWritableSigner(signer)), 1, 1, 1, 1);
    final var ix2 = ix(secondProgram, List.of(), 2, 2, 2, 2);

    final var v1Skeleton = TransactionSkeleton.deserializeSkeleton(serializedV1(feePayer, ix1, ix2));
    final var v1Parsed = v1Skeleton.parseInstructions(v1Skeleton.parseAccounts());

    final var legacyTx = Transaction.createTx(feePayer, List.of(ix1, ix2));
    legacyTx.setRecentBlockHash(BLOCK_HASH);
    final var legacySkeleton = TransactionSkeleton.deserializeSkeleton(legacyTx.serialized());
    assertTrue(legacySkeleton.isLegacy());
    final var legacyParsed = legacySkeleton.parseInstructions(legacySkeleton.parseAccounts());

    assertEquals(legacyParsed.length, v1Parsed.length);
    for (int i = 0; i < legacyParsed.length; ++i) {
      assertTrue(legacyParsed[i].programId().invoked());
      assertTrue(v1Parsed[i].programId().invoked());
      assertEquals(legacyParsed[i].programId(), v1Parsed[i].programId());
    }
  }

  // Pins AccountMetaWrite#merge keeping the write flag when an invoked meta is merged in, i.e. the
  // `return accountMeta.write() ? accountMeta : new AccountMetaInvokedAndWrite(publicKey)` branch.
  // An account written by one instruction and invoked as another's program must keep both flags,
  // otherwise rebuilding demotes it to read only.
  @Test
  void testWrittenAndInvokedAccountKeepsBothFlags() {
    final var feePayer = newKey();
    final var program = newKey();
    final var systemProgram = SolanaAccounts.MAIN_NET.systemProgram();

    // The first instruction writes the account which the second instruction invokes as its program.
    final var writeIx = ix(systemProgram, List.of(createWrite(program)), 1, 1, 1, 1);
    final var invokeIx = ix(program, List.of(), 2, 2, 2, 2);

    assertTrue(createWrite(program).merge(createInvoked(program)).write());
    assertTrue(createWrite(program).merge(createInvoked(program)).invoked());

    final byte[] data = serializedV1(feePayer, writeIx, invokeIx);
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    // feePayer, the written and invoked program, the system program.
    assertEquals(3, skeleton.numIncludedAccounts());
    assertEquals(1, skeleton.numReadonlyUnsignedAccounts());
    assertTrue(findMeta(skeleton.parseAccounts(), program).write());

    final var parsed = skeleton.parseInstructions(skeleton.parseAccounts());
    assertTrue(findMeta(parsed[0].accounts().toArray(ACCOUNT_META_ARRAY_GENERATOR), program).write());
    assertTrue(parsed[1].programId().invoked());

    // Merging the parsed instructions recovers both flags on the single account meta.
    final var merged = AccountMeta.createAccountsMap(4, feePayer);
    for (final var instruction : parsed) {
      instruction.mergeAccounts(merged);
    }
    final var mergedProgram = merged.get(program);
    assertTrue(mergedProgram.write(), "a written account invoked as a program must stay writable");
    assertTrue(mergedProgram.invoked(), "an invoked account written by an instruction must stay invoked");

    // The same holds end to end: the rebuilt transaction keeps the program in the writable region
    // rather than demoting it to one of the read only unsigned accounts.
    final var rebuilt = TransactionSkeleton.deserializeSkeleton(
        TxBuilder.createBuilder().feePayer(feePayer).addInstructions(List.of(parsed)).createTransaction().serialized()
    );
    assertEquals(3, rebuilt.numIncludedAccounts());
    assertEquals(1, rebuilt.numReadonlyUnsignedAccounts());
    assertTrue(findMeta(rebuilt.parseAccounts(), program).write());
    assertTrue(rebuilt.parseInstructions(rebuilt.parseAccounts())[1].programId().invoked());
  }

  // Covers the v1 filter loops end to end: no match, every instruction matching, a match on the
  // first instruction only and on the last only. Every one of these also exercises the eager
  // requireIncludedProgramAccount call for instructions the discriminator does not match.
  @Test
  void testV1FilterMatchPositions() {
    final var feePayer = newKey();
    final var signer = newKey();
    final var program = SolanaAccounts.MAIN_NET.systemProgram();

    final byte[] data = serializedV1(feePayer,
        ix(program, List.of(createWritableSigner(signer)), 5, 5, 5, 5),
        ix(program, List.of(), 6, 6, 6, 6),
        ix(program, List.of(createWritableSigner(signer)), 7, 7, 7, 7)
    );
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts();
    final var parsed = skeleton.parseInstructions(accounts);
    assertEquals(3, parsed.length);

    // Zero matches: an exactly sized empty array, not a three element array of nulls.
    final var noMatches = toDiscriminator(4, 4, 4, 4);
    assertEquals(0, skeleton.filterInstructions(accounts, noMatches).length);
    assertEquals(0, skeleton.filterInstructionsWithoutAccounts(noMatches).length);

    // A match on the first instruction only.
    final var firstMatch = skeleton.filterInstructions(accounts, toDiscriminator(5, 5, 5, 5));
    assertEquals(1, firstMatch.length);
    assertEquals(parsed[0], firstMatch[0]);
    assertEquals(1, skeleton.filterInstructionsWithoutAccounts(toDiscriminator(5, 5, 5, 5)).length);

    // A match on the last instruction only.
    final var lastMatch = skeleton.filterInstructions(accounts, toDiscriminator(7, 7, 7, 7));
    assertEquals(1, lastMatch.length);
    assertEquals(parsed[2], lastMatch[0]);
    final var lastWithoutAccounts = skeleton.filterInstructionsWithoutAccounts(toDiscriminator(7, 7, 7, 7));
    assertEquals(1, lastWithoutAccounts.length);
    assertEquals(program, lastWithoutAccounts[0].programId().publicKey());
    assertTrue(lastWithoutAccounts[0].accounts().isEmpty());
    assertArrayEquals(new byte[]{7, 7, 7, 7}, lastWithoutAccounts[0].copyData());
  }

  @Test
  void testV1FilterAllInstructionsMatch() {
    final var feePayer = newKey();
    final var signer = newKey();
    final var program = SolanaAccounts.MAIN_NET.systemProgram();

    final byte[] data = serializedV1(feePayer,
        ix(program, List.of(createWritableSigner(signer)), 8, 8, 8, 8),
        ix(program, List.of(), 8, 8, 8, 8),
        ix(program, List.of(createWritableSigner(signer)), 8, 8, 8, 8)
    );
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts();
    final var parsed = skeleton.parseInstructions(accounts);
    final var discriminator = toDiscriminator(8, 8, 8, 8);

    // Every instruction matches, so the full array is returned without a copy.
    final var filtered = skeleton.filterInstructions(accounts, discriminator);
    assertEquals(skeleton.numInstructions(), filtered.length);
    for (int i = 0; i < filtered.length; ++i) {
      assertNotNull(filtered[i]);
      assertEquals(parsed[i], filtered[i]);
    }

    final var filteredWithoutAccounts = skeleton.filterInstructionsWithoutAccounts(discriminator);
    assertEquals(skeleton.numInstructions(), filteredWithoutAccounts.length);
    for (final var instruction : filteredWithoutAccounts) {
      assertNotNull(instruction);
      assertTrue(instruction.accounts().isEmpty());
      assertTrue(instruction.programId().invoked());
      assertArrayEquals(new byte[]{8, 8, 8, 8}, instruction.copyData());
    }
  }

  @Test
  void testV1FilterReturnsExactlySizedArray() {
    final var feePayer = newKey();
    final var signer = newKey();
    final var program = SolanaAccounts.MAIN_NET.systemProgram();

    final byte[] data = serializedV1(feePayer,
        ix(program, List.of(createWritableSigner(signer)), 7, 7, 7, 7),
        ix(program, List.of(), 8, 8, 8, 8),
        ix(program, List.of(), 7, 7, 7, 7)
    );
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts();
    final var parsed = skeleton.parseInstructions(accounts);
    final var discriminator = toDiscriminator(7, 7, 7, 7);

    // Two of the three instructions match: the array is trimmed rather than null padded.
    final var filtered = skeleton.filterInstructions(accounts, discriminator);
    assertEquals(2, filtered.length);
    assertEquals(parsed[0], filtered[0]);
    assertEquals(parsed[2], filtered[1]);

    final var filteredWithoutAccounts = skeleton.filterInstructionsWithoutAccounts(discriminator);
    assertEquals(2, filteredWithoutAccounts.length);
    for (final var instruction : filteredWithoutAccounts) {
      assertNotNull(instruction);
      assertArrayEquals(new byte[]{7, 7, 7, 7}, instruction.copyData());
    }
  }

  @Test
  void testV1FilterDiscriminatorLongerThanInstructionData() {
    final var feePayer = newKey();
    final var signer = newKey();
    final var program = SolanaAccounts.MAIN_NET.systemProgram();

    final byte[] data = serializedV1(feePayer,
        ix(program, List.of(createWritableSigner(signer)), 1, 2, 3, 4),
        ix(program, List.of(), 5, 6, 7, 8)
    );
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts();

    // Eight bytes against four byte payloads; no instruction begins with these bytes.
    final var longDiscriminator = Discriminator.createDiscriminator(new byte[]{
        (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE,
        (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE
    });
    assertEquals(8, longDiscriminator.length());
    assertEquals(0, skeleton.filterInstructions(accounts, longDiscriminator).length);
    assertEquals(0, skeleton.filterInstructionsWithoutAccounts(longDiscriminator).length);

    // The last instruction's four data bytes are followed by the signature slots, so a longer
    // discriminator is still bounds safe against the serialized length.
    for (final var instruction : skeleton.parseInstructions(accounts)) {
      assertEquals(4, instruction.len());
      assertTrue(instruction.offset() + longDiscriminator.length() <= data.length);
      assertFalse(instruction.beginsWith(longDiscriminator.data()));
    }
  }

  /// Characterizes a known defect rather than endorsing it, so that fixing it is a deliberate
  /// change and not a surprise: [Discriminator#equals(byte[], int)] only bounds checks the
  /// discriminator against the whole serialized length, never against the matched instruction's
  /// own data length, so a discriminator longer than an instruction's data is compared against the
  /// bytes that follow it. Both filter loops pass only the data offset, so a longer discriminator
  /// can match an instruction it does not fit in. This is not a v1 regression: the legacy skeleton
  /// spills identically, which the second half asserts.
  @Test
  void testFilterDiscriminatorReadsPastInstructionData() {
    final var feePayer = newKey();
    final var signer = newKey();
    final var program = SolanaAccounts.MAIN_NET.systemProgram();

    final var ix1 = ix(program, List.of(createWritableSigner(signer)), 1, 2, 3, 4);
    final var ix2 = ix(program, List.of(), 5, 6, 7, 8);

    final byte[] v1Data = serializedV1(feePayer, ix1, ix2);
    final var v1Skeleton = TransactionSkeleton.deserializeSkeleton(v1Data);
    final var v1Accounts = v1Skeleton.parseAccounts();
    final var v1Parsed = v1Skeleton.parseInstructions(v1Accounts);
    assertEquals(4, v1Parsed[0].len());

    // Eight bytes read from the first instruction's data offset: four of its own and four which
    // belong to the following instruction.
    final var v1Spill = Discriminator.createDiscriminator(v1Data, v1Parsed[0].offset(), 8);
    assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, v1Spill.data());
    final var v1Matches = v1Skeleton.filterInstructions(v1Accounts, v1Spill);
    assertEquals(1, v1Matches.length);
    // The matched instruction is shorter than the discriminator it supposedly begins with.
    assertEquals(4, v1Matches[0].len());
    assertFalse(v1Matches[0].beginsWith(v1Spill.data()));
    assertEquals(1, v1Skeleton.filterInstructionsWithoutAccounts(v1Spill).length);

    final var legacyTx = Transaction.createTx(feePayer, List.of(ix1, ix2));
    legacyTx.setRecentBlockHash(BLOCK_HASH);
    final byte[] legacyData = legacyTx.serialized();
    final var legacySkeleton = TransactionSkeleton.deserializeSkeleton(legacyData);
    final var legacyAccounts = legacySkeleton.parseAccounts();
    final var legacyParsed = legacySkeleton.parseInstructions(legacyAccounts);
    assertEquals(4, legacyParsed[0].len());

    final var legacySpill = Discriminator.createDiscriminator(legacyData, legacyParsed[0].offset(), 8);
    final var legacyMatches = legacySkeleton.filterInstructions(legacyAccounts, legacySpill);
    assertEquals(1, legacyMatches.length);
    assertEquals(4, legacyMatches[0].len());
    assertFalse(legacyMatches[0].beginsWith(legacySpill.data()));
  }
}
