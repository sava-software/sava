package software.sava.rpc.json.http.response;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// First-party defensive upstream conformance for the two error enums this client mirrors
/// one variant at a time. The committed fixture is generated from pinned `solana-*-error`
/// crates by the locked Rust program under `src/test/solana/error-variants`; no Rust
/// toolchain or network access is needed here.
///
/// The oracle is each enum's own `VARIANTS` constant, whose completeness upstream proves
/// with its own `test_*_variants_exhaustive` tests over `strum::EnumIter`. Nothing on either
/// side parses Rust source: the fixture comes from executing Rust, and the Java side is read
/// by reflection over the sealed hierarchy.
///
/// The shape column supports a presence check, not a schema check: a variant that carries a
/// payload upstream must be mirrored by a record with components, but the fixture does not
/// carry the payload's field count, so a two-field payload against a one-field record passes.
///
/// This is the check that would have caught `BailOut`, which upstream added on 2026-08-26
/// and which parsed to the `Unknown` catch-all here until 2026-09-19.
final class UpstreamErrorVariantsConformanceTests {

  private static final String RESOURCE = "/upstream/solana-error-variants.tsv";
  private static final String COLUMNS = "enum\tindex\tvariant\tshape";
  private static final HexFormat HEX = HexFormat.of();

  /// Java records with no upstream variant, each with the reason it is allowed to exist.
  private static final Map<String, String> JAVA_ONLY = Map.of(
      "Unknown", "catch-all so an unmodelled variant parses instead of throwing"
  );

  /// Records that keep a payload upstream no longer sends. The asymmetry is deliberate: a
  /// payload upstream carries and Java drops is data loss, so that stays a hard failure,
  /// but a payload upstream dropped may still reach a client reading historical data.
  private static final Map<String, String> RETAINED_PAYLOAD = Map.of(
      "BorshIoError", "unit upstream since solana-sdk v3; the String payload still appears in "
          + "historical data and IxError parses both wire forms"
  );

  private static final Map<String, String> EXPECTED_METADATA = Map.of(
      "format", "sava-solana-error-variants-v1",
      "solana-transaction-error", "4.0.0",
      "solana-transaction-error-published-from", "9d02e6dc068042257360dceef3f1fa7edd9527e6",
      "solana-instruction-error", "3.0.0",
      "solana-instruction-error-published-from", "5bcc7778a4f3d5ebb95735efeb7f1bae49122f9a",
      "solana-sdk-reviewed", "983858e1bb8ef9bd3c61f4a5172c705dae1f566b",
      "transaction-error-variants", "40",
      "instruction-error-variants", "55"
  );

  private record Variant(String enumName, int index, String name, String shape) {
  }

  @Test
  void everyUpstreamVariantIsMirroredAndNoPayloadIsDropped() throws IOException {
    final var lines = fixture();
    final var metadata = metadata(lines);
    EXPECTED_METADATA.forEach((key, expected) ->
        assertEquals(expected, metadata.get(key), () -> "fixture metadata for " + key));
    assertEquals(COLUMNS, lines.stream().filter(line -> !line.startsWith("#")).findFirst().orElseThrow(),
        "fixture column header");

    final var variants = variants(lines);
    assertEquals(95, variants.size(), "fixture rows");
    check("TransactionError", variants, TransactionError.class,
        Integer.parseInt(metadata.get("transaction-error-variants")));
    check("InstructionError", variants, IxError.class,
        Integer.parseInt(metadata.get("instruction-error-variants")));
  }

