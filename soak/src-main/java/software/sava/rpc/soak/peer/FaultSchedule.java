package software.sava.rpc.soak.peer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

/// The run's fault schedule: built before the client starts, written to `fault-schedule.tsv`,
/// consumed by ordinal (`DESIGN.md` §6).
///
/// Two properties matter more than the catalogue itself.
///
/// **Ordinal-keyed.** A trigger is `<counter>=<n>` over a counter the *peer* owns, so it advances
/// only with the client's own traffic. The same seed therefore applies the same fault to the same
/// operation whatever else the machine is doing, which is what makes a soak finding replayable into
/// a regression test.
///
/// **Written before the run.** The file is the plan, `faults-applied.tsv` is the outcome, and the
/// report diffs them. A fault that never fired shows up as absent rather than as a fault that
/// silently did not exist, and one that fired with nothing to hit is recorded `no_target` so the
/// fidelity ratio has an honest denominator.
final class FaultSchedule {

  /// Faults per (counter, port) domain are bounded so a high notification rate cannot turn a
  /// 4-minute validate run into a hundred-thousand-row schedule. Reached only when
  /// `range / SOAK_FAULT_RATE` would exceed it, in which case the effective interval widens.
  private static final int MAX_PER_DOMAIN = 1_000;
  private static final int MAX_PLANNED = 20_000;
  private static final int STORM_INTERVAL = 5;

  /// The `SOAK_WS_PORTS` index the `OVERFLOW` engine connects on (`ws.EngineProfile.OVERFLOW`, the
  /// only engine whose `maxMessageLength` is below the peer's oversized message). Two placements
  /// use it, for the same reason: F5 restricts its whole plan to it, and an ordinary run's
  /// `MESSAGE_OVERFLOW` minimum is placed on it, so the one instance each is guaranteed lands where
  /// W2-B is evaluated. On every other engine the same message is an ordinary large notification.
  private static final int OVERFLOW_PORT_INDEX = 3;

  /// The per-kind minimums land inside the first `1/this` of a domain (stage 1); see the
  /// placement comment there for the measurement behind it.
  private static final int MINIMUM_PLACEMENT_DIVISOR = 4;

  /// The peer half of a `SOAK_CONTROL` id: what the peer does differently for this one run.
  ///
  /// A control is never drawn from the weighted fill — one that fired at a seeded ordinal would
  /// prove only that the detector can see one instance — so it is expressed here instead, in one of
  /// four shapes: a kind applied on *every* eligible event, a kind suppressed everywhere, a plan
  /// restricted to one kind at a stated density, or no plan at all. [#controlFor] maps each id to
  /// its shape.
  ///
  /// @param always           applied on every event of its scope, or null.
  /// @param never            suppressed everywhere (the `NEVER_APPLY_KIND` control), or null.
  /// @param minConnOrdinal   `always` only applies from this connection ordinal onward (D1 needs a
  ///                         *replayed* subscribe, which only exists on a reconnect; `CROSSTALK`
  ///                         needs a second connection to divert to).
  /// @param only             the one kind the plan may contain, or null for the ordinary schedule.
  ///                         The per-kind minimums, the weighted fill and the storms are all skipped
  ///                         when it is set, so an `F*` row's property is read against its own fault
  ///                         and no other.
  /// @param onlyEverySeconds seconds of *expected traffic* between two `only` placements; see
  ///                         [#planRestricted].
  /// @param onlyBurst        consecutive counter values each placement takes, so a control can ask
  ///                         for a burst (F8) rather than an even drizzle.
  /// @param onlyPortIndex    index into `SOAK_WS_PORTS` to restrict `only` to, or -1 for every port.
  /// @param noFaults         plan nothing at all: the row's injection is client side and a peer
  ///                         fault would confound it.
  record Control(String id,
                 FaultKind always,
                 FaultKind never,
                 int minConnOrdinal,
                 FaultKind only,
                 int onlyEverySeconds,
                 int onlyBurst,
                 int onlyPortIndex,
                 boolean noFaults) {

    static final Control NONE = new Control("", null, null, 1, null, 0, 0, -1, false);

    /// One control-only kind applied to every event of its scope, from `minConnOrdinal` onward,
    /// over the ordinary schedule.
    static Control always(final String id, final FaultKind kind, final int minConnOrdinal) {
      return new Control(id, kind, null, minConnOrdinal, null, 0, 0, -1, false);
    }

    /// One kind accepted into the plan and then never applied (D12): the fidelity gate must see it.
    static Control never(final String id, final FaultKind kind) {
      return new Control(id, null, kind, 1, null, 0, 0, -1, false);
    }

    /// A plan of one kind and nothing else.
    static Control only(final String id,
                        final FaultKind kind,
                        final int everySeconds,
                        final int burst,
                        final int portIndex) {
      return new Control(id, null, null, 1, kind, everySeconds, burst, portIndex, false);
    }

    /// A row the peer must stay out of: no plan, no injection.
    static Control clientSide(final String id) {
      return new Control(id, null, null, 1, null, 0, 0, -1, true);
    }

    /// A row that runs the peer's ordinary schedule, named so the artifacts still carry its id.
    static Control ordinary(final String id) {
      return new Control(id, null, null, 1, null, 0, 0, -1, false);
    }
  }

