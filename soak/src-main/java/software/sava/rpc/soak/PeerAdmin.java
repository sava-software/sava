package software.sava.rpc.soak;

import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/// The harness's client for the peer JVM's `/__soak/*` admin routes.
///
/// The peer is a separate process on purpose — retention evidence is process-wide, and a peer
/// sharing the client's heap would put its own allocations in the measurement. That makes these
/// five routes the only way the client can ask the peer what it saw, and `logDropped` in
/// particular is verdict-relevant: a peer that dropped log rows has invalidated the oracle's
/// denominators, and the run must report `INVALID` rather than a pass computed from a truncated
/// record.
///
/// In live mode there is no peer, so [#none()] returns an instance whose calls are no-ops and
/// whose stats read `unavailable` — never a fabricated zero.
public final class PeerAdmin {

  public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /// The peer's own totals. `-1` means "not reported", which the report prints as unavailable.
  public record Stats(long logDropped, long liveSubscriptions, long openConnections, String raw) {

    public static final Stats UNAVAILABLE = new Stats(-1L, -1L, -1L, "");

    public boolean available() {
      return !raw.isEmpty();
    }
  }

  private final HttpClient httpClient;
  private final URI base;

  private PeerAdmin(final HttpClient httpClient, final URI base) {
    this.httpClient = httpClient;
    this.base = base;
  }

  public static PeerAdmin of(final HttpClient httpClient, final String host, final int httpPort) {
    return new PeerAdmin(httpClient, URI.create("http://" + host + ':' + httpPort + "/__soak/"));
  }

  /// A live run's peer-less stand-in.
  public static PeerAdmin none() {
    return new PeerAdmin(null, null);
  }

  public boolean available() {
    return httpClient != null;
  }

  public URI base() {
    return base;
  }

  /// The raw `/__soak/stats` body, or an empty string when the peer is absent or unreachable.
  /// Unreachable is reported by the caller, never retried here: the runner's liveness cross-watch
  /// already owns "is the peer alive".
  public String statsJson() {
    return get("stats");
  }

  public Stats stats() {
    final var json = statsJson();
    if (json.isEmpty()) {
      return Stats.UNAVAILABLE;
    }
    final var parsed = JsonIterator.parse(json).testObject(new long[]{-1L, -1L, -1L},
        (totals, buffer, offset, length, iterator) -> {
          if (JsonIterator.fieldEquals("logDropped", buffer, offset, length)) {
            totals[0] = iterator.readLong();
          } else if (JsonIterator.fieldEquals("liveSubscriptions", buffer, offset, length)) {
            totals[1] = readLongOrSkip(iterator);
          } else if (JsonIterator.fieldEquals("openConnections", buffer, offset, length)) {
            totals[2] = readLongOrSkip(iterator);
          } else {
            iterator.skip();
          }
          return true;
        });
    return new Stats(parsed[0], parsed[1], parsed[2], json);
  }

  /// `liveSubscriptions` and `openConnections` may be a per-port object rather than a total; in
  /// that case the field is skipped and reported unavailable rather than guessed at.
  private static long readLongOrSkip(final JsonIterator iterator) {
    if (iterator.whatIsNext() == systems.comodal.jsoniter.ValueType.NUMBER) {
      return iterator.readLong();
    }
    iterator.skip();
    return -1L;
  }

  /// Stop emitting notifications, keep answering requests. Used at the QUIESCE boundary so the
  /// residual assertions measure the client's own drain rather than a moving target.
  public boolean quiesce() {
    return post("quiesce");
  }

  /// Flush the peer's TSV logs, so the client can read a complete record at a phase boundary.
  public boolean flush() {
    return post("flush");
  }

  /// The last 64 frames in and out of one connection, hex-encoded: the attachment for a W2-A or
  /// W1-E finding.
  public String capture(final int port, final long connectionId) {
    return get("capture?port=" + port + "&conn=" + connectionId);
  }

  public boolean shutdown() {
    return post("shutdown");
  }

  private String get(final String path) {
    final var body = send(HttpRequest.newBuilder().GET(), path);
    return body == null ? "" : body;
  }

  /// True when the peer answered 2xx. An empty 200 body is still a success, which is why the
  /// distinction between "no body" and "no answer" is a null rather than an empty string.
  private boolean post(final String path) {
    return send(HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.noBody()), path) != null;
  }

  private String send(final HttpRequest.Builder builder, final String path) {
    if (httpClient == null) {
      return null;
    }
    final var request = builder.uri(base.resolve(path)).timeout(REQUEST_TIMEOUT).build();
    try {
      final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        System.err.println("soak: peer admin " + path + " answered " + response.statusCode());
        return null;
      }
      final var body = response.body();
      return body == null ? "" : body;
    } catch (final IOException e) {
      System.err.println("soak: peer admin " + path + " failed: " + Redaction.text(e.toString()));
      return null;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }
}
