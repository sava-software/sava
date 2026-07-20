package software.sava.core.accounts.meta;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// The privilege lattice behind transaction account building. `merge` is what
/// combines the same account appearing across several instructions, and its
/// result decides both the header counts and — via `invoked()` — whether an
/// account may be moved into an address lookup table
/// ([software.sava.core.tx.Transaction] line 249). Nothing referenced
/// `TableAccountMeta` or asserted `merge` precedence before these.
final class AccountMetaTests {

  private static final PublicKey KEY = PublicKey.createPubKey(new byte[32]);

  private static final byte[] OTHER_BYTES = new byte[32];

  static {
    OTHER_BYTES[0] = 1;
  }

  private static final PublicKey OTHER_KEY = PublicKey.createPubKey(OTHER_BYTES);

  /// Compact flag rendering, so failures name the privileges rather than a class.
  private static String flags(final AccountMeta meta) {
    return (meta.feePayer() ? "f" : "-")
        + (meta.signer() ? "s" : "-")
        + (meta.write() ? "w" : "-")
        + (meta.invoked() ? "i" : "-");
  }

  private record Named(String name, Function<PublicKey, AccountMeta> factory) {

    AccountMeta of(final PublicKey key) {
      return factory.apply(key);
    }
  }

  private static final List<Named> ALL = List.of(
      new Named("read", AccountMeta::createRead),
      new Named("write", AccountMeta::createWrite),
      new Named("readSigner", AccountMeta::createReadOnlySigner),
      new Named("writeSigner", AccountMeta::createWritableSigner),
      new Named("feePayer", AccountMeta::createFeePayer),
      new Named("invoked", AccountMeta::createInvoked)
  );

  @Test
  void factoriesSetTheExpectedFlags() {
    assertEquals("----", flags(AccountMeta.createRead(KEY)));
    assertEquals("--w-", flags(AccountMeta.createWrite(KEY)));
    assertEquals("-s--", flags(AccountMeta.createReadOnlySigner(KEY)));
    assertEquals("-sw-", flags(AccountMeta.createWritableSigner(KEY)));
    assertEquals("fsw-", flags(AccountMeta.createFeePayer(KEY)));
    assertEquals("---i", flags(AccountMeta.createInvoked(KEY)));
  }

  @Test
  void factoriesRejectNullKeys() {
    for (final var named : ALL) {
      assertThrows(NullPointerException.class, () -> named.of(null), named.name());
    }
  }

  @Test
  void createMetaPicksByWritableAndSigner() {
    assertEquals("----", flags(AccountMeta.createMeta(KEY, false, false)));
    assertEquals("--w-", flags(AccountMeta.createMeta(KEY, true, false)));
    assertEquals("-s--", flags(AccountMeta.createMeta(KEY, false, true)));
    assertEquals("-sw-", flags(AccountMeta.createMeta(KEY, true, true)));
  }

  /// invoked outranks feePayer, which outranks the writable/signer pair.
  @Test
  void createMetaPrecedenceIsInvokedThenFeePayer() {
    assertEquals("---i", flags(AccountMeta.createMeta(KEY, true, true, true, true)));
    assertEquals("fsw-", flags(AccountMeta.createMeta(KEY, false, true, false, false)));
    assertEquals("-sw-", flags(AccountMeta.createMeta(KEY, false, false, true, true)));
    assertEquals("----", flags(AccountMeta.createMeta(KEY, false, false, false, false)));
  }

  @Test
  void createAccountsMapSeedsTheFeePayer() {
    final var accounts = AccountMeta.createAccountsMap(4, KEY);
    assertEquals(1, accounts.size());
    assertEquals("fsw-", flags(accounts.get(KEY)));
  }

  /// Merging a read only meta always yields the other side, whichever way round.
  @Test
  void readOnlyMergesToTheOtherSide() {
    for (final var named : ALL) {
      final var other = named.of(KEY);
      assertEquals(flags(other), flags(AccountMeta.createRead(KEY).merge(other)), "read.merge(" + named.name() + ')');
    }
  }

  @Test
  void mergingIdenticalMetasIsStable() {
    for (final var named : ALL) {
      final var meta = named.of(KEY);
      assertEquals(flags(meta), flags(meta.merge(named.of(KEY))), named.name());
    }
  }

