package software.sava.rpc.soak.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Reads the harness's tabular artifacts — the tab-separated ledgers of `DESIGN.md` §6/§8/§10
/// and the comma-separated gauge series of §9 — by column NAME rather than by position, so a
/// writer that appends a column does not silently shift every reader.
///
/// Two tolerances matter for a soak: a run can be killed at any instant, so the last line of an
/// append-only file is routinely half-written, and two of the files (`rss.csv`, `phases.tsv`)
/// carry no header at all. A short line is therefore dropped and counted rather than thrown on,
/// and a caller may supply the column names to assume when the first line is already data.
final class TsvReader {

  /// One parsed file. [#partialLines] counts the lines dropped as incomplete — too few cells for
  /// the header, or the final fragment of a file that was still being appended to. Always
  /// reported: "the report read 4 of 5 rows" is a fact its reader needs, and a silent truncation
  /// is not.
  record Table(Path path, List<String> columns, List<Row> rows, int partialLines) {

    boolean has(final String column) {
      return columns.contains(column);
    }

    Row last() {
      return rows.isEmpty() ? null : rows.getLast();
    }

    /// Every value of one column, in file order, skipping rows where it is blank.
    List<String> column(final String name) {
      final var values = new ArrayList<String>(rows.size());
      for (final var row : rows) {
        final var value = row.get(name);
        if (!value.isBlank()) {
          values.add(value);
        }
      }
      return values;
    }
  }

  /// One row. Absent or blank cells read as `""` / the caller's `absent` value: every caller here
  /// is a report line, and a missing cell is a labelled gap, never an exception.
  record Row(Map<String, Integer> index, String[] cells) {

    String get(final String column) {
      final var at = index.get(column);
      return at == null || at >= cells.length ? "" : cells[at];
    }

    String get(final String column, final String absent) {
      final var value = get(column);
      return value.isBlank() ? absent : value;
    }

    long getLong(final String column, final long absent) {
      return parseLong(get(column), absent);
    }

    double getDouble(final String column, final double absent) {
      return parseDouble(get(column), absent);
    }

    boolean getBoolean(final String column) {
      final var value = get(column).trim();
      return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }
  }

  static final Table EMPTY = new Table(null, List.of(), List.of(), 0);

  static boolean exists(final Path path) {
    return path != null && Files.isRegularFile(path);
  }

  static Table readTsv(final Path path) throws IOException {
    return read(path, '\t', null);
  }

  static Table readCsv(final Path path) throws IOException {
    return read(path, ',', null);
  }

  /// Reads `path`, treating the first line as the header unless it is already data and
  /// `assumedColumns` names what that data is (`rss.csv` has no header; `phases.tsv` may not).
  /// A file that does not exist reads as [#EMPTY] with a null path — the caller renders the
  /// `(missing)` line. A file that exists but cannot be read is an `IOException`: an artifact
  /// present and unreadable is a broken run, not an absent measurement.
  static Table read(final Path path, final char delimiter, final List<String> assumedColumns) throws IOException {
    if (!exists(path)) {
      return EMPTY;
    }
    final var text = Files.readString(path, StandardCharsets.UTF_8);
    final var lines = new ArrayList<>(List.of(text.isEmpty() ? new String[0] : text.split("\n", -1)));
    // A file whose last byte is not a newline was still being written when the run was killed, and
    // its final line is a fragment. A fragment can LOOK complete — a csv row truncated after its
    // last comma parses as a short value and silently skews a trend fit — so the rule is the file
    // ending, not the shape of the line.
    int truncated = 0;
    if (!lines.isEmpty() && lines.getLast().isEmpty()) {
      lines.removeLast();
    } else if (!lines.isEmpty()) {
      lines.removeLast();
      truncated = 1;
    }
    int at = 0;
    while (at < lines.size() && lines.get(at).isBlank()) {
      ++at;
    }
    if (at == lines.size()) {
      return new Table(path, assumedColumns == null ? List.of() : assumedColumns, List.of(), truncated);
    }
    final var first = split(lines.get(at), delimiter);
    final List<String> columns;
    if (assumedColumns != null && looksLikeData(first)) {
      columns = assumedColumns;
    } else {
      columns = List.of(trimAll(first));
      ++at;
    }
    final var index = new HashMap<String, Integer>(columns.size() * 2);
    for (int i = 0; i < columns.size(); ++i) {
      index.putIfAbsent(columns.get(i), i);
    }
    final var rows = new ArrayList<Row>(Math.max(1, lines.size() - at));
    int partial = truncated;
    for (; at < lines.size(); ++at) {
      final var line = lines.get(at);
      if (line.isBlank() || line.startsWith("#")) {
        // a '#' line is the comment run.env opens with, not a row
        continue;
      }
      final var cells = split(line, delimiter);
      if (cells.length < columns.size()) {
        ++partial;
        continue;
      }
      rows.add(new Row(index, cells));
    }
    return new Table(path, columns, rows, partial);
  }

