package software.sava.rpc.soak.ws;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;

import java.lang.reflect.Method;

/// Reflective access to `SolanaJsonRpcWebsocket`'s package-private test seams.
///
/// These are the only grade-D numbers in the websocket half: they say how much the engine is still
/// holding after a registration is cancelled or a connection is retired. They are not a contract —
/// the methods are package-private and exist for sava's own tests — so every one of them reads -1
/// when the package is not opened, and -1 travels to the report as "unavailable". Returning 0
/// instead would be the worst of both worlds: it would pass the retention margin while proving
/// nothing.
///
/// Reachable only with `--add-opens software.sava.rpc/software.sava.rpc.json.http.ws=software.sava.rpc.soak`
/// on the soak JVM's launch line. The lookup happens once, against the implementation class of the
/// first websocket handed in, because `trySetAccessible` on a failing member is cheap only the
/// first time.
final class InternalProbes {

  /// The sentinel every accessor returns when the seam is not reachable.
  static final long UNAVAILABLE = -1L;

  private static volatile boolean resolved;
  private static volatile boolean available;
  private static volatile Method retainedRegistrations;
  private static volatile Method retainedCancellationTombstones;
  private static volatile Method retainedOrdinalEntries;
  private static volatile Method retainedExceptionSubscribers;
  private static volatile Method executorServiceShutdown;

  private InternalProbes() {
  }

  /// Resolves the seams against `websocket`'s own class, once per JVM. Safe to call from several
  /// threads: the worst a race costs is a second lookup.
  static void detect(final SolanaRpcWebsocket websocket) {
    if (resolved || websocket == null) {
      return;
    }
    synchronized (InternalProbes.class) {
      if (resolved) {
        return;
      }
      final var type = websocket.getClass();
      retainedRegistrations = probe(type, "retainedRegistrations");
      retainedCancellationTombstones = probe(type, "retainedCancellationTombstones");
      retainedOrdinalEntries = probe(type, "retainedOrdinalEntries");
      retainedExceptionSubscribers = probe(type, "retainedExceptionSubscribers");
      executorServiceShutdown = probe(type, "executorServiceShutdown");
      available = retainedRegistrations != null;
      resolved = true;
    }
  }

  /// True once [#detect(SolanaRpcWebsocket)] has found the seams. A driver reads this to decide
  /// between evaluating a probe-backed property and recording a stated skip for it — an unstated
  /// skip reads as UNEXERCISED, which is a FAIL.
  static boolean available() {
    return available;
  }

  static long retainedRegistrations(final SolanaRpcWebsocket websocket) {
    return invoke(retainedRegistrations, websocket);
  }

  static long retainedCancellationTombstones(final SolanaRpcWebsocket websocket) {
    return invoke(retainedCancellationTombstones, websocket);
  }

  static long retainedOrdinalEntries(final SolanaRpcWebsocket websocket) {
    return invoke(retainedOrdinalEntries, websocket);
  }

  static long retainedExceptionSubscribers(final SolanaRpcWebsocket websocket) {
    return invoke(retainedExceptionSubscribers, websocket);
  }

  /// 1 when the engine's own check-loop executor is shut down, 0 when it is not, -1 when the seam
  /// is unreachable. A `boolean` would have no room for the third answer, which is the one that
  /// decides whether W5-A is evaluated or skipped with a reason.
  static long executorServiceShutdown(final SolanaRpcWebsocket websocket) {
    final var method = executorServiceShutdown;
    if (method == null || websocket == null) {
      return UNAVAILABLE;
    }
    try {
      return ((Boolean) method.invoke(websocket)) ? 1L : 0L;
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return UNAVAILABLE;
    }
  }

  private static Method probe(final Class<?> type, final String name) {
    try {
      final var method = type.getDeclaredMethod(name);
      return method.trySetAccessible() ? method : null;
    } catch (final NoSuchMethodException | RuntimeException e) {
      return null;
    }
  }

  private static long invoke(final Method method, final SolanaRpcWebsocket websocket) {
    if (method == null || websocket == null) {
      return UNAVAILABLE;
    }
    try {
      return ((Number) method.invoke(websocket)).longValue();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return UNAVAILABLE;
    }
  }
}
