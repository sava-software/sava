package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.tx.TransactionRecord.NO_TABLES;

/// Pins the transaction equality contract, which nothing else exercised.
///
/// `TransactionRecord` used to be a Java record, so it compared component-wise. It is now a final
/// class, and both it and `V1Transaction` define equality as *serialized byte* equality:
///
/// ```
/// return (o instanceof final TransactionRecord that) && Arrays.equals(data, that.data);
/// ```
///
/// with `BaseTransaction#hashCode()` supplying `Arrays.hashCode(data)`. Three consequences follow,
/// and each is pinned deliberately below rather than left to be rediscovered:
///
///  1. Equality is by format as well as by bytes — the `instanceof` is against the concrete final
///     class, so a legacy/v0 `TransactionRecord` is never equal to a v1 `V1Transaction`.
///  2. Everything that is not serialized — the fee payer meta, the instruction list, the lookup
///     table, the parsed offsets — is ignored. Two transactions carrying the same bytes are equal
///     even when they were built by completely different routes.
///  3. A transaction is **mutable**, so its hash code is unstable: `sign(...)` and
///     `setRecentBlockHash(...)` both write through the buffer that backs `equals`/`hashCode`. A
///     transaction used as a key in a hash-based collection is lost the moment it is signed. That
///     is characterized here as observed behaviour, not endorsed as desirable.
///
/// Every key is fixed, so a failure is exactly reproducible.
final class TransactionEqualityTests {

  private static PublicKey key(final int fill) {
    final byte[] key = new byte[PUBLIC_KEY_LENGTH];
    Arrays.fill(key, (byte) fill);
    return PublicKey.createPubKey(key);
  }

