package software.sava.rpc.soak.oracle;

import java.util.List;

/// The checks this harness deliberately does not make, and why. Documentation with a compiler
/// behind it: the ids below are the real [Property] rows, so a non-assertion cannot quietly
/// disappear from `properties.tsv` while this explanation stays behind.
///
/// A soak that asserts everything it can observe produces findings the library never promised to
/// avoid, and the cost of one false finding is the owner's attention on every later true one.
/// Each row here was considered, declined, and written down so that the next contributor reads a
/// decision rather than an omission.
///
/// - **[Properties#NP_1] — delivery across a disconnect.** The client does not promise that a
///   notification written by the peer before a retirement is delivered after the successor
///   connection is adopted. The sequence oracle therefore resets at every epoch boundary; a gap
///   that straddles a reconnect is data for the recovery section, not a failure.
/// - **[Properties#NP_2] — exactly-once failure notification.** `connect()`'s javadoc explicitly
///   disclaims correlation between the connect future settling and the lifecycle callbacks
///   firing. Issue #52's capture counts the double observation and reports its attribution; it is
///   never a FAIL, and exit code 2 (`PASS-WITH-FINDING`) exists precisely so that evidence can be
///   surfaced without pretending a promise was broken.
/// - **[Properties#NP_3] — head-of-line independence between consumers.** The engine dispatches on
///   the JDK listener thread, so a slow consumer delays its neighbours by design. W2-D measures
///   the coupling as a grade-D number; if the measurement shows coupling the javadoc does not
///   warn about, that is a documentation finding to report to the owner.
/// - **[Properties#NP_4] — ordering across channels.** Only per-key order is established, because
///   only per-key order is assigned by the peer under a single write lock. Nothing orders an
///   `accountNotification` against a `slotNotification`, and asserting one would be asserting the
///   harness's own emission schedule.
public final class NonAssertions {

  /// The declined checks, as ledger rows. They are written to `properties.tsv` with grade
  /// [Grade#NONE] so the file records the decision beside the assertions.
  public static final List<Property> ALL = List.of(
      Properties.NP_1, Properties.NP_2, Properties.NP_3, Properties.NP_4);

  private NonAssertions() {
  }
}
