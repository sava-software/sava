package software.sava.rpc.json.http.ws;

/// Every delay is in MILLISECONDS.
///
/// @param reConnectDelay                 how long to wait before re-attempting a connection.
/// @param pingDelay                      how long the peer may be silent before it is asked
///                                       whether it is still there, and the minimum spacing
///                                       between two such asks.
/// @param subscriptionAndPingCheckDelay  how often the check cycle runs.
/// @param keepAliveDelay                 how long *this* end may be silent before it pokes the
///                                       peer. Distinct from [#pingDelay()] because it answers a
///                                       different question: the peer talking proves nothing is
///                                       wrong, so this is not a detection deadline but a guard
///                                       against something ageing out a connection on what it
///                                       receives *from us* while we are happily reading.
///                                       <p>
///                                       That is a narrower class of peer than it first appears,
///                                       and worth naming, because it decides when this setting
///                                       matters at all. Ordinary proxies and load balancers —
///                                       nginx, ALB, HAProxy, Envoy — reset their idle timers on
///                                       traffic in either direction, so a talkative server keeps
///                                       the connection alive on its own and this delay never
///                                       binds. When both ends do go quiet, [#pingDelay()]
///                                       governs and fires first. What is left is a server, or an
///                                       intermediary, that enforces client liveness specifically:
///                                       for that, size this against whatever bound it applies,
///                                       which is not the 60s an idle-timeout discussion suggests.
///                                       <p>
///                                       The default derives from [#pingDelay()] and so is
///                                       proportional, not bounded: raising the detection deadline
///                                       raises this too, with no ceiling. That is deliberate —
///                                       clamping it would collapse it onto [#pingDelay()] for any
///                                       larger value, sending *more* frames to a caller who
///                                       raised the delay to send fewer — but it does mean a
///                                       caller with a real outbound bound should state this
///                                       explicitly rather than inherit it.
/// @param subscriptionResendDelay        how long a subscription send that FAILED waits before
///                                       it is retried — a successfully sent request is never
///                                       re-sent on its own connection; the server's answer is
///                                       what releases it, and four of these windows with no
///                                       answer replace the connection through the error seam
///                                       instead. The same window paces the retry of an
///                                       un-subscription the server refused transiently.
///                                       Formerly [#reConnectDelay()] did double duty
///                                       here, which made one number answer two questions that
///                                       disagree about their edge cases: zero is a coherent
///                                       reconnect throttle meaning "do not throttle", but as a
///                                       retry deadline it means "retry whenever a millisecond
///                                       has passed" — a hot loop for as long as a failing
///                                       socket keeps failing. A subscription the server
///                                       rejected as a request defect is released, while one it
///                                       refused transiently stays pending for this window, so
///                                       the two readings were furthest apart exactly where it
///                                       mattered.
public record Timings(long reConnectDelay,
                      long pingDelay,
                      long subscriptionAndPingCheckDelay,
                      long keepAliveDelay,
                      long subscriptionResendDelay) {

  /// Multiple of the ping delay used when no keep-alive delay is given. Not part of the
  /// contract: a caller who wants a particular keep-alive states it rather than deriving it.
  static final int DEFAULT_KEEP_ALIVE_FACTOR = 2;

  /// Validated here rather than only in the builder, because this constructor is public and a
  /// nonsensical delay is nonsensical however it arrives. The builder's own guards cover a
  /// different thing: there, zero is the sentinel for "not given", so rejecting it stops a
  /// caller being handed the derived default while believing their value was taken.
  ///
  /// Which values are nonsense differs per delay, and the two that permit zero permit it because
  /// zero says something. A zero [#reConnectDelay()] means "do not throttle reconnects", and a
  /// zero [#subscriptionAndPingCheckDelay()] means the check loop never parks. Zero is not
  /// coherent for the other three: each is a "how long before acting" bound where zero collapses
  /// to acting every time round, which is a way of asking for a flood rather than a cadence.
  public Timings {
    requireNotNegative(reConnectDelay, "reConnectDelay");
    requirePositive(pingDelay, "pingDelay");
    requireNotNegative(subscriptionAndPingCheckDelay, "subscriptionAndPingCheckDelay");
    requirePositive(keepAliveDelay, "keepAliveDelay");
    requirePositive(subscriptionResendDelay, "subscriptionResendDelay");
  }

  private static void requirePositive(final long value, final String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive: " + value);
    }
  }

  private static void requireNotNegative(final long value, final String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative: " + value);
    }
  }

  /// Defaults the keep-alive to a multiple of the ping delay, so a caller who has tuned only the
  /// detection deadline still gets a keep-alive proportionate to it.
  ///
  /// The multiply saturates rather than wrapping. A very large [#pingDelay()] is how a caller
  /// says "do not ping" — [Long#MAX_VALUE] being the idiomatic form — and an overflow there
  /// would land on a negative delay, which every comparison reads as long overdue: the one
  /// setting that means *never* would become a ping on every check cycle and every inbound
  /// ping or pong. Saturating keeps "never ping" meaning "never poke either".
  public Timings(final long reConnectDelay,
                 final long pingDelay,
                 final long subscriptionAndPingCheckDelay) {
    this(reConnectDelay, pingDelay, subscriptionAndPingCheckDelay, keepAliveFor(pingDelay));
  }

  /// Defaults the re-send deadline, keeping the historical behaviour for a caller who set the
  /// other four: it follows [#reConnectDelay()], which is what it used to be.
  public Timings(final long reConnectDelay,
                 final long pingDelay,
                 final long subscriptionAndPingCheckDelay,
                 final long keepAliveDelay) {
    this(reConnectDelay, pingDelay, subscriptionAndPingCheckDelay, keepAliveDelay,
        resendDelayFor(reConnectDelay, subscriptionAndPingCheckDelay));
  }

  /// Package-private so [SolanaRpcWebsocketBuilder#keepAliveDelay()] reports the value its
  /// [SolanaRpcWebsocketBuilder#create()] would build rather than deriving it a second time.
  static long keepAliveFor(final long pingDelay) {
    return pingDelay > Long.MAX_VALUE / DEFAULT_KEEP_ALIVE_FACTOR
        ? Long.MAX_VALUE
        : pingDelay * DEFAULT_KEEP_ALIVE_FACTOR;
  }

  /// Follows [#reConnectDelay()], but never drops below the check cadence.
  ///
  /// The floor only ever binds when a caller sets a reconnect throttle shorter than the interval
  /// at which anything is checked — in practice, zero. Re-sending faster than the loop that
  /// decides whether to re-send is not a setting anyone means to choose, and reaching it through
  /// a *reconnect* knob is not choosing it at all. A caller who does want it says so through
  /// [#subscriptionResendDelay()], which is not floored.
  static long resendDelayFor(final long reConnectDelay, final long subscriptionAndPingCheckDelay) {
    // Floored at 1: zero is legal for both inputs — no reconnect throttle, a never-parking
    // check loop — but a zero re-send deadline is rejected by this record's own validation,
    // and a caller choosing two legal values must not be told their combination is nonsense.
    final long floored = Math.max(reConnectDelay, subscriptionAndPingCheckDelay);
    return floored > 0 ? floored : 1;
  }
}
