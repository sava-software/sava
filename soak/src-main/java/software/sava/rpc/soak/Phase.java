package software.sava.rpc.soak;

/// The phases of one run, in the order [Phases] drives them.
///
/// `HTTP_QUIET`, `HTTP_LOADED`, `OVERLAP` and `FAULT_STORM` are overlays inside `STEADY` rather
/// than stages of their own: they exist so that a latency or resource number can be quoted for
/// a named sub-window ("after the storm, compared with before it") instead of being averaged
/// over a run whose load deliberately changed underneath it.
public enum Phase {

  /// Peers reachable, subject verified, redaction and detector self-tests passed, engines
  /// connected and the first registrations confirmed. `READY` is printed at its end.
  STARTUP,
  /// Full traffic, faults off. Correctness is fully on — only trend fits drop these samples.
  WARMUP,
  /// Full traffic with the fault schedule running.
  STEADY,
  /// STEADY overlay: HTTP driven at a tenth of its rate, alternating with [#HTTP_LOADED].
  HTTP_QUIET,
  /// STEADY overlay: HTTP driven at its configured rate.
  HTTP_LOADED,
  /// STEADY overlay, a momentary trigger: a fan-out of large requests coinciding with a peer
  /// burst, plus the commonPool deadline-timeliness probe (W4-C).
  OVERLAP,
  /// STEADY overlay: the fault rate and destructive cap raised for `SOAK_STORM_SECONDS`.
  FAULT_STORM,
  /// Faults off, no new work, in-flight work drained, subscriptions unsubscribed.
  QUIESCE,
  /// Engines, managers and churn clients closed; caller-owned resource ownership asserted.
  DRAIN,
  /// Final gauge, counters, verdict event, streams closed.
  SHUTDOWN;

  /// True for the four [#STEADY] overlays, which never replace the major phase in `client.csv`
  /// or in the gauge: a row is always attributed to the stage that owns the clock.
  public boolean overlay() {
    return this == HTTP_QUIET || this == HTTP_LOADED || this == OVERLAP || this == FAULT_STORM;
  }
}