  /// The fee payer is the top of the signer/writable lattice. It survives every
  /// merge except against `invoked`, which is the gap pinned below.
  @Test
  void feePayerSurvivesEveryMergeButInvoked() {
    final var feePayer = AccountMeta.createFeePayer(KEY);
    for (final var named : ALL) {
      if (named.name().equals("invoked")) {
        continue;
      }
      assertTrue(named.of(KEY).merge(feePayer).feePayer(), named.name() + ".merge(feePayer)");
      assertTrue(feePayer.merge(named.of(KEY)).feePayer(), "feePayer.merge(" + named.name() + ')');
    }
  }

  @Test
  void signerAndWritableCombineToWritableSigner() {
    assertEquals("-sw-", flags(AccountMeta.createWrite(KEY).merge(AccountMeta.createReadOnlySigner(KEY))));
    assertEquals("-sw-", flags(AccountMeta.createReadOnlySigner(KEY).merge(AccountMeta.createWrite(KEY))));
  }

  /// The complete merge table, receiver down the left, argument across. Pinned in
  /// full because the individual implementations are spread across six classes
  /// and no single place states the result. Cells marked in
  /// [#mergeLosesPrivilegesOnlyInTheKnownInvokedGaps] are the lossy ones.
  @Test
  void mergeMatrixIsPinned() {
    final String[][] expected = {
        //             read    write   rdSign  wrSign  feePay  invoked
        /* read    */ {"----", "--w-", "-s--", "-sw-", "fsw-", "---i"},
        /* write   */ {"--w-", "--w-", "-sw-", "-sw-", "fsw-", "--wi"},
        /* rdSign  */ {"-s--", "-sw-", "-s--", "-sw-", "fsw-", "-s--"},
        /* wrSign  */ {"-sw-", "-sw-", "-sw-", "-sw-", "fsw-", "-sw-"},
        /* feePay  */ {"fsw-", "fsw-", "fsw-", "fsw-", "fsw-", "fsw-"},
        /* invoked */ {"---i", "--wi", "---i", "--wi", "--wi", "---i"},
    };
    for (int i = 0; i < ALL.size(); ++i) {
      for (int j = 0; j < ALL.size(); ++j) {
        final var merged = ALL.get(i).of(KEY).merge(ALL.get(j).of(KEY));
        assertEquals(expected[i][j], flags(merged),
            ALL.get(i).name() + ".merge(" + ALL.get(j).name() + ')');
      }
    }
  }

  /// Merging yields the union of both sides' privileges, except where `invoked`
  /// meets `signer`. There is no type for an invoked signer, so those six cells
  /// have to drop something and the lattice picks one side.
  ///
  /// They are left as they are rather than given new types, because they require
  /// an account that is simultaneously a transaction level signer and an invoked
  /// program — a program account cannot sign, so the state is unreachable. The
  /// one reachable gap, `write` against `invoked`, is fixed and covered by
  /// [#writeAndInvokedCombineInEitherOrder].
  ///
  /// Scoped rather than skipped: a *new* loss fails the build, and so does one
  /// of these being fixed without updating the set.
  @Test
  void mergeLosesPrivilegesOnlyWhereInvokedMeetsSigner() {
    final var knownLossy = java.util.Set.of(
        "readSigner.merge(invoked)", "writeSigner.merge(invoked)", "feePayer.merge(invoked)",
        "invoked.merge(readSigner)", "invoked.merge(writeSigner)", "invoked.merge(feePayer)"
    );
    for (final var left : ALL) {
      for (final var right : ALL) {
        final var a = left.of(KEY);
        final var b = right.of(KEY);
        final var merged = a.merge(b);
        final var pair = left.name() + ".merge(" + right.name() + ')';
        final boolean lost = ((a.signer() || b.signer()) && !merged.signer())
            || ((a.write() || b.write()) && !merged.write())
            || ((a.feePayer() || b.feePayer()) && !merged.feePayer())
            || ((a.invoked() || b.invoked()) && !merged.invoked());
        if (knownLossy.contains(pair)) {
          assertTrue(lost, pair + " is recorded as lossy but no longer is — remove it from the known set");
        } else {
          assertFalse(lost, "new privilege loss: " + pair + " = " + flags(merged));
        }
      }
    }
  }

