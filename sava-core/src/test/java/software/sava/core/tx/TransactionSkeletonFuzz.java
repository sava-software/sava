package software.sava.core.tx;

/// Jazzer entry point for transaction wire parsing, the widest untrusted-input surface in
/// the library (RPC responses, user-pasted base64). The malformed-input contract is
/// "garbage in -> RuntimeException out": any [RuntimeException] from deserialization or the
/// offset-walking parsers is tolerated. Jazzer still flags what that contract does not
/// permit — hangs (its own timeout), stack/heap exhaustion, and any non-[RuntimeException]
/// throwable — and this harness adds the cross-method structural invariants that must hold
/// whenever a parse fully succeeds.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :sava-core:fuzzTxSkeleton [-PmaxFuzzTime=<seconds>]`.
public final class TransactionSkeletonFuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
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

    // The parsers walk raw offsets, so a malformed body may throw here — tolerated. But if
    // the whole pipeline succeeds, the results must be mutually consistent; a violation is
    // an AssertionError, which is not caught below and so surfaces as a finding.
    try {
      final var accounts = skeleton.parseAccounts();
      final var instructions = skeleton.parseInstructions(accounts);
      final var signerAccounts = skeleton.parseSignerAccounts();
      final var signerKeys = skeleton.parseSignerPublicKeys();
      skeleton.id();
      skeleton.feePayer();
      skeleton.base58BlockHash();

      if (accounts.length != skeleton.numIncludedAccounts()) {
        throw new AssertionError("parseAccounts length != numIncludedAccounts");
      }
      if (instructions.length != skeleton.numInstructions()) {
        throw new AssertionError("parseInstructions length != numInstructions");
      }
      if (signerAccounts.length != signerKeys.length) {
        throw new AssertionError("signer accounts and keys differ in length");
      }
      for (int i = 0; i < signerKeys.length; ++i) {
        if (!signerAccounts[i].publicKey().equals(signerKeys[i])) {
          throw new AssertionError("signer account " + i + " disagrees with signer key");
        }
      }
    } catch (final RuntimeException tolerated) {
      // malformed body reached by a valid header — parsing may fail, that is in contract
    }
  }

  private TransactionSkeletonFuzz() {
  }
}
