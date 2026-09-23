package software.sava.rpc.soak;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/// Everything the harness writes to an artifact, a log line or a JFR event passes through here
/// first.
///
/// A soak against a live provider carries that provider's credential in the URL it was given —
/// in the userinfo, in a path segment, or in an `api-key` query parameter — and the run
/// directory is meant to be attachable to an issue. So an endpoint is recorded as
/// `scheme://host[:port]` and nothing else, and a header or message that names a credential has
/// its value replaced. [#selfTest()] proves it on a synthetic URL before any real endpoint is
/// touched; a failure is `INVALID`, not a warning, because the alternative is discovering the
/// leak in an attached artifact.
public final class Redaction {

  public static final String REDACTED = "<redacted>";

  /// The header names whose values never appear anywhere. Matched case-insensitively.
  private static final Set<String> SECRET_HEADERS = Set.of(
      "authorization", "proxy-authorization", "x-api-key", "api-key", "apikey", "x-auth-token", "cookie");

  private static final Pattern URL = Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s\"'<>,]+");

  /// The value runs to the end of the line, or to the next `;` or `,`. Consuming the rest of the
  /// line is deliberate: `Authorization: Bearer <key>` has a space inside the value, and a
  /// pattern that stopped at the first one would leave the key behind while looking redacted.
  private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
      "(?i)\\b(authorization|proxy-authorization|x-api-key|api-key|apikey|x-auth-token|cookie|token|secret|password)"
          + "\\s*[:=]\\s*(?:\"[^\"\\n]*\"|'[^'\\n]*'|[^\\n;,]*)");

  private Redaction() {
  }

  /// `scheme://host[:port]`. Userinfo, path, query and fragment are dropped rather than masked:
  /// a masked path still tells a reader how many segments the key had.
  public static String endpoint(final URI uri) {
    if (uri == null) {
      return "";
    }
    final var scheme = uri.getScheme();
    var host = uri.getHost();
    if (host == null) {
      final var authority = uri.getAuthority();
      if (authority == null) {
        return scheme == null ? REDACTED : scheme + "://" + REDACTED;
      }
      final int at = authority.lastIndexOf('@');
      host = at < 0 ? authority : authority.substring(at + 1);
      final int colon = host.lastIndexOf(':');
      if (colon > 0) {
        host = host.substring(0, colon);
      }
    }
    final int port = uri.getPort();
    return (scheme == null ? "" : scheme + "://") + host + (port < 0 ? "" : ":" + port);
  }

  public static String endpoint(final String uri) {
    if (uri == null || uri.isBlank()) {
      return "";
    }
    try {
      return endpoint(URI.create(uri.trim()));
    } catch (final IllegalArgumentException e) {
      return REDACTED;
    }
  }

  /// The value to record for one header.
  public static String header(final String name, final String value) {
    return name != null && SECRET_HEADERS.contains(name.toLowerCase(Locale.ROOT)) ? REDACTED : value;
  }

  public static boolean secretHeader(final String name) {
    return name != null && SECRET_HEADERS.contains(name.toLowerCase(Locale.ROOT));
  }

  /// Redacts free text: every URL collapses to its endpoint and every `name: value` pair whose
  /// name reads like a credential loses its value. Used for exception messages and for the
  /// captured manager log lines, both of which can quote a request.
  public static String text(final String message) {
    if (message == null || message.isEmpty()) {
      return message;
    }
    final var withoutUrls = URL.matcher(message).replaceAll(match -> {
      final var replacement = endpoint(match.group());
      return java.util.regex.Matcher.quoteReplacement(replacement);
    });
    return SECRET_ASSIGNMENT.matcher(withoutUrls).replaceAll(match ->
        java.util.regex.Matcher.quoteReplacement(match.group(1) + ": " + REDACTED));
  }

  /// Proves the three redaction paths on a synthetic URL whose secret is `fake-key-123` and
  /// whose userinfo password is `fake-secret`. Returns false — and says which case failed on
  /// `System.err` — rather than throwing, so the caller decides the exit code.
  public static boolean selfTest() {
    final var secret = "fake-key-123";
    final var password = "fake-secret";
    final var url = "https://soakuser:" + password + "@rpc.example.invalid:8899/v1/" + secret
        + "?api-key=" + secret;
    boolean ok = true;
    final var redactedEndpoint = endpoint(url);
    if (!"https://rpc.example.invalid:8899".equals(redactedEndpoint)) {
      System.err.println("REDACTION: endpoint(url) was '" + redactedEndpoint + '\'');
      ok = false;
    }
    ok &= absent("endpoint", redactedEndpoint, secret, password);

    final var wsEndpoint = endpoint("wss://ws.example.invalid/stream/" + secret + "?token=" + secret);
    ok &= absent("ws endpoint path/query", wsEndpoint, secret, null);
    if (!wsEndpoint.startsWith("wss://")) {
      System.err.println("REDACTION: ws endpoint lost its scheme: '" + wsEndpoint + '\'');
      ok = false;
    }

    final var headerValue = header("Authorization", "Bearer " + secret);
    ok &= absent("Authorization header", headerValue, secret, null);
    final var apiKeyHeader = header("x-api-key", secret);
    ok &= absent("x-api-key header", apiKeyHeader, secret, null);
    if (!"application/json".equals(header("Content-Type", "application/json"))) {
      System.err.println("REDACTION: a non-secret header was altered");
      ok = false;
    }

    final var message = "connect to " + url + " failed; x-api-key: " + secret
        + " Authorization=Bearer " + secret;
    ok &= absent("message", text(message), secret, password);
    return ok;
  }

  private static boolean absent(final String what, final String redacted, final String secret,
                                final String password) {
    boolean ok = true;
    if (redacted != null && redacted.contains(secret)) {
      System.err.println("REDACTION: " + what + " still carries the key: '" + redacted + '\'');
      ok = false;
    }
    if (password != null && redacted != null && redacted.contains(password)) {
      System.err.println("REDACTION: " + what + " still carries the password: '" + redacted + '\'');
      ok = false;
    }
    return ok;
  }
}