  /// `KEY=VALUE` files — `counters.properties`, `run.env`. Read by hand rather than through
  /// `java.util.Properties` so the file's own order survives into the report and a `#` comment
  /// line (`run.env`'s first line is the command line) is kept out of the values.
  static Map<String, String> readKeyValues(final Path path) throws IOException {
    final var values = new java.util.LinkedHashMap<String, String>();
    if (!exists(path)) {
      return values;
    }
    for (final var line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      final var trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.charAt(0) == '!') {
        continue;
      }
      final int equals = trimmed.indexOf('=');
      if (equals > 0) {
        values.put(trimmed.substring(0, equals).strip(), trimmed.substring(equals + 1).strip());
      }
    }
    return values;
  }

  static long longValue(final Map<String, String> values, final String key, final long absent) {
    return parseLong(values.get(key), absent);
  }

  static double doubleValue(final Map<String, String> values, final String key, final double absent) {
    return parseDouble(values.get(key), absent);
  }

  static long parseLong(final String value, final long absent) {
    if (value == null) {
      return absent;
    }
    final var trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return absent;
    }
    try {
      return Long.parseLong(trimmed);
    } catch (final NumberFormatException ignored) {
      try {
        return (long) Double.parseDouble(trimmed);
      } catch (final NumberFormatException alsoIgnored) {
        return absent;
      }
    }
  }

  static double parseDouble(final String value, final double absent) {
    if (value == null) {
      return absent;
    }
    final var trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return absent;
    }
    try {
      return Double.parseDouble(trimmed);
    } catch (final NumberFormatException ignored) {
      return absent;
    }
  }

  /// A first line whose first cell is a number is data, not a header: `rss.csv` starts with an
  /// epoch second and `phases.tsv` with epoch millis.
  private static boolean looksLikeData(final String[] first) {
    if (first.length == 0) {
      return false;
    }
    final var cell = first[0].strip();
    if (cell.isEmpty()) {
      return false;
    }
    for (int i = 0; i < cell.length(); ++i) {
      final char c = cell.charAt(i);
      if ((c < '0' || c > '9') && c != '-' && c != '.' && c != '+') {
        return false;
      }
    }
    return true;
  }

  private static String[] split(final String line, final char delimiter) {
    // -1 keeps trailing empty cells, so a row ending in blank columns still matches the header;
    // the delimiter is quoted because split() takes a regex and ',' is not the only caller
    return line.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)), -1);
  }

  private static String[] trimAll(final String[] cells) {
    final var trimmed = new String[cells.length];
    for (int i = 0; i < cells.length; ++i) {
      trimmed[i] = cells[i].strip();
    }
    return trimmed;
  }

  /// `percentile(values, 0.99)` over an unsorted list; the list is copied, never sorted in place,
  /// because callers keep their own order for the tail tables.
  static long percentile(final List<Long> values, final double p) {
    if (values == null || values.isEmpty()) {
      return 0L;
    }
    final var sorted = new ArrayList<>(values);
    java.util.Collections.sort(sorted);
    final int at = Math.clamp((int) Math.ceil(p * sorted.size()) - 1, 0, sorted.size() - 1);
    return sorted.get(at);
  }

  static String pct(final double numerator, final double denominator) {
    return denominator <= 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", numerator * 100d / denominator);
  }

  private TsvReader() {
  }
}
