package software.sava.vanity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.core.accounts.vanity.Subsequence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// The CLI's configuration surface. Every knob a user can set arrives as a system
/// property and is parsed here, so a wrong default or a silently swallowed value
/// changes what gets generated and where it is written.
final class EntrypointTests {

  private static final String MODULE = "test.sava.vanity";

  private final List<String> setProperties = new ArrayList<>();

  private void property(final String name, final String value) {
    final var key = MODULE + '.' + name;
    setProperties.add(key);
    System.setProperty(key, value);
  }

  /// System properties are process wide, so nothing may leak between tests.
  @AfterEach
  void clearProperties() {
    setProperties.forEach(System::clearProperty);
    setProperties.clear();
  }

  @Test
  void intPropFallsBackWhenUnsetOrBlank() {
    assertEquals(7, Entrypoint.intProp(MODULE, "absent", 7));
    property("blank", "");
    assertEquals(7, Entrypoint.intProp(MODULE, "blank", 7));
    property("whitespace", "   ");
    assertEquals(7, Entrypoint.intProp(MODULE, "whitespace", 7));
  }

  @Test
  void intPropParsesSetValues() {
    property("threads", "12");
    assertEquals(12, Entrypoint.intProp(MODULE, "threads", 1));
    property("negative", "-3");
    assertEquals(-3, Entrypoint.intProp(MODULE, "negative", 1));
    property("zero", "0");
    assertEquals(0, Entrypoint.intProp(MODULE, "zero", 1));
  }

  /// A malformed number is a startup failure rather than a silent fallback — the
  /// user asked for a specific value and did not get it.
  @Test
  void intPropRejectsGarbage() {
    property("garbage", "twelve");
    assertThrows(NumberFormatException.class, () -> Entrypoint.intProp(MODULE, "garbage", 1));
    property("overflow", "99999999999999");
    assertThrows(NumberFormatException.class, () -> Entrypoint.intProp(MODULE, "overflow", 1));
  }

  @Test
  void boolPropFallsBackWhenUnsetOrBlank() {
    assertTrue(Entrypoint.boolProp(MODULE, "absent", true));
    assertFalse(Entrypoint.boolProp(MODULE, "absent", false));
    property("blank", "  ");
    assertTrue(Entrypoint.boolProp(MODULE, "blank", true));
  }

  /// Boolean.parseBoolean is lenient: anything that is not "true" is false. Pinned
  /// because a user typing `yes` gets false rather than an error.
  @Test
  void boolPropIsLenientAboutNonTrueValues() {
    property("t", "true");
    assertTrue(Entrypoint.boolProp(MODULE, "t", false));
    property("upper", "TRUE");
    assertTrue(Entrypoint.boolProp(MODULE, "upper", false));
    property("mixed", "TrUe");
    assertTrue(Entrypoint.boolProp(MODULE, "mixed", false));

    for (final String value : new String[]{"yes", "1", "on", "false", "no"}) {
      property("v", value);
      assertFalse(Entrypoint.boolProp(MODULE, "v", true), value + " parses as false");
    }
  }

  @Test
  void durationPropFallsBackWhenUnsetOrBlank() {
    assertEquals(Duration.ofSeconds(5), Entrypoint.durationProp(MODULE, "absent", Duration.ofSeconds(5)));
    property("blank", "");
    assertEquals(Duration.ofSeconds(5), Entrypoint.durationProp(MODULE, "blank", Duration.ofSeconds(5)));
  }

  /// The "PT" prefix is optional, so `30s` and `PT30S` both work.
  @Test
  void durationPropAcceptsBareAndPrefixedValues() {
    property("bare", "30S");
    assertEquals(Duration.ofSeconds(30), Entrypoint.durationProp(MODULE, "bare", Duration.ZERO));
    property("prefixed", "PT30S");
    assertEquals(Duration.ofSeconds(30), Entrypoint.durationProp(MODULE, "prefixed", Duration.ZERO));
    property("minutes", "5M");
    assertEquals(Duration.ofMinutes(5), Entrypoint.durationProp(MODULE, "minutes", Duration.ZERO));
    property("compound", "1H30M");
    assertEquals(Duration.ofMinutes(90), Entrypoint.durationProp(MODULE, "compound", Duration.ZERO));
  }

