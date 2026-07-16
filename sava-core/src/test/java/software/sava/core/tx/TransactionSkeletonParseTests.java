package software.sava.core.tx;

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

  private static TransactionSkeleton skeleton(final String base64) {
    return TransactionSkeleton.deserializeSkeleton(Base64.getDecoder().decode(base64));
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
    noMatch[0] ^= 0xFF;
    assertEquals(0, skeleton.filterInstructionsWithoutTableAccounts(
        software.sava.core.programs.Discriminator.createDiscriminator(noMatch, 0, 4)).length);
  }

  @Test
  void readOnlySignersParseAsReadOnly() {
    // no real fixture here has a read-only signer, so build one: the split between
    // writable and read-only signers is a header-driven bound that nothing else pins
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var readOnlySigner = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
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
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
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
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var signerB = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var signerC = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
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
