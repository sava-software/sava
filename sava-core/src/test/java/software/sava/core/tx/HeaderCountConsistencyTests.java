package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// A message's signature count and address count are two independently read compact-u16 fields, so
/// a header can declare more required signatures than it has addresses. No valid transaction does
/// — every signer is an address — but the bytes are representable, and every account-parsing entry
/// point sizes its array from the address count before filling signer slots from the signature
/// count. That mismatch used to escape as a bare `ArrayIndexOutOfBoundsException`, or as a
/// `NegativeArraySizeException` where the two counts are subtracted.
///
/// This is about *how* such a header fails, not *whether*: these inputs threw before too. Headers
/// that are merely over-limit stay readable — see
/// `LegacyMessageConformanceTests#accountAndHeaderUnsignedByteBoundariesRemainPermissiveForAnalysis`,
/// which is the affordance this must not disturb.
final class HeaderCountConsistencyTests {

  /// A complete legacy transaction declaring one required signature and zero addresses.
  ///
  /// The serialized signature count **matches** the header's, so the only defect is the address
  /// count. That isolation matters: a payload whose prefix disagreed with its header would be
  /// refused by the signature-layout check instead, and would pass these assertions while leaving
  /// the address-coverage guard untested on any path that checks the layout first.
  private static byte[] moreSignersThanAddresses() {
    final byte[] data = new byte[102];
    data[0] = 1;   // serialized signature count, agreeing with the header below
    // data[1..64] the signature slot it reserves
    data[65] = 1;  // num_required_signatures
    data[66] = 0;  // num_readonly_signed
    data[67] = 0;  // num_readonly_unsigned
    data[68] = 0;  // address count
    // data[69..100] blockhash, data[101] instruction count
    return data;
  }

  @Test
  void aHeaderDeclaringMoreSignersThanAddressesIsDiagnosed() {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(moreSignersThanAddresses());
    assertEquals(1, skeleton.numSignatures());
    assertEquals(0, skeleton.numIncludedAccounts(), "the header contradicts itself");

    final String expected = "Header declares 1 required signatures but only 0 addresses are included.";
    assertEquals(expected, assertThrows(IllegalStateException.class, skeleton::parseAccounts).getMessage());
    assertEquals(expected, assertThrows(IllegalStateException.class, skeleton::createTransaction).getMessage());
    // These two subtract the counts, so they reached NegativeArraySizeException rather than an
    // out-of-bounds write — a different unchecked exception for the same contradiction.
    assertEquals(expected, assertThrows(IllegalStateException.class, skeleton::parseNonSignerAccounts).getMessage());
    assertEquals(expected, assertThrows(IllegalStateException.class, skeleton::parseNonSignerPublicKeys).getMessage());

    // The signer readers do not size an array from the address count, so nothing here ever threw:
    // with no addresses to read, they reinterpret the bytes that follow — the blockhash — as the
    // fee payer, and hand back a signer the payload does not contain.
    assertEquals(expected, assertThrows(IllegalStateException.class, skeleton::feePayer).getMessage());
    assertEquals(expected, assertThrows(IllegalStateException.class, skeleton::parseSignerAccounts).getMessage());
    assertEquals(expected, assertThrows(IllegalStateException.class, skeleton::parseSignerPublicKeys).getMessage());
  }

  /// The overloads that take their instructions or accounts directly skip the parsing entry points
  /// entirely, so guarding only those left this route open: a contradictory payload still produced a
  /// mutable, signable transaction whose fee payer was the blockhash.
  @Test
  void theDirectCreateTransactionOverloadsAreGuardedToo() {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(moreSignersThanAddresses());
    final String expected = "Header declares 1 required signatures but only 0 addresses are included.";

    assertEquals(
        expected,
        assertThrows(IllegalStateException.class, () -> skeleton.createTransaction(List.of())).getMessage()
    );
    assertEquals(
        expected,
        assertThrows(IllegalStateException.class, () -> skeleton.createTransaction(new AccountMeta[0])).getMessage()
    );
    assertEquals(
        expected,
        assertThrows(
            IllegalStateException.class,
            () -> skeleton.createTransaction(List.of(), (AddressLookupTable) null)
        ).getMessage()
    );
  }

  /// The same contradiction in a v0 message, which resolves its accounts through a different path
  /// ([TransactionSkeleton#parseInstructionsWithoutTableAccounts] and the lookup-table overloads)
  /// and so needs its own guard: one signature-count byte, the `0x80` version byte, three header
  /// bytes, an address count, the blockhash, an instruction count and a lookup-table count.
  private static byte[] versionedMoreSignersThanAddresses() {
    final byte[] data = new byte[40];
    data[0] = 0;             // serialized signature count
    data[1] = (byte) 0x80;   // versioned, version 0
    data[2] = 1;             // num_required_signatures
    data[3] = 0;             // num_readonly_signed
    data[4] = 0;             // num_readonly_unsigned
    data[5] = 0;             // address count
    // data[6..37] blockhash, data[38] instruction count, data[39] lookup table count
    return data;
  }

  @Test
  void theVersionedAccountPathIsGuardedToo() {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(versionedMoreSignersThanAddresses());
    assertTrue(skeleton.isVersioned());
    assertEquals(1, skeleton.numSignatures());
    assertEquals(0, skeleton.numIncludedAccounts());

    final String expected = "Header declares 1 required signatures but only 0 addresses are included.";
    assertEquals(
        expected,
        assertThrows(IllegalStateException.class, skeleton::parseInstructionsWithoutTableAccounts).getMessage()
    );
    assertEquals(
        expected,
        assertThrows(IllegalStateException.class, () -> skeleton.parseAccounts(java.util.Map.of())).getMessage()
    );
  }

  /// The boundary is `<`, not `<=`: a transaction whose addresses are *exactly* its signers is
  /// normal — a single self-transfer has one. Tightening the guard would reject real traffic.
  @Test
  void anAddressArrayExactlyCoveringItsSignersIsFine() {
    final byte[] data = new byte[38 + 32];
    data[0] = 0;
    data[1] = 1;  // one required signature
    data[2] = 0;
    data[3] = 0;
    data[4] = 1;  // exactly one address
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertEquals(1, skeleton.numSignatures());
    assertEquals(1, skeleton.numIncludedAccounts());

    final var accounts = assertDoesNotThrow(() -> skeleton.parseAccounts());
    assertEquals(1, accounts.length);
    assertTrue(accounts[0].feePayer());
    assertEquals(0, assertDoesNotThrow(() -> skeleton.parseNonSignerAccounts()).length);
  }
}