  /// The prefix check is case sensitive, so a lowercase `pt30s` becomes `PTpt30s`
  /// and fails to parse rather than being accepted.
  @Test
  void durationPropRejectsMalformedValues() {
    property("lower", "pt30s");
    assertThrows(DateTimeParseException.class, () -> Entrypoint.durationProp(MODULE, "lower", Duration.ZERO));
    property("garbage", "soon");
    assertThrows(DateTimeParseException.class, () -> Entrypoint.durationProp(MODULE, "garbage", Duration.ZERO));
    property("empty-unit", "30");
    assertThrows(DateTimeParseException.class, () -> Entrypoint.durationProp(MODULE, "empty-unit", Duration.ZERO));
  }

  @Test
  void maybePluralAddsAnSExceptForOne() {
    assertEquals("1 key", Entrypoint.maybePlural(1, "key"));
    assertEquals("0 keys", Entrypoint.maybePlural(0, "key"));
    assertEquals("2 keys", Entrypoint.maybePlural(2, "key"));
    assertEquals("1000000 keys", Entrypoint.maybePlural(1_000_000, "key"));
  }

  /// Counts are unsigned — the searched counter is a u64 in spirit, so a value
  /// past Long.MAX_VALUE must print as a large positive number, not a negative.
  @Test
  void maybePluralPrintsCountsUnsigned() {
    assertEquals("18446744073709551615 keys", Entrypoint.maybePlural(-1L, "key"));
    assertEquals("9223372036854775808 keys", Entrypoint.maybePlural(Long.MIN_VALUE, "key"));
  }

  @Test
  void formatDurationStripsThePrefixAndSubSecondPrecision() {
    assertEquals("0S", Entrypoint.formatDuration(Duration.ZERO));
    assertEquals("30S", Entrypoint.formatDuration(Duration.ofSeconds(30)));
    assertEquals("1M30S", Entrypoint.formatDuration(Duration.ofSeconds(90)));
    assertEquals("1H", Entrypoint.formatDuration(Duration.ofHours(1)));
    // milliseconds are truncated away rather than rounded
    assertEquals("1S", Entrypoint.formatDuration(Duration.ofMillis(1_999)));
    assertEquals("0S", Entrypoint.formatDuration(Duration.ofMillis(999)));
  }

  @Test
  void readKeyPathRequiresAnOutputDirectory() {
    final var ex = assertThrows(IllegalStateException.class,
        () -> Entrypoint.readKeyPath(MODULE, null, null));
    assertTrue(ex.getMessage().contains("outDir"), ex.getMessage());

    property("outDir", "  ");
    final var blank = assertThrows(IllegalStateException.class,
        () -> Entrypoint.readKeyPath(MODULE, null, null));
    assertTrue(blank.getMessage().contains("output directory"), blank.getMessage());
  }

  @Test
  void readKeyPathCreatesTheDirectory(@TempDir final Path tempDir) {
    final var target = tempDir.resolve("keys");
    property("outDir", target.toString());

    final var keyPath = Entrypoint.readKeyPath(MODULE, null, null);
    assertEquals(target, keyPath);
    assertTrue(Files.isDirectory(keyPath));
  }

  /// The search terms become directory names so runs do not overwrite each other.
  /// A prefix and a suffix nest rather than concatenating.
  @Test
  void readKeyPathNamesDirectoriesAfterTheSearchTerms(@TempDir final Path tempDir) {
    property("outDir", tempDir.toString());
    final var begins = Subsequence.create("sava", true, false, false);
    final var ends = Subsequence.create("xyz", true, false, false);

    assertEquals(tempDir.resolve("sava_"), Entrypoint.readKeyPath(MODULE, begins, null));
    assertEquals(tempDir.resolve("_xyz"), Entrypoint.readKeyPath(MODULE, null, ends));
    assertEquals(tempDir.resolve("sava_").resolve("_xyz"), Entrypoint.readKeyPath(MODULE, begins, ends));

    assertTrue(Files.isDirectory(tempDir.resolve("sava_").resolve("_xyz")));
  }

  @Test
  void readKeyPathIsIdempotent(@TempDir final Path tempDir) {
    property("outDir", tempDir.toString());
    final var first = Entrypoint.readKeyPath(MODULE, null, null);
    final var second = Entrypoint.readKeyPath(MODULE, null, null);
    assertEquals(first, second);
    assertTrue(Files.isDirectory(second));
  }
}