  private final List<Fault> planned;
  private final ConcurrentHashMap<String, Fault> byTrigger;
  private final Control control;
  private final EnumSet<FaultKind> omitted;
  private final String canonicalSha256;
  private volatile String fileSha256 = "";

  private FaultSchedule(final List<Fault> planned,
                        final Control control,
                        final EnumSet<FaultKind> omitted,
                        final String canonicalSha256) {
    this.planned = List.copyOf(planned);
    this.control = control;
    this.omitted = omitted;
    this.canonicalSha256 = canonicalSha256;
    this.byTrigger = new ConcurrentHashMap<>(Math.max(16, planned.size() << 1));
    for (final var fault : planned) {
      byTrigger.put(key(fault.counter(), fault.port(), fault.trigger()), fault);
    }
  }

  private static String key(final String counter, final int port, final long trigger) {
    return counter + '\u0000' + port + '\u0000' + trigger;
  }

  // --- construction -----------------------------------------------------------------------------

  static FaultSchedule of(final long seed,
                          final PeerMain.Config config,
                          final int[] wsPorts,
                          final int httpPort) {
    final var random = new SplittableRandom(seed ^ 0x5A7A_50A4_FA17L);
    final var control = controlFor(config.control(), config);
    final long minutes = Math.max(1, config.durationSeconds() / 60);
    final var ranges = new Ranges(
        connRange(config, wsPorts.length),
        Math.max(32, config.wsSubscriptions() + (long) config.wsChurnPerMinute() * minutes),
        Math.max(256, (long) config.wsNotifyRps() * config.durationSeconds()),
        Math.max(64, (long) config.httpRps() * config.durationSeconds()));

    final var accepted = new LinkedHashMap<String, Fault>();
    final var omitted = EnumSet.noneOf(FaultKind.class);
    final var selectable = new ArrayList<FaultKind>();
    for (final var kind : FaultKind.values()) {
      if (!kind.controlOnly()) {
        selectable.add(kind);
      }
    }

    // `SOAK_FAULT_RATE=0` means *no scheduled faults*, in every stage below, and that is the whole
    // mechanism of the `no-faults` control: its run is the noise baseline every later run's
    // exception floor is read against, so its plan has to be empty. The rate used to be clamped to
    // 1, which made the quietest row in controls.tsv produce the densest schedule the harness can
    // build. It wins over an `F*` control's own density too: an operator who asked for no faults
    // gets none, whatever else the row says. `Control.noFaults` plans nothing for a different
    // reason, recorded there.
    //
    // An empty plan is still written and still digested: `SCHEDULE_PLANNED=0` beside the SHA-256 of
    // a file holding only its header row is provable evidence that nothing was planned, where a
    // missing file could not be told apart from a peer that failed to write one.
    final boolean planFaults = config.faultRate() > 0 && !control.noFaults();
    if (planFaults) {
      if (control.only() == null) {
        planOrdinary(accepted, omitted, selectable, config, random, wsPorts, httpPort, ranges);
      } else {
        planRestricted(accepted, control, config, random, wsPorts, httpPort, ranges);
      }
    }

    final var sorted = new ArrayList<>(accepted.values());
    // Ordered by port INDEX, not by the ephemeral port number: the ordinals this numbering hands
    // out are the identity `faults-applied.tsv` and the report join on, and `canonicalDigest` hashes
    // them, so ordering by the port value would make both a function of what the kernel happened to
    // return from five `bind(0)` calls. Two runs at one seed would then disagree on the canonical
    // digest that is documented (DESIGN.md §7) to be stable across them.
    sorted.sort(Comparator.comparing((final Fault f) -> f.kind().scope())
        .thenComparing(Fault::counter)
        .thenComparingInt(f -> portIndex(wsPorts, f.port()))
        .thenComparingLong(Fault::trigger)
        .thenComparing(f -> f.kind().name()));
    final var numbered = new ArrayList<Fault>(sorted.size());
    for (int i = 0; i < sorted.size(); ++i) {
      final var f = sorted.get(i);
      numbered.add(new Fault(i + 1, f.kind(), f.port(), f.counter(), f.trigger(), f.params()));
    }

    return new FaultSchedule(numbered, control, omitted, canonicalDigest(numbered, wsPorts));
  }

