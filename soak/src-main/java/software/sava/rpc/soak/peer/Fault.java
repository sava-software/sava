package software.sava.rpc.soak.peer;

/// One scheduled fault: a kind, the port it belongs to, and the peer-side counter value that fires
/// it.
///
/// The trigger is an **ordinal, never an instant**. `counter=value` means "the `value`th `counter`
/// event on `port`", and because those counters advance only with the client's own traffic, the
/// same seed applies the same faults to the same operations on a machine under different load. A
/// time-keyed schedule cannot do that, and a replay that does not reproduce is worthless as a
/// regression source.
///
/// @param ordinal 1-based position in `fault-schedule.tsv`, the identity the client and the report
///                join on.
/// @param counter one of `conn`, `sub`, `notify`, `rpc`, or `rpcMethod:<method>`.
/// @param params  kind-specific detail, `-` when there is none (e.g. `DELAY_RESPONSE` carries its
///                seeded millisecond delay here so the schedule states it before the run).
record Fault(int ordinal, FaultKind kind, int port, String counter, long trigger, String params) {

  static final String HEADER = "ordinal\tkind\tscope\tport\ttrigger\tparams";

  /// The `<counter>=<n>` text of the schedule's `trigger` column.
  String triggerText() {
    return counter + '=' + trigger;
  }

  /// One `fault-schedule.tsv` row. `port` is the resolved ephemeral port so the row can be read
  /// without a second lookup; the canonical digest is taken over the port *index* instead, so it is
  /// stable across runs (see [FaultSchedule#canonicalSha256()]).
  String row() {
    return ordinal + "\t" + kind + '\t' + kind.scope() + '\t' + port + '\t' + triggerText() + '\t' + params;
  }
}