  /// `invoked` used to be dropped whenever the invoked meta was the argument
  /// rather than the receiver, which mattered because `InstructionRecord` merges
  /// an instruction's accounts before its program id: an account written by one
  /// instruction and invoked as the program of another arrives as
  /// `write.merge(invoked)`. `invoked()` keeps a program id out of an address
  /// lookup table, and the runtime rejects a transaction whose program is
  /// referenced through one — so losing it produced a transaction that failed
  /// to execute.
  @Test
  void writeAndInvokedCombineInEitherOrder() {
    final var invoked = AccountMeta.createInvoked(KEY);

    assertEquals("--wi", flags(AccountMeta.createWrite(KEY).merge(invoked)), "write.merge(invoked)");
    assertEquals("--wi", flags(invoked.merge(AccountMeta.createWrite(KEY))), "invoked.merge(write)");

    // and it is stable once combined, from both sides
    final var combined = AccountMeta.createWrite(KEY).merge(invoked);
    assertEquals("--wi", flags(combined.merge(AccountMeta.createWrite(KEY))));
    assertEquals("--wi", flags(combined.merge(invoked)));
    assertEquals("--wi", flags(AccountMeta.createWrite(KEY).merge(combined)));
    assertEquals("--wi", flags(invoked.merge(combined)));

    // read only still defers wholesale
    assertEquals("---i", flags(AccountMeta.createRead(KEY).merge(invoked)));
    assertEquals("---i", flags(invoked.merge(AccountMeta.createRead(KEY))));
  }

  /// `AccountMetaReadOnly.equals` compares exact classes rather than using
  /// `instanceof`, so that a read only meta never equals a subclass holding more
  /// privileges. That means it needs an explicit null guard, which it previously
  /// lacked — `o.getClass()` threw instead of returning false.
  @Test
  void equalsIsNullSafeAcrossTheHierarchy() {
    assertFalse(AccountMeta.createRead(KEY).equals(null));
    assertFalse(AccountMeta.createWrite(KEY).equals(null));
    assertFalse(AccountMeta.createReadOnlySigner(KEY).equals(null));
    assertFalse(AccountMeta.createWritableSigner(KEY).equals(null));
    assertFalse(AccountMeta.createFeePayer(KEY).equals(null));
    assertFalse(AccountMeta.createInvoked(KEY).equals(null));

    // the exact-class rule still holds: a read only meta is not a writable one
    assertNotEquals(AccountMeta.createRead(KEY), AccountMeta.createWrite(KEY));
    assertNotEquals(AccountMeta.createWrite(KEY), AccountMeta.createRead(KEY));
  }

  @Test
  void equalsDistinguishesKeysAndPrivileges() {
    assertEquals(AccountMeta.createWrite(KEY), AccountMeta.createWrite(KEY));
    assertNotEquals(AccountMeta.createWrite(KEY), AccountMeta.createWrite(OTHER_KEY));
    assertNotEquals(AccountMeta.createWrite(KEY), AccountMeta.createRead(KEY));
    assertNotEquals(AccountMeta.createReadOnlySigner(KEY), AccountMeta.createWritableSigner(KEY));
    assertEquals(AccountMeta.createWrite(KEY).hashCode(), AccountMeta.createWrite(KEY).hashCode());
  }

  /// Every concrete type, including the one only reachable through a merge.
  private static List<Named> allConcrete() {
    final var all = new java.util.ArrayList<>(ALL);
    all.add(new Named("invokedAndWrite", key -> AccountMeta.createWrite(key).merge(AccountMeta.createInvoked(key))));
    return all;
  }

  /// The metas are map keys — `AccountMeta.createAccountsMap` and the merge in
  /// `InstructionRecord` both hash them — so the contract has to hold across the
  /// whole hierarchy, not just the two types the earlier tests happened to touch.
  @Test
  void equalsAndHashCodeContractHoldsForEveryType() {
    for (final var named : allConcrete()) {
      final var a = named.of(KEY);
      final var b = named.of(KEY);
      final var other = named.of(OTHER_KEY);

      assertEquals(a, a, named.name() + " reflexive");
      assertEquals(a, b, named.name() + " equal by key");
      assertEquals(b, a, named.name() + " symmetric");
      assertEquals(a.hashCode(), b.hashCode(), named.name() + " hashCode consistent");
      assertNotEquals(a, other, named.name() + " differing keys");
      assertNotEquals(a, null, named.name() + " null safe");
      assertNotEquals(a, "not a meta", named.name() + " foreign type");
    }
  }