  private static Signer signer(final int seed) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) seed);
    return Signer.createFromPrivateKey(privateKey);
  }

  private static byte[] blockHash(final int fill) {
    final byte[] blockHash = new byte[Transaction.BLOCK_HASH_LENGTH];
    Arrays.fill(blockHash, (byte) fill);
    return blockHash;
  }

  private static final Signer FEE_PAYER = signer(41);
  private static final PublicKey READ_ACCOUNT = key(12);
  private static final byte[] HASH = blockHash(0xC3);
  private static final byte[] OTHER_HASH = blockHash(0x5A);

  private static Instruction instruction(final byte... data) {
    return Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createRead(READ_ACCOUNT)),
        data
    );
  }

  /// A v1 transaction over the given instruction data, hashed so its bytes are fully determined.
  private static V1Transaction v1Tx(final byte... ixData) {
    final var tx = TxBuilder.createBuilder()
        .feePayer(FEE_PAYER.publicKey())
        .addInstruction(instruction(ixData))
        .createTransaction();
    tx.setRecentBlockHash(HASH);
    return assertInstanceOf(V1Transaction.class, tx);
  }

  /// A legacy transaction over the given instruction data, hashed so its bytes are fully determined.
  private static TransactionRecord legacyTx(final byte... ixData) {
    final var tx = Transaction.createTx(FEE_PAYER.publicKey(), instruction(ixData));
    tx.setRecentBlockHash(HASH);
    return assertInstanceOf(TransactionRecord.class, tx);
  }

  @Test
  void independentBuildsWithTheSameBytesAreEqual() {
    final var v1 = v1Tx((byte) 1, (byte) 2);
    final var v1Twin = v1Tx((byte) 1, (byte) 2);
    assertNotSame(v1, v1Twin);
    assertNotSame(v1.serialized(), v1Twin.serialized());
    assertArrayEquals(v1.serialized(), v1Twin.serialized(), "the fixture must be byte deterministic");
    assertTrue(v1.equals(v1Twin));
    assertTrue(v1Twin.equals(v1), "equality is symmetric");
    assertEquals(v1.hashCode(), v1Twin.hashCode(), "equal transactions must share a hash code");

    final var legacy = legacyTx((byte) 1, (byte) 2);
    final var legacyTwin = legacyTx((byte) 1, (byte) 2);
    assertNotSame(legacy, legacyTwin);
    assertNotSame(legacy.serialized(), legacyTwin.serialized());
    assertArrayEquals(legacy.serialized(), legacyTwin.serialized(), "the fixture must be byte deterministic");
    assertTrue(legacy.equals(legacyTwin));
    assertTrue(legacyTwin.equals(legacy), "equality is symmetric");
    assertEquals(legacy.hashCode(), legacyTwin.hashCode(), "equal transactions must share a hash code");
  }

  @Test
  void aSingleDifferingByteMakesTransactionsUnequal() {
    final var v1 = v1Tx((byte) 1, (byte) 2);
    // Same length, one differing payload byte: only Arrays.equals can tell these apart.
    final var v1Other = v1Tx((byte) 1, (byte) 3);
    assertEquals(v1.size(), v1Other.size());
    assertFalse(v1.equals(v1Other));
    assertFalse(v1Other.equals(v1));
    assertNotEquals(v1.hashCode(), v1Other.hashCode());

    // A differing length as well.
    assertFalse(v1.equals(v1Tx((byte) 1)));

    final var legacy = legacyTx((byte) 1, (byte) 2);
    final var legacyOther = legacyTx((byte) 1, (byte) 3);
    assertEquals(legacy.size(), legacyOther.size());
    assertFalse(legacy.equals(legacyOther));
    assertFalse(legacyOther.equals(legacy));
    assertNotEquals(legacy.hashCode(), legacyOther.hashCode());

    assertFalse(legacy.equals(legacyTx((byte) 1)));
  }

  @Test
  void differingOnlyInTheRecentBlockHashIsUnequal() {
    final var v1 = v1Tx((byte) 7);
    final var v1Rehashed = v1Tx((byte) 7);
    assertTrue(v1.equals(v1Rehashed));
    v1Rehashed.setRecentBlockHash(OTHER_HASH);
    assertFalse(v1.equals(v1Rehashed), "the block hash is part of the serialized bytes");

    final var legacy = legacyTx((byte) 7);
    final var legacyRehashed = legacyTx((byte) 7);
    assertTrue(legacy.equals(legacyRehashed));
    legacyRehashed.setRecentBlockHash(OTHER_HASH);
    assertFalse(legacy.equals(legacyRehashed), "the block hash is part of the serialized bytes");
  }

  @Test
  void equalsIsReflexive() {
    final var v1 = v1Tx((byte) 1);
    assertTrue(v1.equals(v1));
    assertEquals(v1.hashCode(), v1.hashCode());

    final var legacy = legacyTx((byte) 1);
    assertTrue(legacy.equals(legacy));
    assertEquals(legacy.hashCode(), legacy.hashCode());
  }

  @Test
  void equalsRejectsNullAndForeignTypes() {
    final var v1 = v1Tx((byte) 1);
    final var legacy = legacyTx((byte) 1);

    assertFalse(v1.equals(null));
    assertFalse(legacy.equals(null));

    assertFalse(v1.equals("not a transaction"));
    assertFalse(legacy.equals("not a transaction"));
    assertFalse(v1.equals(v1.serialized()));
    assertFalse(legacy.equals(legacy.serialized()));
  }

  /// The `instanceof` is against the concrete final class, so the two formats never compare equal
  /// even though they implement the same interface and were built from the same inputs.
  @Test
  void aV1TransactionIsNeverEqualToALegacyTransaction() {
    final var v1 = v1Tx((byte) 1);
    final var legacy = legacyTx((byte) 1);
    assertNotEquals(v1.getClass(), legacy.getClass());

    assertFalse(v1.equals(legacy));
    assertFalse(legacy.equals(v1), "cross-format inequality is symmetric");
  }

  /// Equality reads the bytes and nothing else: a transaction rebuilt from a copy of another's
  /// serialized payload — different buffer, freshly parsed instruction list, freshly derived fee
  /// payer meta — is equal to it.
  @Test
  void equalityIsSerializedByteEqualityRegardlessOfHowTheTransactionWasBuilt() {
    final var v1 = v1Tx((byte) 4, (byte) 5);
    v1.sign(FEE_PAYER);
    final var v1Parsed = assertInstanceOf(
        V1Transaction.class,
        TransactionSkeleton.deserializeSkeleton(v1.serialized().clone()).createTransaction()
    );
    assertNotSame(v1.serialized(), v1Parsed.serialized());
    assertNotSame(v1.instructions(), v1Parsed.instructions());
    assertTrue(v1.equals(v1Parsed));
    assertTrue(v1Parsed.equals(v1));
    assertEquals(v1.hashCode(), v1Parsed.hashCode());

    final var legacy = legacyTx((byte) 4, (byte) 5);
    legacy.sign(FEE_PAYER);
    final var legacyParsed = assertInstanceOf(
        TransactionRecord.class,
        TransactionSkeleton.deserializeSkeleton(legacy.serialized().clone()).createTransaction()
    );
    assertNotSame(legacy.serialized(), legacyParsed.serialized());
    assertNotSame(legacy.instructions(), legacyParsed.instructions());
    assertTrue(legacy.equals(legacyParsed));
    assertTrue(legacyParsed.equals(legacy));
    assertEquals(legacy.hashCode(), legacyParsed.hashCode());
  }

  @Test
  void hashCodeIsTheSerializedByteHash() {
    final var v1 = v1Tx((byte) 1, (byte) 2);
    assertEquals(Arrays.hashCode(v1.serialized()), v1.hashCode());
    assertNotEquals(0, v1.hashCode(), "the fixture's byte hash is non-zero");

    final var legacy = legacyTx((byte) 1, (byte) 2);
    assertEquals(Arrays.hashCode(legacy.serialized()), legacy.hashCode());
    assertNotEquals(0, legacy.hashCode(), "the fixture's byte hash is non-zero");
  }

  /// Characterization, not endorsement: because `hashCode` hashes the live buffer and both
  /// `setRecentBlockHash` and `sign` write into it, a transaction stored in a hash-based collection
  /// cannot be found again after either call. Callers who need a stable key must snapshot the
  /// serialized bytes themselves.
  @Test
  void hashCodeIsUnstableAcrossBlockHashUpdatesAndSigning() {
    final var tx = v1Tx((byte) 9);
    final var set = new HashSet<Transaction>();
    assertTrue(set.add(tx));
    assertTrue(set.contains(tx), "the transaction is findable before it is mutated");

    final int beforeRehash = tx.hashCode();
    tx.setRecentBlockHash(OTHER_HASH);
    assertNotEquals(beforeRehash, tx.hashCode(), "re-hashing moves the transaction's hash code");
    assertFalse(set.contains(tx), "the very object stored can no longer be found");
    assertFalse(set.remove(tx), "and cannot be removed either");
    assertEquals(1, set.size(), "the stale entry is stranded in the set");

    final int beforeSigning = tx.hashCode();
    tx.sign(FEE_PAYER);
    assertNotEquals(beforeSigning, tx.hashCode(), "signing moves the transaction's hash code again");
    assertFalse(set.contains(tx));

    // Equality is just as mutable: the signed transaction no longer equals its unsigned twin.
    final var unsignedTwin = v1Tx((byte) 9);
    unsignedTwin.setRecentBlockHash(OTHER_HASH);
    assertFalse(tx.equals(unsignedTwin));
    unsignedTwin.sign(FEE_PAYER);
    assertTrue(tx.equals(unsignedTwin), "ed25519 signing is deterministic, so the bytes converge again");
  }

  /// A v1 transaction carries no address lookup tables at all: SIMD-0385 serializes every address
  /// inline. The accessor must still hand back the shared empty array rather than null, so callers
  /// can iterate it unguarded.
  @Test
  void aV1TransactionHasNoLookupTables() {
    final var v1 = v1Tx((byte) 1);
    final var tableAccountMetas = v1.tableAccountMetas();
    assertNotNull(tableAccountMetas);
    assertEquals(0, tableAccountMetas.length);
    assertSame(NO_TABLES, tableAccountMetas);
    assertNull(v1.lookupTable());
  }
}