  /// How many `conn`, `sub`, `notify` and `rpc` events the peers expect over the configured run.
  /// Every density in this file is expressed against these rather than against the clock, which is
  /// what makes a schedule a function of `(seed, config)` and a rerun land on the same operations.
  private record Ranges(long conn, long sub, long notifications, long rpc) {

    long of(final FaultKind.Scope scope) {
      return switch (scope) {
        case CONN -> conn;
        case SUB -> sub;
        case NOTIFY -> notifications;
        case RPC -> rpc;
      };
    }
  }

  /// The `conn` domain: how many connection ordinals one ws port can plausibly reach, which is
  /// `1 + destructivePerHour x durationSeconds / 3600 / ports`, rounded up and floored at 2.
  ///
  /// A connection ordinal is not a function of the clock. The client opens one connection per
  /// engine and opens the next only after the current one is **retired**, and what retires a
  /// connection is overwhelmingly a destructive fault — which `SOAK_DESTRUCTIVE_PER_HOUR` caps for
  /// the whole run, shared across the ports. So the reachable ordinals per port are one (the first
  /// connect, which needs no retirement) plus that port's share of the run's retirement budget; the
  /// floor of 2 keeps the reconnect ordinal in the domain for a run whose budget rounds to nothing,
  /// since the churn engine and the client's own escalations still retire occasionally.
  ///
  /// The old estimate — one connection per 30 s of wall clock — described a run in which something
  /// unspecified keeps ending connections, and it overshoots exactly where the cap binds hardest.
  /// Measured on the 2026-09-21 validate run: seven of the ten CONN kinds had their guaranteed
  /// minimum placed at ordinals 4-8 on ports that saw one or two connections all run, so those
  /// kinds were planned, never reached, and reported UNEXERCISED — a property of the estimate, not
  /// of the client. Every bound below is unchanged and the value is still a pure function of
  /// `(seed, config)`, so the plan stays seed-reproducible.
  private static long connRange(final PeerMain.Config config, final int wsPortCount) {
    final long ports = Math.max(1, wsPortCount);
    final long retirements = Math.ceilDiv((long) config.destructivePerHour() * config.durationSeconds(),
        3600L * ports);
    return Math.max(2, 1 + retirements);
  }

