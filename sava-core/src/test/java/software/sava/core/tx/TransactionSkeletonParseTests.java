package software.sava.core.tx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.CompactU16Encoding;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Cross-method invariants for the [TransactionSkeleton] parse API: each narrow accessor
/// must agree with the broad [TransactionSkeleton#parseAccounts()] /
/// [TransactionSkeleton#parseInstructions(AccountMeta[])] views that the serialization
/// tests already pin against real transactions. Mutation testing showed these accessors
/// had no coverage at all, so a wrong offset or a swapped read/write split in any of them
/// was invisible.
final class TransactionSkeletonParseTests {

  /// Real main-net vote transaction: legacy, 1 signature, 3 accounts, 1 instruction.
  private static final String LEGACY_TX = "AZ10DEU4Cx/7Wz0hfgSBv611o/M0IbBBiHEz1+u8Def5X5olVQBPCJwAU7vAe3cHAWgJCBFZlkT5F3y6lqKfjwsBAAEDG/DgbsI0C9boBnk4XisMmoQA7OtSuLN0M3UeIQzH3GTsrOfsoxxvoBrDjsS+XKKxY6f1+u+wvdkfXobqS0TzIwdhSB01dHS7fE12JOvTvbPYNV5z0RBD/A2jU4AAAAAAuBYPDxl6rC7XQsWlhL08FOLnN+4cFCZIP9ZUVS7jJUwBAgIBAJQBDgAAACQWyhkAAAAAHwEfAR4BHQEcARsBGgEZARgBFwEWARUBFAETARIBEQEQAQ8BDgENAQwBCwEKAQkBCAEHAQYBBQEEAQMBAgEBhDS9Ak3W+EXJYB7nN8pC8/9LHbmVTRk2Zw58n7nYC78BswtVagAAAACE2G8ZeCyZEzWLLSFSoYHv4I8HDiH47L/BH7C9cJJg3g==";

  /// Real main-net transaction: versioned, address-table lookups, 6 instructions.
  private static final String VERSIONED_TX = "ATgc2Iye/GlwnpSeIytu+tYkb2A+5VJhc1yui59+7/PMQSuywEqpb3k8wHCKnupEuC5fDTUjvGhASTEH5c90UACAAQAFEU4rs4al2vatnKR6MtsLLzl+Q24T1Y5kkYBmPhrq9O/VzcyvadLTPMXTLHJ2IKteqvqoQAgRH4dVHOW+cw1EkNOJB31VpbsTMHY+t2f1XsB3tBoNB1994dc/uso8Y9VUcRCcPGXQaDMBtOvEnG0Lyr4Lf68erOMMjG6weDn4HuIS6tSjkUAFDNLqypEZqieck8DZMKBobFJb3fYlMJjWpjHvHv25qj1olz/ZenFlAVmw6stGZYC5aF5nQ9ZqQr8vxXTXpuq5/UeOzPqvqL7sJuBwFgO//vEZG9uw6edrxAd2vInnwNHlA4uvk7TwFNJd9xWnndlfBJ5f9fX36m+JwJ9fAlkt3jAFytFpv8wnPC/6I0tpd+F+Bw3UOdTTA8X8HR7XL/DvxwiqYIadWSBAIms1hbo9KoaOYES91ZtNIF/jeSWG+N64PtIqGyqU3OdPOEd0TTjj79CJx+HICgFkwRrNq0B12gG6uYd+a79dybCsJPRSedzSl8R6nwJYXSLJGQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAjJclj04kifG7PRApFI4NgwtaE5na/xCEBI572Nvp+FkDBkZv5SEXMv/srbpyw5vnvIzlu8X3EmssQ5s6QAAAAJWBt/6PKcF0R86zH0ytUdAc7K5LbdbpdsVcWwvkMUGK86EED9glv4WMHdJchvP2ZZvDPQq6PniaYqUsrsmZ94ETjVRi2k6UWSxzbmoMl/cGeYhlpwGEqRE3YD3BBue3swYOAAUCgE8SAA4ABQEAAAIADgAJAyKiAAAAAAAADwYdDAABFg0IllUsHJUO0hoPHQACAwwREhMUARUWFx0eGBkEHxogGxwFBgcICQoLigEyEHMzqHo5LQIAAAACAAAAAAECAAAAAgEDAAAABBIAAAAEBQQGBwIICQoLBAAMDA0ODxABCQAAABEMEhMUFQgCAAQSAAAABBYEFxgCCAoJGQQADAwNGhscCAAAABAnTB2IE8QJ6ANkAAoAAQC0ZeZpAAAAAFDDAAAAAAAAAAAAAAAAAAAAAAAAAAAQAB9Qb3dlcmVkIGJ5IGJsb1hyb3V0ZSBUcmFkZXIgQXBpAonsVzlUh3H7+XOmaklk0KWZm+wt34ECwzGrxcB6Sb7VCQRLSE8FSUpRTgQMAgsHmoTIVF9hkZWFmpxCqc8zcavo9Yu6S8ysQBz59/7iw1ADVllVAA==";