  private static void check(final String enumName,
                            final List<Variant> allVariants,
                            final Class<?> sealedInterface,
                            final int declaredCount) {
    final var upstream = allVariants.stream().filter(v -> v.enumName().equals(enumName)).toList();
    assertEquals(declaredCount, upstream.size(), () -> "row count for " + enumName);
    for (int i = 0; i < upstream.size(); i++) {
      final int index = i;
      assertEquals(i, upstream.get(i).index(), () -> "non-contiguous index in " + enumName + " at " + index);
    }

    final var mirrored = new LinkedHashMap<String, Integer>();
    for (final var permitted : sealedInterface.getPermittedSubclasses()) {
      final var components = permitted.getRecordComponents();
      assertNotNull(components, () -> permitted.getSimpleName() + " is not a record");
      assertNull(mirrored.put(permitted.getSimpleName(), components.length),
          () -> "duplicate record name " + permitted.getSimpleName());
    }

    final var missing = new LinkedHashSet<String>();
    for (final var variant : upstream) {
      final var components = mirrored.remove(variant.name());
      if (components == null) {
        missing.add(variant.name());
        continue;
      }
      if (variant.shape().equals("unit")) {
        // upstream sends nothing, so a record that takes something is modelling history
        if (components != 0) {
          assertTrue(RETAINED_PAYLOAD.containsKey(variant.name()),
              () -> enumName + '.' + variant.name() + " is a unit variant upstream but its record takes "
                  + components + "; if that is deliberate, say why in RETAINED_PAYLOAD");
        }
      } else {
        // upstream carries a payload, so a record that takes nothing silently drops it
        assertNotEquals(0, components,
            () -> enumName + '.' + variant.name() + " is a " + variant.shape() + " variant upstream but its record takes nothing");
      }
    }
    assertEquals(Set.of(), missing,
        () -> "upstream " + enumName + " variants with no record; run the error-variants generator and mirror them");
    assertEquals(JAVA_ONLY.keySet(), mirrored.keySet(),
        () -> "records with no upstream variant in " + enumName + "; every one needs a reason in JAVA_ONLY");
  }

  @Test
  void theFixtureWasGeneratedFromTheCommittedGeneratorInputs() throws IOException {
    final var metadata = metadata(fixture());
    assertFileSha256(metadata, "cargo-lock-sha256", "Cargo.lock");
    assertFileSha256(metadata, "cargo-manifest-sha256", "Cargo.toml");
    assertFileSha256(metadata, "generator-source-sha256", "src/main.rs");
    assertFileSha256(metadata, "rust-toolchain-sha256", "rust-toolchain.toml");
  }

  private static List<String> fixture() throws IOException {
    try (final var in = UpstreamErrorVariantsConformanceTests.class.getResourceAsStream(RESOURCE)) {
      assertNotNull(in, () -> "missing fixture " + RESOURCE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  private static Map<String, String> metadata(final List<String> lines) {
    final var metadata = new LinkedHashMap<String, String>();
    for (final var line : lines) {
      if (!line.startsWith("#")) {
        break;
      }
      final int colon = line.indexOf(':');
      assertTrue(colon > 0, () -> "malformed metadata line " + line);
      metadata.put(line.substring(1, colon).strip(), line.substring(colon + 1).strip());
    }
    return metadata;
  }

  private static List<Variant> variants(final List<String> lines) {
    final var variants = new ArrayList<Variant>();
    boolean pastHeader = false;
    for (final var line : lines) {
      if (line.startsWith("#") || line.isBlank()) {
        continue;
      }
      if (!pastHeader) {
        pastHeader = true;
        continue;
      }
      final var fields = line.split("\t", -1);
      assertEquals(4, fields.length, () -> "malformed row " + line);
      variants.add(new Variant(fields[0], Integer.parseInt(fields[1]), fields[2], fields[3]));
    }
    return variants;
  }

  private static void assertFileSha256(final Map<String, String> metadata,
                                       final String key,
                                       final String relativePath) throws IOException {
    final var path = generatorPath(relativePath);
    assertEquals(metadata.get(key), HEX.formatHex(sha256().digest(Files.readAllBytes(path))),
        () -> "stale fixture provenance for " + path + "; regenerate with `cargo run --locked --release -- --write`");
  }

  private static Path generatorPath(final String relativePath) {
    final var moduleRelative = Path.of("src/test/solana/error-variants").resolve(relativePath);
    if (Files.isRegularFile(moduleRelative)) {
      return moduleRelative;
    }
    final var repositoryRelative = Path.of("sava-rpc").resolve(moduleRelative);
    assertTrue(Files.isRegularFile(repositoryRelative), () -> "missing generator input " + relativePath);
    return repositoryRelative;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