  /// The ordinary run's three planning stages: per-kind minimums, weighted fill, storms.
  private static void planOrdinary(final Map<String, Fault> accepted,
                                   final EnumSet<FaultKind> omitted,
                                   final List<FaultKind> selectable,
                                   final PeerMain.Config config,
                                   final SplittableRandom random,
                                   final int[] wsPorts,
                                   final int httpPort,
                                   final Ranges ranges) {
    // 1. per-kind minimums, spread over the kind's own counter domain. These are the floor: the
    //    destructive cap bounds the weighted fill below, it never deletes a minimum, because a
    //    kind the run never exercised is reported UNEXERCISED and that must be a real finding
    //    rather than an artefact of the cap.
    //
    //    Every kind of one scope shares that scope's counter and domain, so the even spread
    //    `domain * (m + 1) / (want + 1)` is the *same* ordinal for all of them and only the port
    //    cycle ever separated them. A scope with more kinds than ports therefore lost its surplus
    //    kinds to `putIfAbsent` — silently, because the drop never reached `omitted`. Measured
    //    2026-09-21: five of the ten CONN kinds, SWALLOW_PING among them, were never planned at
    //    all while the run reported `omittedKinds=[]` and W1-H still claimed a clean pass on the
    //    half of its statement that a swallowed ping is the only way to reach. So each kind gets
    //    its own offset inside the scope, and a taken key probes forward over the whole domain
    //    before the kind may be dropped — and a drop is recorded.
    //
    //    `SOAK_FAULT_MIN_PER_KIND=0` asks for no guaranteed instance at all and gets exactly that:
    //    nothing is placed and nothing is reported omitted, because `omitted` means "wanted here
    //    and could not be placed", not "absent".
    final int scopes = FaultKind.Scope.values().length;
    final int[] scopeKinds = new int[scopes];
    for (final var kind : selectable) {
      ++scopeKinds[kind.scope().ordinal()];
    }
    final int[] scopeSeen = new int[scopes];
    int kindIndex = 0;
    final int[] destructiveUsed = {0};
    final int want = Math.max(0, config.faultMinPerKind());
    if (want > 0) {
      for (final var kind : selectable) {
        final long range = ranges.of(kind.scope());
        // Counted before the domain check so a kind omitted for a short run does not shift the
        // offsets of the kinds after it: the plan must stay a pure function of seed and config.
        final long scopeIndex = scopeSeen[kind.scope().ordinal()]++;
        if (range < want) {
          omitted.add(kind);
          ++kindIndex;
          continue;
        }
        int placed = 0;
        for (int m = 0; m < want; ++m) {
          final boolean byMethod = kind.scope() == FaultKind.Scope.RPC && (m & 1) == 1;
          final var counter = byMethod ? "rpcMethod:getAccountInfo" : kind.scope().counter();
          // getAccountInfo is 40 % of the HTTP mix (DESIGN.md §13), so its own counter advances at
          // roughly that fraction of the rpc counter.
          final long domain = byMethod ? Math.max(1, (range * 2) / 5) : range;
          // MESSAGE_OVERFLOW's *guaranteed* instances go to the OVERFLOW engine's port, because it
          // is the only engine whose maxMessageLength the message exceeds and therefore the only
          // place W2-B is evaluated at all: on any other port the same message is an ordinary large
          // notification (DESIGN.md §6), so a minimum landing there guarantees delivery, not an
          // overflow. The weighted fill still places the kind anywhere, so "lands on every port"
          // stays true of the plan as a whole; this only pins the floor where the property lives.
          final int port = kind.scope() == FaultKind.Scope.RPC
              ? httpPort
              : kind == FaultKind.MESSAGE_OVERFLOW
              ? wsPorts[Math.min(OVERFLOW_PORT_INDEX, wsPorts.length - 1)]
              : wsPorts[(kindIndex + m) % wsPorts.length];
          // One draw per (kind, m), taken before the probe so a contended key costs no randomness
          // and the params a kind ends up with do not depend on how far it had to walk.
          final var faultParams = params(kind, config, random);
          // The minimums are spread over the first part of the domain only. Every domain here is
          // an upper bound built from configured ceilings, not a forecast of what the run reaches:
          // measured 2026-09-22 on a validate run, the per-port sub counter reached 31-77 of an
          // estimated 504, the notify counter 41906 of 48000, and three ports never saw a second
          // connection. A floor placed at the middle of such a domain is planned and never
          // reached, which the report states honestly but which leaves the property it was meant
          // to exercise UNEXERCISED. The weighted fill below still covers the whole domain, so the
          // campaign's faults are not front-loaded; only the guaranteed instances are.
          // CONN's domain is derived from the destructive cap (an estimate of what the run reaches,
          // not a ceiling), and packing its minimums into a quarter of it puts every connection
          // fault of a port at consecutive attempts — measured on a pilot: reset, 500, a 30 s
          // stall, a bad accept and a 20 s refusal on one port's first five attempts, which no
          // recovery budget survives and no real peer produces outside a storm. So CONN spreads
          // over its whole domain; the other scopes keep the quarter.
          final long placement = kind.scope() == FaultKind.Scope.CONN
              ? domain
              : Math.max(want + 1, domain / MINIMUM_PLACEMENT_DIVISOR);
          final long spread = Math.max(1, (placement * (m + 1)) / (want + 1));
          final long stride = Math.max(1, placement / (Math.max(1, scopeKinds[kind.scope().ordinal()]) * (long) (want + 1)));
          final long first = Math.floorMod((spread - 1) + scopeIndex * stride, placement);
          for (long probe = 0; probe < domain; ++probe) {
            final long trigger = 1 + ((first + probe) % domain);
            final var slot = key(counter, port, trigger);
            if (!accepted.containsKey(slot)) {
              accepted.put(slot, new Fault(0, kind, port, counter, trigger, faultParams));
              ++placed;
              if (kind.destructive()) {
                ++destructiveUsed[0];
              }
              break;
            }
          }
        }
        if (placed == 0) {
          // The whole domain is already spoken for, so this kind has no guaranteed instance. Say so:
          // an unplanned kind is invisible to the report's unapplied-kind gate, which only iterates
          // kinds that reached the plan, so silence here would read downstream as a clean run.
          omitted.add(kind);
        }
        ++kindIndex;
      }
    }

    // 2. weighted fill at SOAK_FAULT_RATE operations between faults, per (counter, port) domain.
    //    Stepping each domain by the rate gives the right *global* rate: a domain of `range` ops
    //    contributes range/rate faults, and the domains sum to the run's total operations.
    final long destructiveBudget = Math.max(destructiveUsed[0],
        Math.round(config.destructivePerHour() * (config.durationSeconds() / 3600.0)));
    for (final var scope : FaultKind.Scope.values()) {
      final long range = ranges.of(scope);
      final int[] ports = scope == FaultKind.Scope.RPC ? new int[]{httpPort} : wsPorts;
      final long step = Math.max(config.faultRate(), (range + MAX_PER_DOMAIN - 1) / MAX_PER_DOMAIN);
      for (final int port : ports) {
        for (long trigger = step; trigger <= range && accepted.size() < MAX_PLANNED; trigger += step) {
          final var kind = draw(selectable, scope, random, destructiveUsed[0] < destructiveBudget);
          if (kind == null) {
            continue;
          }
          if (accepted.putIfAbsent(key(scope.counter(), port, trigger),
              new Fault(0, kind, port, scope.counter(), trigger, params(kind, config, random))) == null
              && kind.destructive()) {
            ++destructiveUsed[0];
          }
        }
      }
    }

    // 3. storm windows: one per hour, SOAK_STORM_SECONDS long, at 1-in-STORM_INTERVAL on the two
    //    high-rate counters. Represented as extra dense ordinal ranges, not as a clock window, so a
    //    storm stays reproducible.
    //
    //    The storms draw against the same `destructiveBudget` as the fill and report back into the
    //    same counter. They used to draw with `allowDestructive` hardcoded true and no way to
    //    account for what they drew, which made SOAK_DESTRUCTIVE_PER_HOUR a claim about the fill
    //    alone: measured 2026-09-21, 1,402 of a plan's 1,410 destructive rows came from the storms
    //    at roughly 984 retiring faults per hour against a configured 30, and the density cost P7
    //    most of its evidence (30 of 38 recovery windows superseded before they could be judged).
    //    A storm is a dense window, not a licence to exceed the run's retirement budget.
    final int storm = config.stormSeconds();
    if (storm > 0) {
      for (int start = Math.max(0, 3600 - storm); start < config.durationSeconds(); start += 3600) {
        final int end = Math.min(config.durationSeconds(), start + storm);
        addStorm(accepted, selectable, random, config, FaultKind.Scope.NOTIFY, wsPorts,
            (long) config.wsNotifyRps() * start, (long) config.wsNotifyRps() * end, ranges.notifications(),
            destructiveUsed, destructiveBudget);
        addStorm(accepted, selectable, random, config, FaultKind.Scope.RPC, new int[]{httpPort},
            (long) config.httpRps() * start, (long) config.httpRps() * end, ranges.rpc(),
            destructiveUsed, destructiveBudget);
      }
      if (config.durationSeconds() < 3600) {
        final int start = Math.max(0, config.durationSeconds() - storm);
        addStorm(accepted, selectable, random, config, FaultKind.Scope.NOTIFY, wsPorts,
            (long) config.wsNotifyRps() * start, ranges.notifications(), ranges.notifications(),
            destructiveUsed, destructiveBudget);
        addStorm(accepted, selectable, random, config, FaultKind.Scope.RPC, new int[]{httpPort},
            (long) config.httpRps() * start, ranges.rpc(), ranges.rpc(),
            destructiveUsed, destructiveBudget);
      }
    }
  }

