package software.sava.rpc.soak;

import java.time.Duration;

/// One driver of the subject: the websocket engines, the HTTP clients, the churn cycles, or a
/// deliberately broken control. [Phases] owns the clock; a workload owns what it does with it.
///
/// The lifecycle is separated into [#quiesce(Duration)] and [#drain(Duration)] because the two
/// answer different questions. Quiescing means "stop starting work and let what is in flight
/// finish", which is when the residual assertions are meaningful — a registration still live here
/// is a real residue. Draining means "close what you own", which is when the ownership
/// assertions are meaningful — a caller-owned `HttpClient` must survive it and an engine-owned
/// thread must not. Collapsing them into one `close()` would make both measurements ambiguous.
///
/// Every method is bounded by the caller. A workload that cannot finish inside its bound does
/// not fail the run's correctness — it makes the run `INCOMPLETE`, which is a different verdict
/// on purpose: nothing was disproved, the evidence is just short.
public interface Workload extends AutoCloseable {

  /// Stable, short, and used as a column value and a counter suffix, so it must not contain a
  /// tab, a comma or a newline.
  String name();

  /// Builds and starts the driver. Called during STARTUP, before `READY`: work started here is
  /// expected to be converged by the time the phase machine begins.
  void start(SoakContext ctx) throws Exception;

  /// A phase or STEADY overlay boundary. Called on the phase thread for every boundary,
  /// including the overlays, and must return promptly — a workload that blocks here delays the
  /// whole schedule. Start the work, do not wait for it.
  void onPhase(Phase phase);

  /// Stop starting new work and let what is in flight finish, within `bound`.
  void quiesce(Duration bound) throws Exception;

  /// Close what this workload owns — engines, managers, per-cycle clients — within `bound`,
  /// leaving anything the harness owns (the shared `HttpClient`, the shared executor) alone.
  void drain(Duration bound) throws Exception;

  /// Final teardown. Must not throw: it runs in SHUTDOWN, where a thrown exception would cost
  /// the run its artifacts.
  @Override
  void close();
}
