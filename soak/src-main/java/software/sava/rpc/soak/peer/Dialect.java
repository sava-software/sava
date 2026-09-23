package software.sava.rpc.soak.peer;

/// The server dialect a websocket port speaks for the whole run.
///
/// `DESIGN.md` §7 fixes the assignment: even positions in `SOAK_WS_PORTS` order are [#AGAVE], odd
/// are [#HELIUS]. Fixing it per port (rather than per connection) means a reconnect lands on the
/// same dialect, so a client-side gap after a reconnect is never explained away by "the peer
/// changed its mind".
enum Dialect {

  /// Agave: dedupes ids, answers a stale unsubscribe with `-32602 "Invalid subscription id."`.
  AGAVE(IdPolicy.DEDUPE),

  /// Helius: distinct ids, answers a stale unsubscribe with a quiet `false`.
  HELIUS(IdPolicy.DISTINCT);

  private final IdPolicy idPolicy;

  Dialect(final IdPolicy idPolicy) {
    this.idPolicy = idPolicy;
  }

  IdPolicy idPolicy() {
    return idPolicy;
  }

  /// Even index -> agave, odd -> helius, per `DESIGN.md` §7.
  static Dialect forPortIndex(final int index) {
    return (index & 1) == 0 ? AGAVE : HELIUS;
  }

  String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