  /// The peer half of an `F*` fault control: a plan holding exactly one kind and nothing else.
  ///
  /// Those rows read one property against one fault, so the ordinary schedule's minimums, fill and
  /// storms are all skipped rather than merely outnumbered. A `TCP_RESET` landing inside F1's
  /// stalled exchange would retire the connection the stall is being measured on, and the row would
  /// then be scored against a run in which several different faults were live at once — which is
  /// exactly the confounding a control exists to remove.
  ///
  /// Density is stated in seconds of *expected traffic*, never wall-clock seconds: a placement every
  /// `onlyEverySeconds` is `range / duration × onlyEverySeconds` events of the kind's own counter,
  /// so a row keeps its several occurrences whatever rate it configures. The domain is widened to
  /// the placements the control itself asks for, because [#connRange] estimates an ordinary run's
  /// reachable ordinals from the run's *destructive budget*, and an `F*` row deliberately retires a
  /// connection every few seconds outside that budget; a plan that stopped at the estimate would
  /// stop injecting a fraction of the way in.
  private static void planRestricted(final Map<String, Fault> accepted,
                                     final Control control,
                                     final PeerMain.Config config,
                                     final SplittableRandom random,
                                     final int[] wsPorts,
                                     final int httpPort,
                                     final Ranges ranges) {
    final var kind = control.only();
    final var scope = kind.scope();
    final long duration = Math.max(1, config.durationSeconds());
    final long every = Math.max(1, control.onlyEverySeconds());
    final int burst = Math.max(1, control.onlyBurst());
    // The per-(counter, port) bound of DESIGN.md §6 holds here too: a control run is short, but
    // that bound is a promise the file format makes, not an accident of the profile that set it.
    final long placements = Math.min(Math.max(1, duration / every), Math.max(1, MAX_PER_DOMAIN / burst));
    final long range = Math.max(ranges.of(scope), placements * burst);
    // At least `burst`, so two bursts can never overlap into one long one.
    final long step = Math.max(burst, range / placements);
    final int[] ports;
    if (scope == FaultKind.Scope.RPC) {
      ports = new int[]{httpPort};
    } else if (control.onlyPortIndex() >= 0 && wsPorts.length > 0) {
      // Clamped rather than widened: a run configured with fewer ws ports than the control names
      // still gets a one-port plan, because "this kind, on one engine" is the control's claim and
      // spraying it over every port would quietly change what the row measures.
      ports = new int[]{wsPorts[Math.min(control.onlyPortIndex(), wsPorts.length - 1)]};
    } else {
      ports = wsPorts;
    }
    for (final int port : ports) {
      for (long placement = 0; placement < placements; ++placement) {
        for (int b = 0; b < burst; ++b) {
          final long trigger = 1 + (placement * step) + b;
          if (trigger > range || accepted.size() >= MAX_PLANNED) {
            break;
          }
          accepted.putIfAbsent(key(scope.counter(), port, trigger),
              new Fault(0, kind, port, scope.counter(), trigger, params(kind, config, random)));
        }
      }
    }
  }