  /// Privileges are part of identity: two metas for the same account but with
  /// different privileges must not collide as map keys, in either direction.
  @Test
  void differentPrivilegesAreNeverEqualForTheSameKey() {
    final var concrete = allConcrete();
    for (int i = 0; i < concrete.size(); ++i) {
      for (int j = 0; j < concrete.size(); ++j) {
        if (i == j) {
          continue;
        }
        final var a = concrete.get(i).of(KEY);
        final var b = concrete.get(j).of(KEY);
        final var label = concrete.get(i).name() + " vs " + concrete.get(j).name();
        assertNotEquals(a, b, label);
        assertNotEquals(b, a, label + " (symmetric)");
      }
    }
  }

  /// hashCode mixes the privilege bits, not just the key, so metas for the same
  /// account do not all land in one bucket.
  ///
  /// The subclasses fold in the `(signer, write, invoked)` triple and nothing
  /// else, so `feePayer` and `writeSigner` — which share that triple — hash
  /// identically. That is legal: `equals` separates them by class, and unequal
  /// objects are permitted to collide. It is asserted rather than worked around
  /// so the scheme is stated somewhere.
  @Test
  void hashCodeDistinguishesPrivileges() {
    final var byHash = new java.util.HashMap<Integer, String>();
    for (final var named : allConcrete()) {
      final int hash = named.of(KEY).hashCode();
      final var clash = byHash.put(hash, named.name());
      if (clash != null) {
        assertEquals("writeSigner", clash, "unexpected hashCode collision with " + named.name());
        assertEquals("feePayer", named.name(), "unexpected hashCode collision with " + clash);
      }
    }
    // six distinct hashes across seven types, the one pair being the documented collision
    assertEquals(allConcrete().size() - 1, byHash.size());

    assertEquals(
        AccountMeta.createWritableSigner(KEY).hashCode(),
        AccountMeta.createFeePayer(KEY).hashCode(),
        "feePayer is not part of the hashed triple");
    assertNotEquals(AccountMeta.createWritableSigner(KEY), AccountMeta.createFeePayer(KEY),
        "but equals must still separate them");

    // the read only base case is the key's own hash — it has no privileges to mix
    assertEquals(KEY.hashCode(), AccountMeta.createRead(KEY).hashCode());
    // and the key always participates
    for (final var named : allConcrete()) {
      assertNotEquals(named.of(KEY).hashCode(), named.of(OTHER_KEY).hashCode(), named.name());
    }
  }

  /// The shared constants are API surface — the array generator feeds
  /// `toArray` calls in transaction building, and the factory references are
  /// handed to map operations.
  @Test
  void sharedConstantsAreUsable() {
    final var array = AccountMeta.ACCOUNT_META_ARRAY_GENERATOR.apply(3);
    assertNotNull(array);
    assertEquals(3, array.length);
    assertEquals(0, AccountMeta.ACCOUNT_META_ARRAY_GENERATOR.apply(0).length);

    assertTrue(AccountMeta.NO_KEYS.isEmpty());
    assertEquals("---i", flags(AccountMeta.CREATE_INVOKED.apply(KEY)));
    assertEquals("----", flags(AccountMeta.CREATE_READ.apply(KEY)));
    assertEquals("--w-", flags(AccountMeta.CREATE_WRITE.apply(KEY)));
    assertEquals("-s--", flags(AccountMeta.CREATE_READ_ONLY_SIGNER.apply(KEY)));
    assertEquals("-sw-", flags(AccountMeta.CREATE_WRITE_SIGNER.apply(KEY)));
    assertEquals("fsw-", flags(AccountMeta.CREATE_FEE_PAYER.apply(KEY)));
  }

  @Test
  void toStringReportsEveryFlag() {
    final var json = AccountMeta.createFeePayer(KEY).toString();
    assertTrue(json.contains("\"feePayer\": true"), json);
    assertTrue(json.contains("\"signer\": true"), json);
    assertTrue(json.contains("\"writable\": true"), json);
    assertTrue(json.contains("\"invoked\": false"), json);
    assertTrue(json.contains(KEY.toBase58()), json);
  }
}