  private static final AtomicInteger KEY_SEED = new AtomicInteger();

  @BeforeEach
  void resetKeySeed() {
    KEY_SEED.set(0);
  }

  /// Fixture signers come from a counter reset before each test, so a key depends on neither
  /// execution order nor how many tests ran before it. A generated key pair makes a PIT kill
  /// non-reproducible: a mutant that misreads an offset can land on a fixture byte that happens to
  /// equal an asserted constant, surviving on some runs and dying on others. That is not
  /// hypothetical here — `TransactionRecord.numAccounts()` reads its short-vector at a
  /// version-dependent offset, and forcing the versioned offset on a legacy transaction reads the
  /// fee payer's first public-key byte instead.
  private static Signer nextSigner() {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) KEY_SEED.incrementAndGet());
    return Signer.createFromPrivateKey(privateKey);
  }

  private static TransactionSkeleton skeleton(final String base64) {
    return TransactionSkeleton.deserializeSkeleton(Base64.getDecoder().decode(base64));
  }

  /// The three message-header counts are `u8` on the wire, like the version byte they sit
  /// next to. Read as signed bytes, a value past `0x7F` came back negative, and
  /// `numSignatures - numReadonlySignedAccounts` then walked the writable-signer loop
  /// past the accounts it was slicing instead of the count being reported as read.
  @Test
  void headerCountsPastTheSignBitReadUnsigned() {
    final byte[] data = new byte[1 + Transaction.SIGNATURE_LENGTH + 1 + 1 + 1 + 1 + 1
        + (PublicKey.PUBLIC_KEY_LENGTH * 3) + Transaction.BLOCK_HASH_LENGTH + 1];
    int o = 0;
    data[o++] = 1;                                   // compact-u16: one signature blob
    o += Transaction.SIGNATURE_LENGTH;
    data[o++] = (byte) 0x80;                         // versioned marker, version 0
    data[o++] = (byte) 0x80;                         // numRequiredSignatures
    data[o++] = (byte) 0x81;                         // numReadonlySignedAccounts
    data[o++] = (byte) 0xFF;                         // numReadonlyUnsignedAccounts
    data[o++] = 3;                                   // compact-u16 numIncludedAccounts
    o += PublicKey.PUBLIC_KEY_LENGTH * 3;
    o += Transaction.BLOCK_HASH_LENGTH;
    data[o] = 0;                                     // compact-u16 numInstructions

    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertEquals(0, skeleton.version());
    assertEquals(128, skeleton.numSignatures());
    assertEquals(129, skeleton.numReadonlySignedAccounts());
    assertEquals(255, skeleton.numReadonlyUnsignedAccounts());
  }

  private void assertSignerSplit(final TransactionSkeleton skeleton) {
    final var accounts = skeleton.parseAccounts();
    final var signerAccounts = skeleton.parseSignerAccounts();
    final var nonSignerAccounts = skeleton.parseNonSignerAccounts();

    // the two halves must partition parseAccounts() exactly, in order
    assertEquals(skeleton.numSignatures(), signerAccounts.length);
    assertEquals(accounts.length - skeleton.numSignatures(), nonSignerAccounts.length);
    assertEquals(skeleton.numIncludedAccounts(), signerAccounts.length + nonSignerAccounts.length);
    for (int i = 0; i < signerAccounts.length; ++i) {
      assertEquals(accounts[i], signerAccounts[i], "signer account " + i);
    }
    for (int i = 0; i < nonSignerAccounts.length; ++i) {
      assertEquals(accounts[signerAccounts.length + i], nonSignerAccounts[i], "non signer account " + i);
    }

    // the pubkey views must agree with the meta views
    final var signerKeys = skeleton.parseSignerPublicKeys();
    final var nonSignerKeys = skeleton.parseNonSignerPublicKeys();
    assertEquals(signerAccounts.length, signerKeys.length);
    assertEquals(nonSignerAccounts.length, nonSignerKeys.length);
    for (int i = 0; i < signerKeys.length; ++i) {
      assertEquals(signerAccounts[i].publicKey(), signerKeys[i], "signer key " + i);
    }
    for (int i = 0; i < nonSignerKeys.length; ++i) {
      assertEquals(nonSignerAccounts[i].publicKey(), nonSignerKeys[i], "non signer key " + i);
    }

    // the fee payer is always the first signer
    assertEquals(accounts[0].publicKey(), skeleton.feePayer());
    assertTrue(accounts[0].feePayer(), "first account must be the fee payer");
  }

  private void assertWritableSplit(final TransactionSkeleton skeleton) {
    // read-only unsigned accounts are the tail of the non-signer accounts; everything
    // before them is writable. A swapped bound here would silently mark a writable
    // account read-only, or worse.
    final var nonSignerAccounts = skeleton.parseNonSignerAccounts();
    final int readOnly = skeleton.numReadonlyUnsignedAccounts();
    for (int i = 0; i < nonSignerAccounts.length - readOnly; ++i) {
      assertTrue(nonSignerAccounts[i].write(), "non signer account " + i + " must be writable");
    }
    for (int i = nonSignerAccounts.length - readOnly; i < nonSignerAccounts.length; ++i) {
      assertFalse(nonSignerAccounts[i].write(), "non signer account " + i + " must be read only");
    }
  }

  private void assertProgramAccounts(final TransactionSkeleton skeleton, final Instruction[] instructions) {
    final var programs = skeleton.parseProgramAccounts();
    assertEquals(skeleton.numInstructions(), programs.length);
    assertEquals(instructions.length, programs.length);
    for (int i = 0; i < programs.length; ++i) {
      assertEquals(instructions[i].programId().publicKey(), programs[i], "program account " + i);
    }
  }

  private void assertSerializedInstructionsLength(final TransactionSkeleton skeleton,
                                                  final Instruction[] instructions) {
    // independent recomputation of the wire length: per instruction one program-index
    // byte, then a compact-u16 account count plus one index byte each, then a compact-u16
    // data length plus the data
    int expected = 0;
    for (final var ix : instructions) {
      final int numAccounts = ix.accounts().size();
      expected += 1
          + CompactU16Encoding.getByteLen(numAccounts) + numAccounts
          + CompactU16Encoding.getByteLen(ix.len()) + ix.len();
    }
    assertEquals(expected, skeleton.serializedInstructionsLength());
  }

  @Test
  void legacyAccountViewsAgree() {
    final var skeleton = skeleton(LEGACY_TX);
    assertTrue(skeleton.isLegacy());
    assertEquals(1, skeleton.numSignatures());
    assertEquals(3, skeleton.numIncludedAccounts());

    assertSignerSplit(skeleton);
    assertWritableSplit(skeleton);

    // a legacy transaction indexes no table accounts
    assertEquals(skeleton.numIncludedAccounts(), skeleton.numAccounts());
    assertEquals(0, skeleton.numIndexedAccounts());

    final var instructions = skeleton.parseInstructions(skeleton.parseAccounts());
    assertProgramAccounts(skeleton, instructions);
    assertSerializedInstructionsLength(skeleton, instructions);
  }

  /// A program is invoked by definition, whichever accessor produced the instruction.
  /// The legacy header carries no invoked indexes, so `parseAccounts()` types every
  /// read-only account as read-only; `parseInstructions` must still hand back an invoked
  /// program, or it disagrees with its `filterInstructions` sibling and a rebuilt
  /// transaction sorts its accounts differently (`VO_META_COMPARATOR` ranks invoked ahead
  /// of other read-only accounts).
  private void assertProgramsAreInvoked(final TransactionSkeleton skeleton,
                                        final Instruction[] instructions) {
    for (final var ix : instructions) {
      assertTrue(ix.programId().invoked(), ix.programId().publicKey() + " must be invoked");
      assertFalse(ix.programId().signer(), "a program may not be a signer");
      assertFalse(ix.programId().feePayer(), "a program may not be the fee payer");
    }
    // every accessor that builds instructions must agree on the program meta
    final var withoutAccounts = skeleton.parseInstructionsWithoutAccounts();
    assertEquals(instructions.length, withoutAccounts.length);
    for (int i = 0; i < instructions.length; ++i) {
      assertEquals(instructions[i].programId(), withoutAccounts[i].programId(), "instruction " + i);
    }
  }

  @Test
  void legacyProgramAccountsAreInvoked() {
    final var skeleton = skeleton(LEGACY_TX);
    assertTrue(skeleton.isLegacy());
    final var instructions = skeleton.parseInstructions(skeleton.parseAccounts());
    assertProgramsAreInvoked(skeleton, instructions);

    // the account array itself keeps the header's read-only typing: a legacy header cannot
    // say which accounts are invoked, so only the instruction's own program meta knows
    final var accounts = skeleton.parseAccounts();
    final var programIndex = Arrays.asList(skeleton.parseNonSignerPublicKeys())
        .indexOf(instructions[0].programId().publicKey());
    assertTrue(programIndex >= 0, "the vote program is a non signer account");
    assertFalse(accounts[skeleton.numSignatures() + programIndex].invoked(),
        "legacy parseAccounts() has no invoked indexes to consult");
  }

  @Test
  void versionedProgramAccountsAreInvoked() {
    final var skeleton = skeleton(VERSIONED_TX);
    assertTrue(skeleton.isVersioned());
    assertProgramsAreInvoked(skeleton, skeleton.parseInstructionsWithoutTableAccounts());
  }

  @Test
  void blockHashAndIdViewsAgree() {
    final var skeleton = skeleton(LEGACY_TX);

    // the three block-hash views must describe the same 32 bytes
    final byte[] blockHash = skeleton.blockHash();
    assertEquals(Transaction.BLOCK_HASH_LENGTH, blockHash.length);
    assertArrayEquals(blockHash, Base58.decode(skeleton.base58BlockHash()));
    assertNotSame(skeleton.blockHash(), skeleton.blockHash(), "blockHash must hand out a copy");

    // a transaction built from the skeleton keeps the same block hash and id
    final var tx = skeleton.createTransaction();
    assertArrayEquals(blockHash, tx.recentBlockHash());
    assertEquals(skeleton.id(), tx.getBase58Id());
    assertArrayEquals(Base58.decode(skeleton.id()), tx.getId());
  }

  @Test
  void createTransactionOverloadsAgree() {
    final var skeleton = skeleton(LEGACY_TX);
    final var accounts = skeleton.parseAccounts();
    final var instructions = skeleton.parseInstructions(accounts);

    // every convenience overload must land on the same transaction as the explicit one
    final byte[] expected = skeleton.createTransaction(List.of(instructions)).serialized();
    assertArrayEquals(expected, skeleton.createTransaction().serialized());
    assertArrayEquals(expected, skeleton.createTransaction(accounts).serialized());
    assertArrayEquals(expected, skeleton.createTransaction(instructions).serialized());

    final var tx = skeleton.createTransaction();
    assertEquals(skeleton.feePayer(), tx.feePayer().publicKey());
    assertEquals(instructions.length, tx.instructions().size());
    assertEquals(Arrays.asList(instructions), tx.instructions());
    // a transaction rebuilt from a parsed skeleton re-serializes to the original message
    assertArrayEquals(Base64.getDecoder().decode(LEGACY_TX), tx.serialized());
  }

  @Test
  void createTransactionWithoutLookupTableFallsBack() {
    final var skeleton = skeleton(LEGACY_TX);
    // a null table is the no-table case, not a crash
    assertArrayEquals(skeleton.parseAccounts(), skeleton.parseAccounts((software.sava.core.accounts.lookup.AddressLookupTable) null));
    assertArrayEquals(
        skeleton.createTransaction().serialized(),
        skeleton.createTransaction((software.sava.core.accounts.lookup.AddressLookupTable) null).serialized()
    );
  }

  @Test
  void filterInstructionsByDiscriminator() {
    final var skeleton = skeleton(LEGACY_TX);
    final var instructions = skeleton.parseInstructions(skeleton.parseAccounts());
    assertEquals(1, instructions.length);

    // the real instruction's own leading bytes must select it, and a filtered instruction
    // must be indistinguishable from the parsed one — including the programId flags
    final var matching = instructions[0].wrapDiscriminator(4);
    final var found = skeleton.filterInstructionsWithoutTableAccounts(matching);
    assertEquals(1, found.length);
    assertEquals(instructions[0], found[0]);
    assertEquals(instructions[0].programId(), found[0].programId());

    // a discriminator that matches nothing selects nothing
    final byte[] noMatch = instructions[0].copyData();
    noMatch[0] = (byte) (noMatch[0] ^ 0xFF);
    assertEquals(0, skeleton.filterInstructionsWithoutTableAccounts(
        software.sava.core.programs.Discriminator.createDiscriminator(noMatch, 0, 4)).length);
  }

  /// Agave's legacy and v0 message sanitizers require `program_id_index` to resolve
  /// within the statically included account keys. Sava deliberately permits structural
  /// analysis of unsanitized messages, but every public instruction view must reject the
  /// same invalid index instead of interpreting the following blockhash bytes as a key.
  private static void assertEveryInstructionViewRejects(final TransactionSkeleton skeleton,
                                                        final AccountMeta[] accounts,
                                                        final software.sava.core.programs.Discriminator discriminator) {
    assertThrowsExactly(IndexOutOfBoundsException.class, () -> skeleton.parseInstructions(accounts));
    assertThrowsExactly(IndexOutOfBoundsException.class, skeleton::parseLegacyInstructions);
    assertThrowsExactly(IndexOutOfBoundsException.class, skeleton::parseProgramAccounts);
    assertThrowsExactly(IndexOutOfBoundsException.class, skeleton::parseInstructionsWithoutAccounts);
    assertThrowsExactly(IndexOutOfBoundsException.class, skeleton::parseInstructionsWithoutTableAccounts);
    assertThrowsExactly(IndexOutOfBoundsException.class, () -> skeleton.filterInstructions(accounts, discriminator));
    assertThrowsExactly(IndexOutOfBoundsException.class,
        () -> skeleton.filterInstructionsWithoutTableAccounts(discriminator));
    assertThrowsExactly(IndexOutOfBoundsException.class,
        () -> skeleton.filterInstructionsWithoutAccounts(discriminator));
  }

  @Test
  void outOfRangeProgramIndexIsRejectedByEveryInstructionView() {
    final byte[] data = new byte[
        1 + Transaction.SIGNATURE_LENGTH
            + 3
            + 1 + PublicKey.PUBLIC_KEY_LENGTH
            + Transaction.BLOCK_HASH_LENGTH
            + 1 + 4
    ];
    int o = 0;
    data[o++] = 1;                            // one signature
    o += Transaction.SIGNATURE_LENGTH;
    data[o++] = 1;                            // one required signature
    data[o++] = 0;                            // no read-only signers
    data[o++] = 0;                            // no read-only unsigned accounts
    data[o++] = 1;                            // one included account: index 0 only
    o += PublicKey.PUBLIC_KEY_LENGTH;
    Arrays.fill(data, o, o + Transaction.BLOCK_HASH_LENGTH, (byte) 0xA5);
    o += Transaction.BLOCK_HASH_LENGTH;
    data[o++] = 1;                            // one instruction
    data[o++] = 1;                            // invalid program index == account count
    data[o++] = 0;                            // no instruction accounts
    data[o++] = 1;                            // one instruction data byte
    data[o++] = 42;
    assertEquals(data.length, o);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts();
    final var expandedAccounts = Arrays.copyOf(accounts, 2);
    expandedAccounts[1] = AccountMeta.createInvoked(PublicKey.NONE);
    // The discriminator deliberately does not match. Filtering must validate every raw
    // program index rather than hiding malformed instructions that happen not to be selected.
    final var discriminator = software.sava.core.programs.Discriminator.toDiscriminator(43);

    // The unexpanded array used to throw ArrayIndexOutOfBoundsException by accident; require
    // the parser's deliberate, consistent exception instead of accepting that subclass.
    assertThrowsExactly(IndexOutOfBoundsException.class, () -> skeleton.parseInstructions(accounts));
    assertEveryInstructionViewRejects(skeleton, expandedAccounts, discriminator);
  }

  @Test
  void lookupTableProgramIndexIsRejectedByEveryInstructionView() {
    final byte[] data = Base64.getDecoder().decode(VERSIONED_TX);
    final var original = TransactionSkeleton.deserializeSkeleton(data);
    assertTrue(original.numAccounts() > original.numIncludedAccounts(),
        "fixture must contain lookup-table accounts");

    // A program id must come from the statically included keys, never from the first loaded
    // lookup-table account. Keep the expanded array populated to prove the static-key bound
    // is enforced instead of succeeding merely because an array lookup runs out of bounds.
    data[original.instructionsOffset()] = (byte) original.numIncludedAccounts();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = Arrays.copyOf(skeleton.parseAccounts(), skeleton.numAccounts());
    for (int i = skeleton.numIncludedAccounts(); i < accounts.length; ++i) {
      accounts[i] = AccountMeta.createInvoked(PublicKey.NONE);
    }

    assertEveryInstructionViewRejects(
        skeleton,
        accounts,
        software.sava.core.programs.Discriminator.toDiscriminator(
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF
        )
    );
  }

  private static void assertEveryMutableCreationRejects(final TransactionSkeleton skeleton,
                                                        final List<Instruction> instructions,
                                                        final String expectedMessage) {
    final byte[] before = skeleton.data().clone();

    final var withoutTables = assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(instructions)
    );
    assertEquals(expectedMessage, withoutTables.getMessage());
    assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(
            instructions,
            (software.sava.core.accounts.lookup.AddressLookupTable) null
        )
    );
    assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(
            instructions,
            new software.sava.core.accounts.meta.LookupTableAccountMeta[0]
        )
    );
    assertArrayEquals(before, skeleton.data(), "rejected conversion must not mutate parsed bytes");
  }

  /// Parsing remains deliberately permissive for offline analysis, but a mutable Transaction
  /// rewrites signature slots according to the message header. It is therefore unsafe unless
  /// the serialized slot prefix and required-signature header agree exactly.
  @Test
  void mismatchedSignatureLayoutsRemainReadableButCannotBecomeMutableTransactions() {
    final byte[] tooFewSlots = Base64.getDecoder().decode(LEGACY_TX);
    final int originalMessageOffset = CompactU16Encoding.getByteLen(tooFewSlots, 0)
        + (CompactU16Encoding.decode(tooFewSlots, 0) * Transaction.SIGNATURE_LENGTH);
    tooFewSlots[originalMessageOffset] = 2; // two required signers, one serialized slot

    final var tooFew = TransactionSkeleton.deserializeSkeleton(tooFewSlots);
    assertEquals(2, tooFew.numSignatures());
    final var tooFewInstructions = Arrays.asList(tooFew.parseInstructions(tooFew.parseAccounts()));
    assertEveryMutableCreationRejects(
        tooFew,
        tooFewInstructions,
        "Serialized signature count 1 does not match the message header's required signature count 2."
    );

    final byte[] original = Base64.getDecoder().decode(LEGACY_TX);
    final int prefixLength = CompactU16Encoding.getByteLen(original, 0);
    assertEquals(1, prefixLength, "fixture uses a one-byte signature-count prefix");
    assertEquals(1, CompactU16Encoding.decode(original, 0));
    final int messageOffset = prefixLength + Transaction.SIGNATURE_LENGTH;
    final byte[] extraSlot = new byte[original.length + Transaction.SIGNATURE_LENGTH];
    extraSlot[0] = 2;
    System.arraycopy(original, prefixLength, extraSlot, prefixLength, Transaction.SIGNATURE_LENGTH);
    System.arraycopy(
        original,
        messageOffset,
        extraSlot,
        prefixLength + (2 * Transaction.SIGNATURE_LENGTH),
        original.length - messageOffset
    );

    final var tooMany = TransactionSkeleton.deserializeSkeleton(extraSlot);
    assertEquals(1, tooMany.numSignatures());
    final var tooManyInstructions = Arrays.asList(tooMany.parseInstructions(tooMany.parseAccounts()));
    assertEveryMutableCreationRejects(
        tooMany,
        tooManyInstructions,
        "Serialized signature count 2 does not match the message header's required signature count 1."
    );
  }

  /// The versioned constructor paths receive the serialized slot count and message-header
  /// signer count as adjacent ints. Valid transactions normally make them equal, so use a
  /// deliberate mismatch to keep their order observable in the lookup-table branch.
  @Test
  void mismatchedSignatureLayoutWithLookupTablesPreservesCountOrder() {
    final byte[] data = Base64.getDecoder().decode(VERSIONED_TX);
    final int messageOffset = CompactU16Encoding.getByteLen(data, 0)
        + (CompactU16Encoding.decode(data, 0) * Transaction.SIGNATURE_LENGTH);
    assertEquals((byte) 0x80, data[messageOffset], "fixture must be a v0 transaction");
    data[messageOffset + 1] = 2; // two required signers, one serialized slot

    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertTrue(skeleton.lookupTableAccounts().length > 0, "fixture must enter the table branch");
    assertEquals(2, skeleton.numSignatures());
    assertEveryMutableCreationRejects(
        skeleton,
        List.of(),
        "Serialized signature count 1 does not match the message header's required signature count 2."
    );
  }

  /// The explicit zero-table branch has a separate record-construction site and therefore needs
  /// its own unequal-count fixture; equality-only fixtures cannot detect argument transposition.
  @Test
  void mismatchedSignatureLayoutWithZeroLookupTablesPreservesCountOrder() {
    final byte[] legacy = Base64.getDecoder().decode(LEGACY_TX);
    final int messageOffset = CompactU16Encoding.getByteLen(legacy, 0)
        + (CompactU16Encoding.decode(legacy, 0) * Transaction.SIGNATURE_LENGTH);
    final byte[] data = toVersionedWithoutTables(legacy, messageOffset);
    data[messageOffset + 1] = 2; // two required signers, one serialized slot

    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertEquals(0, skeleton.lookupTableAccounts().length, "fixture must enter the zero-table branch");
    assertEquals(2, skeleton.numSignatures());
    assertEveryMutableCreationRejects(
        skeleton,
        List.of(),
        "Serialized signature count 1 does not match the message header's required signature count 2."
    );
  }

  @Test
  void matchingSignatureCountsWithMultiBytePrefixesRemainReadableButCannotBecomeMutable() {
    final byte[] original = Base64.getDecoder().decode(LEGACY_TX);
    final byte[] nonCanonicalOne = new byte[original.length + 1];
    nonCanonicalOne[0] = (byte) 0x81;
    nonCanonicalOne[1] = 0;
    System.arraycopy(original, 1, nonCanonicalOne, 2, original.length - 1);

    final var aliased = TransactionSkeleton.deserializeSkeleton(nonCanonicalOne);
    assertEquals(1, aliased.numSignatures());
    assertEquals(1, aliased.parseSignerAccounts().length, "read-only analysis remains available");
    assertEveryMutableCreationRejects(
        aliased,
        Arrays.asList(aliased.parseInstructions(aliased.parseAccounts())),
        "Serialized signature count 1 uses a 2-byte prefix; mutable transactions require a one-byte prefix."
    );

    final int signatures = 128;
    final byte[] canonical128 = new byte[
        2 + (signatures * Transaction.SIGNATURE_LENGTH)
            + 1 + 3
            + 2 + (signatures * PublicKey.PUBLIC_KEY_LENGTH)
            + Transaction.BLOCK_HASH_LENGTH
            + 1 + 1
    ];
    int o = 0;
    canonical128[o++] = (byte) 0x80;
    canonical128[o++] = 1;
    o += signatures * Transaction.SIGNATURE_LENGTH;
    canonical128[o++] = (byte) 0x80; // versioned marker, version 0
    canonical128[o++] = (byte) signatures;
    canonical128[o++] = (byte) (signatures - 1);
    canonical128[o++] = 0;
    canonical128[o++] = (byte) 0x80;
    canonical128[o++] = 1;
    o += signatures * PublicKey.PUBLIC_KEY_LENGTH;
    o += Transaction.BLOCK_HASH_LENGTH;
    canonical128[o++] = 0; // no instructions
    canonical128[o++] = 0; // no lookup tables
    assertEquals(canonical128.length, o);

    final var wide = TransactionSkeleton.deserializeSkeleton(canonical128);
    assertTrue(wide.isVersioned());
    assertEquals(signatures, wide.numSignatures());
    assertEquals(signatures, wide.parseSignerAccounts().length, "read-only analysis remains available");
    assertEveryMutableCreationRejects(
        wide,
        List.of(),
        "Serialized signature count 128 uses a 2-byte prefix; mutable transactions require a one-byte prefix."
    );
  }

  @Test
  void readOnlySignersParseAsReadOnly() {
    // no real fixture here has a read-only signer, so build one: the split between
    // writable and read-only signers is a header-driven bound that nothing else pins
    final var feePayer = nextSigner();
    final var readOnlySigner = nextSigner();
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createReadOnlySigner(readOnlySigner.publicKey())),
        new byte[]{1, 2, 3, 4}
    );
    final var tx = Transaction.createTx(feePayer.publicKey(), ix);
    tx.setRecentBlockHash(new byte[Transaction.BLOCK_HASH_LENGTH]);
    tx.sign(List.of(feePayer, readOnlySigner));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertEquals(2, skeleton.numSignatures());
    assertEquals(1, skeleton.numReadonlySignedAccounts());

    final var signerAccounts = skeleton.parseSignerAccounts();
    assertEquals(2, signerAccounts.length);
    assertTrue(signerAccounts[0].feePayer(), "slot 0 must be the fee payer");
    assertTrue(signerAccounts[0].write(), "the fee payer is writable");
    assertTrue(signerAccounts[1].signer(), "slot 1 must be a signer");
    assertFalse(signerAccounts[1].write(), "slot 1 must be read only");
    assertEquals(readOnlySigner.publicKey(), signerAccounts[1].publicKey());

    assertSignerSplit(skeleton);
    assertWritableSplit(skeleton);
  }

  @Test
  void replaceInstructionSwapsInPlace() {
    final var skeleton = skeleton(LEGACY_TX);
    final var tx = skeleton.createTransaction();
    final var original = tx.instructions().getFirst();
    final var replacement = Instruction.createInstruction(
        original.programId(), original.accounts(), new byte[]{9, 9});

    final var replaced = tx.replaceInstruction(0, replacement);
    assertEquals(List.of(replacement), replaced.instructions());
    // the block hash rides along, and the receiver keeps its own instruction
    assertArrayEquals(tx.recentBlockHash(), replaced.recentBlockHash());
    assertEquals(List.of(original), tx.instructions());
  }

  /// Rewrites a legacy message as a v0 message that uses no address tables: set the
  /// version bit, then close the message with an empty lookup-table section. `createTx`
  /// only emits v0 when tables are supplied, but any wallet can send a v0 transaction that
  /// happens not to use them, so this is a real wire form nothing else here parses.
  private static byte[] toVersionedWithoutTables(final byte[] legacy, final int messageOffset) {
    final byte[] out = new byte[legacy.length + 2];
    System.arraycopy(legacy, 0, out, 0, messageOffset);
    out[messageOffset] = (byte) 0x80; // versioned bit, version 0
    System.arraycopy(legacy, messageOffset, out, messageOffset + 1, legacy.length - messageOffset);
    out[out.length - 1] = 0; // compact-u16 zero: no lookup tables
    return out;
  }

  private static byte[] versionedNoTableTx() {
    final var feePayer = nextSigner();
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWrite(feePayer.publicKey())),
        new byte[]{1, 2, 3, 4}
    );
    final var tx = Transaction.createTx(feePayer.publicKey(), ix);
    tx.setRecentBlockHash(new byte[Transaction.BLOCK_HASH_LENGTH]);
    tx.sign(feePayer);
    assertTrue(TransactionSkeleton.deserializeSkeleton(tx.serialized()).isLegacy(),
        "createTx without tables is expected to emit a legacy message");
    return toVersionedWithoutTables(tx.serialized(), ((TransactionRecord) tx).messageOffset());
  }

  @Test
  void versionedTransactionWithoutLookupTables() {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(versionedNoTableTx());

    assertTrue(skeleton.isVersioned());
    assertFalse(skeleton.isLegacy());
    assertEquals(0, skeleton.version());
    assertEquals(0, skeleton.lookupTableAccounts().length, "no tables were indexed");
    // with no table lookups every account is included in the message
    assertEquals(skeleton.numIncludedAccounts(), skeleton.numAccounts());
    assertEquals(0, skeleton.numIndexedAccounts());

    assertSignerSplit(skeleton);
    final var instructions = skeleton.parseInstructions(skeleton.parseAccounts());
    assertProgramAccounts(skeleton, instructions);
    assertSerializedInstructionsLength(skeleton, instructions);
    assertNotNull(skeleton.createTransaction());
  }

  private static byte[] versionedWithOutOfOrderProgramIndexes(final boolean includeLookupTableCount) {
    final byte[] data = new byte[
        1 + Transaction.SIGNATURE_LENGTH
            + 1 + 3
            + 1 + (3 * PublicKey.PUBLIC_KEY_LENGTH)
            + Transaction.BLOCK_HASH_LENGTH
            + 1 + (2 * 3)
            + (includeLookupTableCount ? 1 : 0)
        ];
    int o = 0;
    data[o++] = 1;                            // one signature
    o += Transaction.SIGNATURE_LENGTH;
    data[o++] = (byte) 0x80;                  // versioned marker, version 0
    data[o++] = 1;                            // one required signature
    data[o++] = 0;                            // no read-only signers
    data[o++] = 2;                            // both unsigned accounts are read-only
    data[o++] = 3;                            // payer plus two program accounts
    for (int key = 1; key <= 3; ++key) {
      data[o + PublicKey.PUBLIC_KEY_LENGTH - 1] = (byte) key;
      o += PublicKey.PUBLIC_KEY_LENGTH;
    }
    o += Transaction.BLOCK_HASH_LENGTH;
    data[o++] = 2;                            // two instructions
    data[o++] = 2;                            // first program index
    data[o++] = 0;                            // no instruction accounts
    data[o++] = 0;                            // no instruction data
    data[o++] = 1;                            // second program index: out of order
    data[o++] = 0;
    data[o++] = 0;
    if (includeLookupTableCount) {
      data[o++] = 0;                          // zero address-lookup tables
    }
    assertEquals(data.length, o);
    return data;
  }

  private static void assertOutOfOrderProgramAccountsAreInvoked(final byte[] data) {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts(List.of(), List.of());
    final var instructions = skeleton.parseInstructions(accounts);

    assertEquals(accounts[2].publicKey(), instructions[0].programId().publicKey());
    assertEquals(accounts[1].publicKey(), instructions[1].programId().publicKey());
    assertTrue(accounts[2].invoked(), "first instruction's program index 2 must be invoked");
    assertTrue(accounts[1].invoked(), "second instruction's program index 1 must be invoked");
  }

  /// A versioned message's lookup-table count does not control whether its instruction
  /// program accounts are invoked. The out-of-order indexes exercise the binary-search
  /// ordering requirement rather than succeeding accidentally in instruction order.
  @Test
  void zeroLookupTableCountMarksOutOfOrderProgramIndexesInvoked() {
    assertOutOfOrderProgramAccountsAreInvoked(versionedWithOutOfOrderProgramIndexes(true));
  }

  /// The parser accepts a v0 message ending after its instruction section; that early-return
  /// shape must preserve the same invoked-account contract as an explicit zero table count.
  @Test
  void missingLookupTableCountMarksOutOfOrderProgramIndexesInvoked() {
    assertOutOfOrderProgramAccountsAreInvoked(versionedWithOutOfOrderProgramIndexes(false));
  }

  @Test
  void versionedTransactionWithoutLookupTableSection() {
    // the same message with the trailing empty-table section chopped off: a truncated v0
    // message must still parse its instructions rather than run off the end
    final byte[] full = versionedNoTableTx();
    final byte[] truncated = Arrays.copyOfRange(full, 0, full.length - 1);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(truncated);
    assertTrue(skeleton.isVersioned());
    assertEquals(0, skeleton.lookupTableAccounts().length);
    assertEquals(skeleton.numIncludedAccounts(), skeleton.numAccounts());

    final var instructions = skeleton.parseInstructions(skeleton.parseAccounts());
    assertEquals(skeleton.numInstructions(), instructions.length);
    assertNotNull(skeleton.createTransaction());
  }

  @Test
  void multipleWritableSignersParseInOrder() {
    // three writable signers: the writable-signer loop must walk every slot, not just the
    // fee payer and one more
    final var feePayer = nextSigner();
    final var signerB = nextSigner();
    final var signerC = nextSigner();
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(
            AccountMeta.createWritableSigner(signerB.publicKey()),
            AccountMeta.createWritableSigner(signerC.publicKey())
        ),
        new byte[]{1, 2, 3, 4}
    );
    final var tx = Transaction.createTx(feePayer.publicKey(), ix);
    tx.setRecentBlockHash(new byte[Transaction.BLOCK_HASH_LENGTH]);
    assertEquals(3, tx.numSigners());
    tx.sign(List.of(feePayer, signerB, signerC));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertEquals(3, skeleton.numSignatures());
    assertEquals(0, skeleton.numReadonlySignedAccounts());

    final var signerAccounts = skeleton.parseSignerAccounts();
    assertEquals(3, signerAccounts.length);
    assertEquals(feePayer.publicKey(), signerAccounts[0].publicKey());
    for (final var signerAccount : signerAccounts) {
      assertTrue(signerAccount.signer(), signerAccount.publicKey() + " must be a signer");
      assertTrue(signerAccount.write(), signerAccount.publicKey() + " must be writable");
    }
    // every signer slot is distinct: the loop must advance a full key per iteration
    assertEquals(3, Arrays.stream(signerAccounts).map(AccountMeta::publicKey).distinct().count());

    assertSignerSplit(skeleton);
  }

  @Test
  void versionedAccountViewsAgree() {
    final var skeleton = skeleton(VERSIONED_TX);
    assertTrue(skeleton.isVersioned());
    assertEquals(1, skeleton.numSignatures());

    assertSignerSplit(skeleton);
    assertWritableSplit(skeleton);

    // table lookups add accounts beyond those included in the message
    assertTrue(skeleton.numAccounts() > skeleton.numIncludedAccounts());
    assertEquals(skeleton.numAccounts() - skeleton.numIncludedAccounts(), skeleton.numIndexedAccounts());

    final var instructions = skeleton.parseInstructionsWithoutTableAccounts();
    assertProgramAccounts(skeleton, instructions);
    assertSerializedInstructionsLength(skeleton, instructions);
  }
}