  /// `destructiveUsed` is the run's destructive counter, shared with the weighted fill and advanced
  /// here too, so a storm draw is bounded by `destructiveBudget` like any other: the per-hour cap is
  /// a statement about the whole run's retirements, not about one of the two paths that schedule
  /// them.
  private static void addStorm(final Map<String, Fault> accepted,
                               final List<FaultKind> selectable,
                               final SplittableRandom random,
                               final PeerMain.Config config,
                               final FaultKind.Scope scope,
                               final int[] ports,
                               final long from,
                               final long to,
                               final long range,
                               final int[] destructiveUsed,
                               final long destructiveBudget) {
    final long hi = Math.min(to, range);
    for (final int port : ports) {
      for (long trigger = Math.max(1, from); trigger <= hi && accepted.size() < MAX_PLANNED; trigger += STORM_INTERVAL) {
        final var kind = draw(selectable, scope, random, destructiveUsed[0] < destructiveBudget);
        if (kind == null) {
          continue;
        }
        if (accepted.putIfAbsent(key(scope.counter(), port, trigger),
            new Fault(0, kind, port, scope.counter(), trigger, params(kind, config, random))) == null
            && kind.destructive()) {
          ++destructiveUsed[0];
        }
      }
    }
  }

  private static FaultKind draw(final List<FaultKind> selectable,
                                final FaultKind.Scope scope,
                                final SplittableRandom random,
                                final boolean allowDestructive) {
    int total = 0;
    for (final var kind : selectable) {
      if (kind.scope() == scope && (allowDestructive || !kind.destructive())) {
        total += kind.weight();
      }
    }
    if (total <= 0) {
      return null;
    }
    int pick = random.nextInt(total);
    for (final var kind : selectable) {
      if (kind.scope() == scope && (allowDestructive || !kind.destructive())) {
        pick -= kind.weight();
        if (pick < 0) {
          return kind;
        }
      }
    }
    return null;
  }

  private static String params(final FaultKind kind, final PeerMain.Config config, final SplittableRandom random) {
    return switch (kind) {
      // The delay is drawn once, here, so `fault-schedule.tsv` states it before the run and
      // `peer-http.tsv`'s delayMillis can be checked against the plan rather than trusted.
      case DELAY_RESPONSE -> "delayMillis=" + (50 + random.nextInt(5951));
      case DELAY_CONFIRM -> "delayMillis=" + config.delayConfirmMillis();
      case CONNECT_REFUSE -> "refuseSeconds=" + config.refuseSeconds();
      case MESSAGE_OVERFLOW -> "chars=" + Payloads.OVERFLOW_CHARS;
      case STALL_BODY -> "holdSeconds=" + WsPeer.STALL_HOLD_SECONDS;
      case HANDSHAKE_STALL -> "holdSeconds=" + WsPeer.HANDSHAKE_STALL_SECONDS;
      default -> "-";
    };
  }

