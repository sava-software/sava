package software.sava.rpc.soak.oracle;

/// One row of the correctness ledger: what is claimed, what makes it claimable, and the bound
/// past which the claim is broken.
///
/// `guarantee` carries the citation — the javadoc sentence, the documented invariant, or the
/// peer mechanism — because a property whose guarantee cannot be quoted is an opinion, and the
/// report prints the citation next to the verdict so a reader can check the oracle instead of
/// trusting it.
///
/// @param id        the fixed id used in `properties.tsv`, in findings and in the report
/// @param grade     which kind of promise the oracle rests on; only [Grade#fatal()] grades FAIL
/// @param statement what is asserted, in one sentence
/// @param guarantee why it may be asserted, with its citation
/// @param bound     the numeric or structural bound the evaluation applies
public record Property(String id, Grade grade, String statement, String guarantee, String bound) {

  public Property {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("a property needs an id");
    }
  }
}
