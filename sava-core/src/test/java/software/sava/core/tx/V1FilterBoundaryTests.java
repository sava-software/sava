package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.meta.AccountMeta.*;
import static software.sava.core.programs.Discriminator.toDiscriminator;
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;
import static software.sava.core.tx.TxBuilderImpl.V1_INSTRUCTION_HEADER_LENGTH;
import static software.sava.core.tx.V1TransactionSkeleton.V1_ACCOUNTS_OFFSET;

/// Pins the exact arithmetic boundaries of the v1 instruction walks.
///
/// The v1 wire format packs every fixed-width `InstructionHeader` contiguously and then every
/// payload contiguously behind them, so each view drives a cursor over the payloads that is only
/// ever corroborated by the headers it already read. An off-by-one in that cursor, in the loop
/// bound that walks the headers, or in the account-index bound does not fail loudly: it silently
/// reports a *different, plausible* instruction — a neighbouring account, the fee payer, or an
/// instruction assembled out of another one's payload bytes.
///
/// Every fixture here is built through the public [TxBuilder] and then corrupted at a single named
/// byte, so each assertion below distinguishes the current arithmetic from one specific
/// perturbation of it.
final class V1FilterBoundaryTests {

  private static PublicKey key(final int seed) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) seed);
    return Signer.createFromPrivateKey(privateKey).publicKey();
  }

  private static byte[] blockHash() {
    final byte[] blockHash = new byte[Transaction.BLOCK_HASH_LENGTH];
    for (int b = 0; b < blockHash.length; ++b) {
      blockHash[b] = (byte) (b + 71);
    }
    return blockHash;
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
    tx.setRecentBlockHash(blockHash());
    return tx.serialized();
  }

  /// The first byte after the fixed-width header block, i.e. where the first instruction's payload
  /// — its account indexes, then its data — begins.
  private static int firstInstructionCursor(final TransactionSkeleton skeleton) {
    return skeleton.instructionsOffset() + (skeleton.numInstructions() * V1_INSTRUCTION_HEADER_LENGTH);
  }

  /// Rewrites the account index the first instruction holds at `position`, leaving every other byte,
  /// and therefore every declared length, untouched.
  private static byte[] withFirstInstructionAccountIndex(final byte[] data,
                                                         final int position,
                                                         final int accountIndex) {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final int offset = firstInstructionCursor(skeleton) + position;
    final byte[] corrupted = Arrays.copyOf(data, data.length);
    corrupted[offset] = (byte) accountIndex;
    return corrupted;
  }

  // -----------------------------------------------------------------------------------------
  // The account-index bound: `accountIndex < accounts.length`
  // -----------------------------------------------------------------------------------------

  /// Pins `V1TransactionSkeleton#parseInstructions`'s
  /// `ixAccounts[a] = accountIndex < accounts.length ? accounts[accountIndex] : null`.
  ///
  /// The account indexes are untrusted wire bytes while `accounts` is the caller's array, so the two
  /// need not agree. An index sitting *exactly* on `accounts.length` is the first one outside it:
  /// the bound must stay exclusive, or that index reads one past the end of the caller's array and
  /// the whole parse dies with a bare `ArrayIndexOutOfBoundsException` instead of reporting the one
  /// account it could not resolve as null.
  @Test
  void parseInstructionsTreatsAnIndexEqualToTheAccountCountAsUnresolvable() {
    final var feePayer = key(11);
    final var signerB = key(22);
    final var other = key(33);
    final var program = SolanaAccounts.MAIN_NET.systemProgram();

    final byte[] data = serializedV1(feePayer,
        ix(program, List.of(createWritableSigner(signerB), createWrite(other)), 1, 2, 3, 4)
    );
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts();
    // feePayer, signerB, other, systemProgram.
    assertEquals(4, accounts.length);
    assertEquals(accounts.length, skeleton.numIncludedAccounts());

    // Uncorrupted, both indexes resolve.
    final var parsed = skeleton.parseInstructions(accounts);
    assertEquals(2, parsed[0].accounts().size());
    assertEquals(signerB, parsed[0].accounts().get(0).publicKey());
    assertEquals(other, parsed[0].accounts().get(1).publicKey());

    // The last in-bounds index still resolves: the bound is not merely "reject everything large".
    final byte[] lastInBounds = withFirstInstructionAccountIndex(data, 1, accounts.length - 1);
    final var inBoundsSkeleton = TransactionSkeleton.deserializeSkeleton(lastInBounds);
    final var inBoundsAccounts = inBoundsSkeleton.parseAccounts();
    final var inBoundsParsed = inBoundsSkeleton.parseInstructions(inBoundsAccounts);
    assertNotNull(inBoundsParsed[0].accounts().get(1));
    assertEquals(
        inBoundsAccounts[accounts.length - 1].publicKey(),
        inBoundsParsed[0].accounts().get(1).publicKey()
    );

    // ...and the first out-of-bounds index, exactly on the bound, is refused. SIMD-0385 makes this
    // a sanitization failure, so the transaction cannot execute; v1 reports that rather than
    // handing back a null inside the instruction's account list for the caller to trip over.
    final byte[] onTheBound = withFirstInstructionAccountIndex(data, 1, accounts.length);
    final var boundSkeleton = TransactionSkeleton.deserializeSkeleton(onTheBound);
    final var boundAccounts = boundSkeleton.parseAccounts();
    assertEquals(accounts.length, boundAccounts.length);

    assertEquals(
        "Instruction account index 4 is outside the 4 accounts of this transaction.",
        assertThrowsExactly(
            IndexOutOfBoundsException.class,
            () -> boundSkeleton.parseInstructions(boundAccounts)
        ).getMessage()
    );
  }

  // -----------------------------------------------------------------------------------------
  // The filter cursor: `data[cursor + a]` and `accountIndex < accounts.length`
  // -----------------------------------------------------------------------------------------

  /// Pins the two arithmetic details of `V1TransactionSkeleton#filterInstructions`'s account loop:
  /// `accountIndex = data[cursor + a]` and `accountIndex < accounts.length`.
  ///
  /// Unlike `parseInstructions`, the filter loop does not advance the cursor as it reads — it indexes
  /// *forward* from a cursor which still points at the first account index of the matched
  /// instruction. Reading backwards instead lands on the tail of the preceding bytes: for the first
  /// instruction that is the last byte of the final `InstructionHeader`, the high byte of a payload
  /// length, which for any instruction shorter than 256 bytes is zero — i.e. account index 0, the
  /// fee payer. A filtered instruction would then silently name the fee payer where it should name
  /// its second account.
  @Test
  void filterInstructionsReadsItsAccountIndexesForwardFromTheCursor() {
    final var feePayer = key(11);
    final var signerB = key(22);
    final var other = key(33);
    final var program = SolanaAccounts.MAIN_NET.systemProgram();

    final byte[] data = serializedV1(feePayer,
        ix(program, List.of(createWritableSigner(signerB), createWrite(other)), 1, 2, 3, 4)
    );
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts();
    final var discriminator = toDiscriminator(1, 2, 3, 4);

    final int cursor = firstInstructionCursor(skeleton);
    // What a backwards read of the second account index would find: the high byte of the last
    // header's payload length, which is the fee payer's index.
    assertEquals(0, data[cursor - 1] & 0xFF);
    assertEquals(feePayer, accounts[0].publicKey());
    // ...and what the forward read must find instead.
    assertNotEquals(0, data[cursor + 1] & 0xFF);

    final var filtered = skeleton.filterInstructions(accounts, discriminator);
    assertEquals(1, filtered.length);
    assertEquals(2, filtered[0].accounts().size());
    assertEquals(signerB, filtered[0].accounts().get(0).publicKey());
    assertEquals(other, filtered[0].accounts().get(1).publicKey(),
        "the second account index is read forward from the cursor, not backward"
    );
    assertNotEquals(feePayer, filtered[0].accounts().get(1).publicKey());
    // The filter and the parse views must agree account for account.
    assertEquals(skeleton.parseInstructions(accounts)[0], filtered[0]);

    // The same account-index bound the parse view has, on the same exclusive boundary: the second
    // index is moved exactly onto accounts.length.
    final byte[] onTheBound = withFirstInstructionAccountIndex(data, 1, accounts.length);
    final var boundSkeleton = TransactionSkeleton.deserializeSkeleton(onTheBound);
    final var boundAccounts = boundSkeleton.parseAccounts();

    assertEquals(
        "Instruction account index 4 is outside the 4 accounts of this transaction.",
        assertThrowsExactly(
            IndexOutOfBoundsException.class,
            () -> boundSkeleton.filterInstructions(boundAccounts, discriminator)
        ).getMessage(),
        "the filter view enforces the same bound as the parse view"
    );

    // One below the bound still resolves, so the bound is exclusive rather than absent.
    final byte[] lastInBounds = withFirstInstructionAccountIndex(data, 1, accounts.length - 1);
    final var inBoundsSkeleton = TransactionSkeleton.deserializeSkeleton(lastInBounds);
    final var inBoundsAccounts = inBoundsSkeleton.parseAccounts();
    final var inBoundsFiltered = inBoundsSkeleton.filterInstructions(inBoundsAccounts, discriminator);
    assertEquals(1, inBoundsFiltered.length);
    assertEquals(
        inBoundsAccounts[accounts.length - 1].publicKey(),
        inBoundsFiltered[0].accounts().get(1).publicKey()
    );
  }

  // -----------------------------------------------------------------------------------------
  // The header walk: `i < numInstructions`
  // -----------------------------------------------------------------------------------------

  /// Pins the loop bound of both filter walks: `for (int i = 0; i < numInstructions; ...)`.
  ///
  /// The header block is exactly `numInstructions` headers wide and the payloads begin immediately
  /// behind it, so a walk that runs one iteration too long does not read padding — it reads the
  /// first four payload bytes *as an `InstructionHeader`* and filters an instruction that does not
  /// exist. This fixture's only instruction carries no accounts and leads with `0xFF`, so those four
  /// bytes decode to program account index 255, which the eager `requireIncludedProgramAccount`
  /// rejects. Both filter views must therefore complete without touching them.
  @Test
  void theFilterHeaderWalksStopAtTheLastInstructionHeader() {
    final var feePayer = key(11);
    final var program = SolanaAccounts.MAIN_NET.systemProgram();

    final byte[] data = serializedV1(feePayer, ix(program, List.of(), 0xFF, 1, 2, 3));
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertEquals(1, skeleton.numInstructions());
    // feePayer and the system program.
    assertEquals(2, skeleton.numIncludedAccounts());

    // The bytes one header past the end of the header block, which are the first payload bytes.
    final int oneHeaderPastTheEnd = firstInstructionCursor(skeleton);
    assertEquals(0xFF, data[oneHeaderPastTheEnd] & 0xFF);
    assertTrue(0xFF >= skeleton.numIncludedAccounts(),
        "the payload's first byte must be an unusable program account index"
    );

    final var accounts = skeleton.parseAccounts();
    final var discriminator = toDiscriminator(0xFF, 1, 2, 3);

    final var filtered = assertDoesNotThrow(() -> skeleton.filterInstructions(accounts, discriminator));
    assertEquals(1, filtered.length);
    assertEquals(program, filtered[0].programId().publicKey());
    assertTrue(filtered[0].accounts().isEmpty());
    assertArrayEquals(new byte[]{(byte) 0xFF, 1, 2, 3}, filtered[0].copyData());

    final var withoutAccounts = assertDoesNotThrow(
        () -> skeleton.filterInstructionsWithoutAccounts(discriminator)
    );
    assertEquals(1, withoutAccounts.length);
    assertEquals(program, withoutAccounts[0].programId().publicKey());
    assertArrayEquals(new byte[]{(byte) 0xFF, 1, 2, 3}, withoutAccounts[0].copyData());

    // A discriminator matching nothing walks every header too, and must stop in the same place.
    final var noMatches = toDiscriminator(9, 9, 9, 9);
    assertEquals(0, assertDoesNotThrow(() -> skeleton.filterInstructions(accounts, noMatches)).length);
    assertEquals(0, assertDoesNotThrow(() -> skeleton.filterInstructionsWithoutAccounts(noMatches)).length);

    // Negative control: a header whose program account index really is 255 — the value those first
    // payload bytes would supply — is diagnosed, so an over-long walk could not have returned the
    // arrays above.
    final var withPayloadAsHeader = TransactionSkeleton.deserializeSkeleton(withProgramIdIndex(data, 0, 0xFF));
    assertEquals(
        "Program account index 255 is outside the 2 included accounts.",
        assertThrows(
            IndexOutOfBoundsException.class,
            () -> withPayloadAsHeader.filterInstructions(accounts, discriminator)
        ).getMessage()
    );
    assertEquals(
        "Program account index 255 is outside the 2 included accounts.",
        assertThrows(
            IndexOutOfBoundsException.class,
            () -> withPayloadAsHeader.filterInstructionsWithoutAccounts(discriminator)
        ).getMessage()
    );
  }

  /// Rewrites the program id index of an instruction's header, which is its first byte.
  private static byte[] withProgramIdIndex(final byte[] data,
                                           final int instructionIndex,
                                           final int programIdIndex) {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final byte[] corrupted = Arrays.copyOf(data, data.length);
    corrupted[skeleton.instructionsOffset() + (instructionIndex * V1_INSTRUCTION_HEADER_LENGTH)] = (byte) programIdIndex;
    return corrupted;
  }

  // -----------------------------------------------------------------------------------------
  // BaseTransactionSkeleton#invokedProgramAccount
  // -----------------------------------------------------------------------------------------

  /// Pins `BaseTransactionSkeleton#invokedProgramAccount`'s
  /// `account.invoked() ? account : createInvoked(account.publicKey())`.
  ///
  /// The fallback rebuilds the meta from the public key alone, which is correct only because the
  /// account it replaces carried nothing else worth keeping. When the caller's array already marks
  /// the program invoked, the meta must be returned untouched: an account that is *both* invoked as
  /// one instruction's program and written by another arrives here as an
  /// `AccountMetaInvokedAndWrite`, and rebuilding it from the public key silently demotes it to a
  /// read-only invoked meta — the transaction then names its own program as read-only and the
  /// runtime rejects any write the program performs on it.
  @Test
  void anInvokedAndWrittenProgramKeepsBothFlagsThroughParseInstructions() {
    final var feePayer = key(11);
    final var program = key(44);
    final var systemProgram = SolanaAccounts.MAIN_NET.systemProgram();

    // The first instruction writes the account which the second invokes as its program.
    final byte[] data = serializedV1(feePayer,
        ix(systemProgram, List.of(createWrite(program)), 1, 1, 1, 1),
        ix(program, List.of(), 2, 2, 2, 2)
    );
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var wireAccounts = skeleton.parseAccounts();
    // feePayer, the written-and-invoked program, the system program.
    assertEquals(3, wireAccounts.length);

    // Resolve the accounts the way a caller holding both instructions does: merging every use of an
    // account into one meta. That is the only way an invoked account also carries the write flag.
    final var merged = AccountMeta.createAccountsMap(wireAccounts.length, feePayer);
    for (final var instruction : skeleton.parseInstructions(wireAccounts)) {
      instruction.mergeAccounts(merged);
    }
    final var resolved = new AccountMeta[wireAccounts.length];
    for (int a = 0; a < wireAccounts.length; ++a) {
      resolved[a] = merged.getOrDefault(wireAccounts[a].publicKey(), wireAccounts[a]);
    }

    int programIndex = -1;
    for (int a = 0; a < resolved.length; ++a) {
      if (program.equals(resolved[a].publicKey())) {
        programIndex = a;
      }
    }
    assertTrue(programIndex > 0, "the program must be one of the included accounts");
    // The input to parseInstructions: the program is already invoked, and it is writable.
    assertTrue(resolved[programIndex].invoked());
    assertTrue(resolved[programIndex].write());

    final var parsed = skeleton.parseInstructions(resolved);
    assertEquals(2, parsed.length);
    final var programMeta = parsed[1].programId();
    assertEquals(program, programMeta.publicKey());
    assertTrue(programMeta.invoked());
    assertTrue(programMeta.write(),
        "an already invoked program meta must be carried through, not rebuilt from its public key"
    );
    assertSame(resolved[programIndex], programMeta);
    assertNotEquals(createInvoked(program), programMeta);

    // What the rebuild-always fallback would have produced, for contrast: the same key, invoked,
    // but no longer writable.
    assertFalse(createInvoked(program).write());
  }

  // -----------------------------------------------------------------------------------------
  // V1TransactionSkeleton#requireSignatureBlockOffset
  // -----------------------------------------------------------------------------------------

  /// A hand-built v1 buffer holding nothing but a header, an empty `TransactionConfigMask` and
  /// `numSigners` signature slots: no addresses, no instructions, no `ConfigValues`. Its message is
  /// therefore exactly `V1_ACCOUNTS_OFFSET` bytes long, which is the smallest a v1 message can be.
  private static byte[] minimalV1Buffer(final int numSigners, final int signatureFill) {
    final byte[] data = new byte[V1_ACCOUNTS_OFFSET + (numSigners * SIGNATURE_LENGTH)];
    data[0] = TxBuilderImpl.V1_VERSION_BYTE;
    data[1] = (byte) numSigners;
    // numReadonlySignedAccounts, numReadonlyUnsignedAccounts, the config mask, the block hash,
    // NumInstructions and NumAddresses are all left zero.
    Arrays.fill(data, V1_ACCOUNTS_OFFSET, data.length, (byte) signatureFill);
    return data;
  }

  /// Pins `V1TransactionSkeleton#requireSignatureBlockOffset`'s `signaturesOffset < V1_ACCOUNTS_OFFSET`
  /// bound as exclusive.
  ///
  /// The guard exists because the signature count is an untrusted header byte, so a count too large
  /// for the buffer drives the implied offset backwards into — or past the front of — the message.
  /// `V1_ACCOUNTS_OFFSET` is the first offset that is not inside the fixed header, i.e. the shortest
  /// possible v1 message: an address-less, instruction-less message ends exactly there. Such a
  /// buffer is degenerate but well formed, and `messageEnd` corroborates it, so it must be accepted.
  /// Tightening the bound to `<=` would reject a buffer whose signature block starts precisely where
  /// its message ends, which is the one thing the guard is supposed to certify.
  @Test
  void aV1MessageEndingExactlyAtTheAccountsOffsetIsAccepted() {
    for (final int numSigners : new int[]{1, 2, 12}) {
      final byte[] data = minimalV1Buffer(numSigners, 0x5A);
      assertEquals(V1_ACCOUNTS_OFFSET + (numSigners * SIGNATURE_LENGTH), data.length);

      // The implied signature block starts exactly on the bound, and the message ends there.
      final int signaturesOffset = data.length - (numSigners * SIGNATURE_LENGTH);
      assertEquals(V1_ACCOUNTS_OFFSET, signaturesOffset);
      assertEquals(signaturesOffset, V1TransactionSkeleton.messageEnd(data));

      final int accepted = assertDoesNotThrow(() -> V1TransactionSkeleton.requireSignatureBlockOffset(data));
      assertEquals(V1_ACCOUNTS_OFFSET, accepted,
          "a message ending exactly at the accounts offset must be accepted"
      );

      // ...and the public raw-byte helpers reach the same offset, so the id is read from the
      // signature block rather than diagnosed as an impossible layout.
      final var expectedId = Base58.encode(data, V1_ACCOUNTS_OFFSET, V1_ACCOUNTS_OFFSET + SIGNATURE_LENGTH);
      assertEquals(expectedId, assertDoesNotThrow(() -> Transaction.getBase58Id(data)));
      assertArrayEquals(
          Arrays.copyOfRange(data, V1_ACCOUNTS_OFFSET, V1_ACCOUNTS_OFFSET + SIGNATURE_LENGTH),
          Transaction.getId(data)
      );
    }
  }

  /// The other side of the same bound: one byte short of the minimum, the implied signature block
  /// starts inside the fixed header and must be rejected.
  @Test
  void aV1BufferOneByteBelowTheAccountsOffsetIsRejected() {
    final byte[] tooShort = Arrays.copyOf(minimalV1Buffer(1, 0x5A), V1_ACCOUNTS_OFFSET + SIGNATURE_LENGTH - 1);
    assertEquals(V1_ACCOUNTS_OFFSET - 1, tooShort.length - SIGNATURE_LENGTH);

    final var expected = String.format(
        "A v1 transaction of %d bytes cannot hold the 1 signatures its header declares.",
        tooShort.length
    );
    assertEquals(expected, assertThrowsExactly(
        IllegalArgumentException.class,
        () -> V1TransactionSkeleton.requireSignatureBlockOffset(tooShort)
    ).getMessage());
    assertEquals(expected, assertThrowsExactly(
        IllegalArgumentException.class,
        () -> Transaction.getBase58Id(tooShort)
    ).getMessage());
  }
}