  /// The peer half of a `SOAK_CONTROL` id (`controls.tsv`). Four shapes:
  ///
  /// - **D1–D7, D12, crosstalk, gzip-wrong-twin** keep the ordinary schedule and add one
  ///   control-only kind applied to *every* eligible event — or, for D12, suppress one kind
  ///   everywhere. A negative control that fired at a seeded ordinal would prove only that the
  ///   detector can see one instance.
  /// - **F1–F8** replace the plan with one kind at a stated density and nothing else, so the
  ///   property each row names is read against that fault alone (see [#planRestricted]).
  /// - **I1-sync, I1-forced, I1-natural, I1-jdk25, I2** plan no peer fault at all. Their injection
  ///   is entirely client side — the harness's own `WebSocket.Builder`, `Backoff` and JUL seams
  ///   (`DESIGN.md` §10) — and a peer fault would retire connections the #52 episode is trying to
  ///   attribute, which is the one thing those rows measure.
  /// - **I3** and every remaining harness-side or runner-side row (D8–D11, D13, D14, filter-typo,
  ///   stall-callback) run the ordinary schedule: I3 *is* the measurement under the ordinary fault
  ///   rate, and the others break the client or the verdict machinery, not the peer.
  ///
  /// `no-faults` needs no case here: it sets `SOAK_FAULT_RATE=0`, which empties the plan in [#of].
  private static Control controlFor(final String id, final PeerMain.Config config) {
    if (id == null || id.isBlank()) {
      return Control.NONE;
    }
    // STALL_BODY holds a peer thread for the whole hold, and the peer parks at most a quarter of
    // its pool ([RpcPeer#STALL_HOLD_SHARE]); a stall past that budget is served normally and
    // counted against fidelity. The row's density therefore follows the budget: one stall per
    // hold-length divided by the holds the peer can park, so every planned stall is one it can
    // stage (measured 2026-09-22: every 10 s planned four times what the peer could hold).
    final int stallEvery = Math.max(10,
        WsPeer.STALL_HOLD_SECONDS / Math.max(1, config.peerHttpThreads() / RpcPeer.STALL_HOLD_SHARE));
    return switch (id) {
      case "D1" -> Control.always(id, FaultKind.DROP_REPLAYED_SUBSCRIBES, 2);
      case "D2" -> Control.always(id, FaultKind.DUPLICATE_NOTIFY, 1);
      case "D3" -> Control.always(id, FaultKind.SWAP_ADJACENT_NOTIFY, 1);
      case "D4" -> Control.always(id, FaultKind.WRONG_KEY_ECHO, 1);
      case "D5" -> Control.always(id, FaultKind.FLIP_CONTINUATION_BYTE, 1);
      case "D6" -> Control.always(id, FaultKind.DEDUPE_BREAK, 1);
      case "D7" -> Control.always(id, FaultKind.ZOMBIE_NOTIFICATION, 1);
      case "D12" -> Control.never(id, FaultKind.SWALLOW_PING);
      // A diverted notification needs somewhere to divert to, so the second connection onward.
      case "crosstalk" -> Control.always(id, FaultKind.CROSSTALK, 2);
      case "gzip-wrong-twin" -> Control.always(id, FaultKind.GZIP_WRONG_TWIN, 1);
      // One stall per 10 s of request traffic: each one occupies a worker until the client's own
      // exchange deadline (2 x requestTimeout) ends it, and F1 asserts that deadline, so they must
      // not overlap so densely that the pool has nothing left to make progress with.
      case "F1" -> Control.only(id, FaultKind.STALL_BODY, stallEvery, 1, -1);
      // A swallowed ping is noticed only by the two-phase probe (2 x pingDelay + check delay, about
      // 9 s at the row's own overrides), so one per connection is the density: the next connection
      // is the next occurrence.
      case "F2" -> Control.only(id, FaultKind.SWALLOW_PING, 10, 1, -1);
      // Escalation is four resend delays away, so one swallowed subscribe per 10 s of subscribe
      // traffic leaves each escalation its own window.
      case "F3" -> Control.only(id, FaultKind.SWALLOW_SUBSCRIBE, 10, 1, -1);
      // The storm: one reset per 5 s of expected traffic, which on the conn counter is every
      // connection, so the row measures retire-reconnect-replay over and over.
      case "F4" -> Control.only(id, FaultKind.TCP_RESET, 5, 1, -1);
      case "F5" -> Control.only(id, FaultKind.MESSAGE_OVERFLOW, 10, 1, OVERFLOW_PORT_INDEX);
      // Every other connection, so the run alternates refused and accepted: refusing them all would
      // leave the ws properties UNEXERCISED rather than testing reconnect pacing against them.
      case "F6" -> Control.only(id, FaultKind.CONNECT_REFUSE, 20, 1, -1);
      case "F7" -> Control.only(id, FaultKind.ZOMBIE_NOTIFICATION, 10, 1, -1);
      // A burst, not a drizzle: eight consecutive 503s every 20 s of request traffic, so the row's
      // "the calls after the burst succeed" has a burst and an after.
      case "F8" -> Control.only(id, FaultKind.HTTP_503, 20, 8, -1);
      case "I1-sync", "I1-forced", "I1-natural", "I1-jdk25", "I2" -> Control.clientSide(id);
      default -> Control.ordinary(id);
    };
  }

  /// The port's position in `SOAK_WS_PORTS` order, or -1 for the HTTP port. Both the row order
  /// (hence every ordinal) and the canonical digest are taken over this rather than over the
  /// ephemeral port number, which the kernel picks afresh each run.
  private static int portIndex(final int[] wsPorts, final int port) {
    for (int i = 0; i < wsPorts.length; ++i) {
      if (wsPorts[i] == port) {
        return i;
      }
    }
    return -1;
  }

