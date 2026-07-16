package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_META_SIZE;

/// Jazzer entry point for transaction wire parsing, the widest untrusted-input surface in
/// the library (RPC responses, user-pasted base64). The malformed-input contract is
/// "garbage in -> RuntimeException out": any [RuntimeException] from deserialization or the
/// offset-walking parsers is tolerated. Jazzer still flags what that contract does not
/// permit — hangs (its own timeout), stack/heap exhaustion, and any non-[RuntimeException]
/// throwable — and this harness adds the cross-method structural invariants that must hold
/// whenever a parse fully succeeds.
///
/// Seeded from real legacy and versioned (lookup-table) transactions plus a real
/// lookup-table account under src/test/resources/fuzz/txSkeleton — the header, offsets, and
/// lengths must all agree before any body-walking runs, so a from-scratch mutator never
/// reaches these paths. Every input is also fed to [AddressLookupTable#read] (see
/// [#fuzzLookupTable]), the other untrusted account-data parser on the versioned path.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :sava-core:fuzzTxSkeleton [-PmaxFuzzTime=<seconds>]`.
public final class TransactionSkeletonFuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
    fuzzLookupTable(data);
    fuzzSkeleton(data);
  }

  private static final PublicKey TABLE_ADDRESS = PublicKey.createPubKey(new byte[PUBLIC_KEY_LENGTH]);

  /// AddressLookupTable.read consumes untrusted account data (RPC responses) and versioned
  /// transaction parsing resolves indexes through the result, so it shares this harness and
  /// its seed corpus (alt_account is a real mainnet table). Same malformed-input contract as
  /// the skeleton. When a parse succeeds, the eager reverse-lookup view and the lazy overlay
  /// view walk the same bytes by different code paths and must agree on every accessor.
  private static void fuzzLookupTable(final byte[] data) {
    final AddressLookupTable table;
    try {
      table = AddressLookupTable.read(TABLE_ADDRESS, data);
    } catch (final RuntimeException tolerated) {
      return;
    }
    if (table == null) {
      if (data.length != 0) {
        throw new AssertionError("read returned null for non-empty data");
      }
      return;
    }

    final int numAccounts = table.numAccounts();
    if (numAccounts != (data.length - LOOKUP_TABLE_META_SIZE) >> 5) {
      throw new AssertionError("numAccounts != floor((length - meta) / 32)");
    }
    if (table.length() != data.length) {
      throw new AssertionError("length != data.length");
    }
    if (table.withReverseLookup() != table) {
      throw new AssertionError("withReverseLookup on an indexed table is not identity");
    }

    final var overlay = AddressLookupTable.readWithoutReverseLookup(TABLE_ADDRESS, data);
    if (overlay.numAccounts() != numAccounts
        || overlay.length() != data.length
        || !Arrays.equals(overlay.discriminator(), table.discriminator())
        || overlay.deactivationSlot() != table.deactivationSlot()
        || overlay.lastExtendedSlot() != table.lastExtendedSlot()
        || overlay.lastExtendedSlotStartIndex() != table.lastExtendedSlotStartIndex()
        || !Objects.equals(overlay.authority(), table.authority())
        || overlay.isActive() != table.isActive()) {
      throw new AssertionError("overlay view disagrees with indexed view on metadata");
    }
    if (!overlay.withReverseLookup().equals(table)) {
      throw new AssertionError("overlay.withReverseLookup() != read(...)");
    }

    final var unique = table.uniqueAccounts();
    if (!unique.equals(overlay.uniqueAccounts())
        || table.numUniqueAccounts() != unique.size()
        || overlay.numUniqueAccounts() != unique.size()) {
      throw new AssertionError("unique account views disagree");
    }

    for (int i = 0; i < numAccounts; ++i) {
      final var key = table.account(i);
      if (!key.equals(overlay.account(i))) {
        throw new AssertionError("account " + i + " disagrees between views");
      }
      if (!unique.contains(key)) {
        throw new AssertionError("account " + i + " missing from uniqueAccounts");
      }
      final int index = table.indexOf(key);
      // indexOf resolves to the first occurrence of a duplicated key
      if (index < 0 || index > i || !table.account(index).equals(key)) {
        throw new AssertionError("indexOf(account(" + i + ")) = " + index);
      }
      if (overlay.indexOf(key) != index) {
        throw new AssertionError("overlay.indexOf disagrees at " + i);
      }
      if (!table.containKey(key) || !overlay.containKey(key)) {
        throw new AssertionError("containKey false for account " + i);
      }
      if (table.indexOfOrThrow(key) != (byte) index || overlay.indexOfOrThrow(key) != (byte) index) {
        throw new AssertionError("indexOfOrThrow disagrees with indexOf at " + i);
      }
    }

    // a key absent from the table must be reported missing by both views
    final byte[] probeBytes = new byte[PUBLIC_KEY_LENGTH];
    if (numAccounts > 0) {
      System.arraycopy(data, LOOKUP_TABLE_META_SIZE, probeBytes, 0, PUBLIC_KEY_LENGTH);
    }
    probeBytes[0] ^= 1;
    final var probe = PublicKey.createPubKey(probeBytes);
    final boolean present = unique.contains(probe);
    if ((table.indexOf(probe) >= 0) != present
        || (overlay.indexOf(probe) >= 0) != present
        || table.containKey(probe) != present
        || overlay.containKey(probe) != present) {
      throw new AssertionError("membership of probe key disagrees with uniqueAccounts");
    }
    if (!present) {
      try {
        table.indexOfOrThrow(probe);
        throw new AssertionError("indexOfOrThrow returned for a missing key");
      } catch (final IllegalStateException expectedContract) {
      }
    }

    // write copies the account data back out verbatim
    final byte[] out = new byte[data.length + 2];
    final int written = table.write(out, 1);
    if (written != data.length || !Arrays.equals(out, 1, 1 + written, data, 0, data.length)) {
      throw new AssertionError("write did not round-trip the account data");
    }
  }

  private static void fuzzSkeleton(final byte[] data) {
    final TransactionSkeleton skeleton;
    try {
      skeleton = TransactionSkeleton.deserializeSkeleton(data);
    } catch (final RuntimeException tolerated) {
      return;
    }

    // Version discrimination is a pure boolean pair over one field; the two views must
    // never agree, independent of any offset walking.
    if (skeleton.isLegacy() == skeleton.isVersioned()) {
      throw new AssertionError("isLegacy and isVersioned agree for: " + skeleton.id());
    }
    if (skeleton.numSigners() != skeleton.numSignatures()) {
      throw new AssertionError("numSigners != numSignatures");
    }
    // numAccounts counts included accounts plus every lookup-table index; it can never be
    // fewer than the directly included accounts.
    if (skeleton.numAccounts() < skeleton.numIncludedAccounts()) {
      throw new AssertionError("numAccounts < numIncludedAccounts");
    }
    if (skeleton.numIndexedAccounts() != skeleton.numAccounts() - skeleton.numIncludedAccounts()) {
      throw new AssertionError("numIndexedAccounts inconsistent with numAccounts - numIncludedAccounts");
    }

    // The parsers walk raw offsets, so a malformed body may throw here — tolerated. But if
    // the whole pipeline succeeds, the results must be mutually consistent; a violation is
    // an AssertionError, which is not caught below and so surfaces as a finding.
    try {
      final var accounts = skeleton.parseAccounts();
      final var instructions = skeleton.parseInstructions(accounts);
      final var signerAccounts = skeleton.parseSignerAccounts();
      final var signerKeys = skeleton.parseSignerPublicKeys();
      final var nonSignerAccounts = skeleton.parseNonSignerAccounts();
      final var nonSignerKeys = skeleton.parseNonSignerPublicKeys();
      final var programAccounts = skeleton.parseProgramAccounts();
      final var withoutAccounts = skeleton.parseInstructionsWithoutAccounts();
      final int serializedLen = skeleton.serializedInstructionsLength();
      skeleton.id();
      skeleton.feePayer();
      skeleton.base58BlockHash();

      if (accounts.length != skeleton.numIncludedAccounts()) {
        throw new AssertionError("parseAccounts length != numIncludedAccounts");
      }
      if (instructions.length != skeleton.numInstructions()) {
        throw new AssertionError("parseInstructions length != numInstructions");
      }
      if (withoutAccounts.length != skeleton.numInstructions()) {
        throw new AssertionError("parseInstructionsWithoutAccounts length != numInstructions");
      }
      if (programAccounts.length != skeleton.numInstructions()) {
        throw new AssertionError("parseProgramAccounts length != numInstructions");
      }
      if (serializedLen < 0) {
        throw new AssertionError("serializedInstructionsLength is negative: " + serializedLen);
      }
      if (signerAccounts.length != signerKeys.length) {
        throw new AssertionError("signer accounts and keys differ in length");
      }
      // signers + non-signers partition the included accounts exactly.
      if (signerAccounts.length + nonSignerAccounts.length != accounts.length) {
        throw new AssertionError("signers + non-signers != included accounts");
      }
      if (nonSignerAccounts.length != nonSignerKeys.length) {
        throw new AssertionError("non-signer accounts and keys differ in length");
      }
      for (int i = 0; i < signerKeys.length; ++i) {
        if (!signerAccounts[i].publicKey().equals(signerKeys[i])) {
          throw new AssertionError("signer account " + i + " disagrees with signer key");
        }
      }
      // The instruction program of each instruction must be one of the transaction's
      // accounts; parseInstructions resolves it by index, parseProgramAccounts re-reads it.
      for (int i = 0; i < programAccounts.length; ++i) {
        if (!instructions[i].programId().publicKey().equals(programAccounts[i])) {
          throw new AssertionError("program account " + i + " disagrees between parse paths");
        }
      }
    } catch (final RuntimeException tolerated) {
      // malformed body reached by a valid header — parsing may fail, that is in contract
    }

    // Versioned transactions with lookup tables have a whole second offset-walking parser
    // (parseAccounts(Map)) that the no-table paths above never reach. Drive it with
    // synthetic 256-entry tables so any in-band byte index resolves, keeping the check
    // sound: a successful parse must still yield exactly numAccounts entries.
    final var tableKeys = skeleton.lookupTableAccounts();
    if (skeleton.isVersioned() && tableKeys.length > 0) {
      try {
        final var tables = new HashMap<PublicKey, AddressLookupTable>(tableKeys.length);
        for (final var key : tableKeys) {
          tables.put(key, syntheticTable(key));
        }
        final var withTables = skeleton.parseAccounts(tables);
        if (withTables.length != skeleton.numAccounts()) {
          throw new AssertionError("parseAccounts(tables) length != numAccounts");
        }
      } catch (final RuntimeException tolerated) {
        // a malformed lookup-table section reached by a valid header — tolerated
      }
    }
  }

  private static AddressLookupTable syntheticTable(final PublicKey address) {
    final byte[] tableData = new byte[LOOKUP_TABLE_META_SIZE + (256 * PUBLIC_KEY_LENGTH)];
    // distinct, deterministic non-zero account bytes so each index maps to its own key
    for (int i = 0; i < 256; ++i) {
      tableData[LOOKUP_TABLE_META_SIZE + (i * PUBLIC_KEY_LENGTH)] = (byte) i;
      tableData[LOOKUP_TABLE_META_SIZE + (i * PUBLIC_KEY_LENGTH) + 1] = (byte) (i ^ 0x5A);
    }
    return AddressLookupTable.readWithoutReverseLookup(address, tableData);
  }

  private TransactionSkeletonFuzz() {
  }
}
