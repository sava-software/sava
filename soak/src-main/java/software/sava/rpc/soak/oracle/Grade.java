package software.sava.rpc.soak.oracle;

/// What kind of promise a property rests on, which is the only thing that decides whether it may
/// fail a run.
///
/// The harness drives a published library its authors own. Asserting something the library never
/// promised turns a soak into a source of false findings, and a false finding costs more than a
/// missed one because it burns the owner's trust in every later one. So each property names its
/// oracle's grade, and only A, B and C may FAIL; D is measured and reported.
public enum Grade {

  /// Stated in a public javadoc or a published signature's contract.
  A_PUBLIC_CONTRACT,
  /// A documented internal invariant — a comment at the declaration, a named field whose purpose
  /// the source states — that a caller can rely on but the javadoc does not spell out.
  B_DOCUMENTED_INVARIANT,
  /// Established by the controlled peer: the harness made the call and the peer recorded what it
  /// sent, so the expected value is not an inference about sava at all.
  C_PEER_ESTABLISHED,
  /// A number, not a promise. Reported with its mechanism named; never a FAIL — except where a
  /// margin gate is stated in the property's own bound (W5-C).
  D_MEASUREMENT,
  /// A named non-property: considered and deliberately not asserted, recorded so a later
  /// contributor sees the decision rather than adding the "obvious" check.
  NONE;

  /// True for the grades whose failure fails the run.
  public boolean fatal() {
    return this == A_PUBLIC_CONTRACT || this == B_DOCUMENTED_INVARIANT || this == C_PEER_ESTABLISHED;
  }
}