  private static String canonicalDigest(final List<Fault> faults, final int[] wsPorts) {
    final var sb = new StringBuilder(faults.size() * 48);
    for (final var fault : faults) {
      sb.append(fault.ordinal()).append('\t').append(fault.kind()).append('\t')
          .append(fault.kind().scope()).append('\t').append(portIndex(wsPorts, fault.port())).append('\t')
          .append(fault.triggerText()).append('\t').append(fault.params()).append('\n');
    }
    return Stamp.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  // --- use --------------------------------------------------------------------------------------

  List<Fault> planned() {
    return planned;
  }

  Control control() {
    return control;
  }

  /// The kinds that were wanted in the plan and could not be placed: their counter domain was
  /// smaller than `SOAK_FAULT_MIN_PER_KIND`, or it was already fully taken by other kinds'
  /// minimums. Either way the run must announce it (`SCHEDULE_OMITTED`, `omittedKinds`), because a
  /// kind that never reaches the plan is invisible to the report's unapplied-kind gate and its
  /// absence would read as a clean run rather than as missing evidence.
  ///
  /// Empty when the run asked for no minimums: a plan emptied by `SOAK_FAULT_RATE=0`, restricted to
  /// one kind by an `F*` control, or skipped by a client-side control omits nothing — the other
  /// kinds were not wanted, and listing all of them would drown the signal this set exists to give.
  EnumSet<FaultKind> omitted() {
    return omitted;
  }

  /// SHA-256 of `fault-schedule.tsv` exactly as written; `run.json` records it.
  String fileSha256() {
    return fileSha256;
  }

  /// SHA-256 over the same rows with the port *index* in place of the ephemeral port number, so it
  /// is comparable across runs at one seed. The file digest is not, because the ports move.
  String canonicalSha256() {
    return canonicalSha256;
  }

  void write(final Path path) throws IOException {
    final var sb = new StringBuilder(planned.size() * 48 + 64);
    sb.append(Fault.HEADER).append('\n');
    for (final var fault : planned) {
      sb.append(fault.row()).append('\n');
    }
    final byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
    Files.write(path, bytes);
    this.fileSha256 = Stamp.sha256Hex(bytes);
  }

  /// One-shot lookup by the peer's own counter. Removing the entry is what makes a scheduled fault
  /// fire exactly once even when two connections race the same counter value.
  Fault take(final String counter, final int port, final long value) {
    final var fault = byTrigger.remove(key(counter, port, value));
    return fault == null || suppressed(fault.kind()) ? null : fault;
  }

  /// `rpcMethod:<m>` wins over `rpc` when both name the same request, so a schedule that deliberately
  /// targets one method is not pre-empted by the generic counter.
  Fault takeRpc(final int port, final String method, final long rpcSeq, final long methodSeq) {
    final var byMethod = take("rpcMethod:" + method, port, methodSeq);
    return byMethod != null ? byMethod : take("rpc", port, rpcSeq);
  }

  /// The control-only kind applied on every event of `scope`, or null.
  ///
  /// How each scope's caller consults it:
  /// - **CONN**: `WsPeer`'s accept loop, with the ordinal it is about to assign
  ///   (`connCounter + 1`), beside its `take("conn", …)` lookup; a kind that acts on an established
  ///   connection rather than on the accept itself (`CROSSTALK`) is re-consulted by `WsConnection`
  ///   with its own `connId`.
  /// - **SUB** / **NOTIFY**: `WsConnection`, with its own `connId`.
  /// - **RPC**: `RpcPeer` has no websocket connection, so it calls [#always(FaultKind.Scope)] — the
  ///   same lookup with the `minConnOrdinal` gate left out rather than faked with a number. That
  ///   gate exists for the controls that need a *reconnect* (a replayed subscribe, a sibling
  ///   connection to divert to), and an HTTP request has no generation to wait for.
  ///
  /// The kind is returned on **every** call, not once: a differential or crosstalk oracle handed a
  /// single instance per run would prove only that it can fire once.
  FaultKind always(final FaultKind.Scope scope, final long connOrdinal) {
    return connOrdinal >= control.minConnOrdinal() ? always(scope) : null;
  }

  /// The connection-independent half of [#always(FaultKind.Scope,long)], for RPC-scope callers.
  FaultKind always(final FaultKind.Scope scope) {
    final var always = control.always();
    return always != null && always.scope() == scope ? always : null;
  }

  /// How many scheduled faults were never reached. The report's fidelity ratio needs this as a
  /// denominator check, and it must not be computed by draining the map.
  int remaining() {
    return byTrigger.size();
  }

  boolean suppressed(final FaultKind kind) {
    return control.never() == kind;
  }
}
