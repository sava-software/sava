package software.sava.rpc.json.http.ws;

/// Websocket engine timings, all in milliseconds. The constructors do not validate them.
///
/// @param reConnectDelay                 minimum time between connection attempts.
/// @param pingDelay                      how long the peer may be silent before a Ping probes it;
///                                       see [SolanaRpcWebsocket.Builder#pingDelay(long)].
/// @param subscriptionAndPingCheckDelay  how often the check cycle runs.
/// @param keepAliveDelay                 how long this end may be silent before it pokes the
///                                       peer; see
///                                       [SolanaRpcWebsocket.Builder#keepAliveDelay(long)].
/// @param subscriptionResendDelay        how long a failed subscription send waits before it is
///                                       retried; see
///                                       [SolanaRpcWebsocket.Builder#subscriptionResendDelay(long)].
public record Timings(long reConnectDelay,
                      long pingDelay,
                      long subscriptionAndPingCheckDelay,
                      long keepAliveDelay,
                      long subscriptionResendDelay) {

  /// Multiple of the ping delay used when no keep-alive delay is given; not a contract.
  static final int DEFAULT_KEEP_ALIVE_FACTOR = 2;

  /// Defaults [#keepAliveDelay()] to a multiple of [#pingDelay()], saturating instead of
  /// overflowing, so a [Long#MAX_VALUE] ping delay ("never ping") also never pokes. The re-send
  /// delay defaults as in [#Timings(long,long,long,long)].
  public Timings(final long reConnectDelay,
                 final long pingDelay,
                 final long subscriptionAndPingCheckDelay) {
    this(reConnectDelay, pingDelay, subscriptionAndPingCheckDelay, keepAliveFor(pingDelay));
  }

  /// Defaults [#subscriptionResendDelay()] to [#reConnectDelay()], floored at
  /// [#subscriptionAndPingCheckDelay()] and at 1.
  public Timings(final long reConnectDelay,
                 final long pingDelay,
                 final long subscriptionAndPingCheckDelay,
                 final long keepAliveDelay) {
    this(reConnectDelay, pingDelay, subscriptionAndPingCheckDelay, keepAliveDelay,
        resendDelayFor(reConnectDelay, subscriptionAndPingCheckDelay));
  }

  /// The derived keep-alive, shared so builders report exactly what they build. Proportional,
  /// not capped: a cap would poke more often for a caller who raised the ping delay to send
  /// fewer frames.
  static long keepAliveFor(final long pingDelay) {
    return pingDelay > Long.MAX_VALUE / DEFAULT_KEEP_ALIVE_FACTOR
        ? Long.MAX_VALUE
        : pingDelay * DEFAULT_KEEP_ALIVE_FACTOR;
  }

  /// The derived re-send delay: the greater of [#reConnectDelay()] and
  /// [#subscriptionAndPingCheckDelay()] (re-sending faster than the loop that decides to re-send
  /// is never meant), and at least 1. An explicit [#subscriptionResendDelay()] is not floored.
  static long resendDelayFor(final long reConnectDelay, final long subscriptionAndPingCheckDelay) {
    // Floored at 1: zero is legal for both inputs — no reconnect throttle, a never-parking
    // check loop — but a zero re-send deadline is rejected by the builder,
    // and a caller choosing two legal values must not be told their combination is nonsense.
    final long floored = Math.max(reConnectDelay, subscriptionAndPingCheckDelay);
    return floored > 0 ? floored : 1;
  }
}
