package software.sava.rpc.soak;

import software.sava.rpc.soak.churn.ChurnWorkload;
import software.sava.rpc.soak.control.HarnessControls;
import software.sava.rpc.soak.http.HttpWorkload;
import software.sava.rpc.soak.ws.WsWorkload;

import java.util.ArrayList;
import java.util.List;

/// The one place [Main] learns which drivers a run has.
///
/// The core owns the lifecycle, the counters, the oracle and the recording; the drivers own what
/// they do to the subject. Keeping the wiring behind a single lookup means the core compiles and
/// runs with no driver at all — which is exactly what a bring-up or a recording-only rehearsal
/// wants — and a driver's absence is visible here rather than as a silently empty run.
public final class Workloads {

  private Workloads() {
  }

  /// The drivers for this run, in start order, which is also the order [Phases] quiesces and
  /// drains them.
  ///
  /// The control workload goes first when `SOAK_CONTROL` names a harness-side injection
  /// (D8–D11, D13, D14): its threads, sockets and retained allocation must exist before the
  /// thread, descriptor and heap baselines are read, or the gate it exists to trip would be
  /// measuring a baseline that already included the leak. The websocket driver is suppressed
  /// under D11 so its properties read `UNEXERCISED`, which is the control's point. The HTTP
  /// driver drains after the websocket one, because W5-B1 (the shared `HttpClient` survives)
  /// is proved in its `drain` and has to run after every other driver has released what it
  /// held. Each driver registers its own gauge source inside `start`.
  public static List<Workload> create(final SoakContext ctx) {
    final var workloads = new ArrayList<Workload>(4);
    final var controls = HarnessControls.of(ctx);
    if (controls != null) {
      workloads.add(controls);
    }
    if (HarnessControls.webSocketDriverEnabled(ctx.config())) {
      workloads.add(new WsWorkload());
    }
    workloads.add(HttpWorkload.create(ctx));
    workloads.add(new ChurnWorkload());
    return List.copyOf(workloads);
  }
}
