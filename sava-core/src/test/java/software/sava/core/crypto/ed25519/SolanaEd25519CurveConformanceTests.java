package software.sava.core.crypto.ed25519;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/// First-party defensive upstream conformance for the Solana PDA curve-membership
/// predicate. The committed fixture is generated from pinned Solana SDK and Agave
/// runtime-backend implementations; no Rust toolchain or network access is needed by
/// the Java test suite.
final class SolanaEd25519CurveConformanceTests {

  private static final String RESOURCE = "/ed25519/solana-curve-vectors.tsv";
  private static final String COLUMNS =
      "id\tcategory\tcompressed_hex\tsdk_on_curve\tagave_runtime_backend_on_curve";
  private static final HexFormat HEX = HexFormat.of();
  private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
  private static final Pattern CATEGORY = Pattern.compile("[a-z0-9_]+");
  private static final Pattern COMPRESSED_POINT = Pattern.compile("[0-9a-f]{64}");
  private static final byte[] P = HEX.parseHex(
      "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"
  );
  private static final byte[] SHA256_DOMAIN =
      "sava:solana-ed25519-curve:v1".getBytes(StandardCharsets.US_ASCII);
  private static final String[] TORSION = {
      "0100000000000000000000000000000000000000000000000000000000000000",
      "0000000000000000000000000000000000000000000000000000000000000000",
      "0000000000000000000000000000000000000000000000000000000000000080",
      "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
      "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
      "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa",
      "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
      "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85"
  };
  private static final Map<String, String> EXPECTED_METADATA = Map.ofEntries(
      Map.entry("format", "sava-solana-ed25519-curve-v1"),
      Map.entry("property", "Sava isNotOnCurve is the logical negation of Solana bytes_are_curve_point"),
      Map.entry("agave", "v4.2.0 ac82b5d438b0c2303dc7169f52c748977713a111"),
      Map.entry("agave-cargo-lock-sha256", "5f29b3869fa78fae8f7780ba10b428198c1c3e5c0ac39153485a942931908557"),
      Map.entry("solana-pubkey", "4.2.0 5b985fd7b60de1c845c25bb2d4fc16e19c9ee6ab"),
      Map.entry("solana-pubkey-crate-checksum", "7db719574990de7e8b0f55a8593ac92a5ccb42c8ce67b3e4bf05b139d5d9ee71"),
      Map.entry("solana-address", "2.6.1 14a725d6e9180e6cfbd98054473d61ef3aabde57"),
      Map.entry("solana-address-crate-checksum", "39c93e262f671bf402e1040e4a7e40b05d81da5956c7681948c975a0997517bb"),
      Map.entry("solana-curve25519", "4.0.1 a947d32fb1ab7d06b69bd96bd97eb4d002eb454e"),
      Map.entry("solana-curve25519-crate-checksum", "14b4d2a4bf0d0b0a86c22111917e86e8bd39a7b31420fb2c7d73eb83761fc7af"),
      Map.entry("curve25519-dalek", "4.1.3 5312a0311ec40df95be953eacfa8a11b9a34bc54"),
      Map.entry("curve25519-dalek-crate-checksum", "97fb8b7c4503de7d6ae7b42ab72a5a59857b4c937ec27a3d4539dba95b5ab2be"),
      Map.entry("sha2", "0.10.9"),
      Map.entry("sha2-crate-checksum", "a7507d819769d01a365ab707794a4084392c824f54a7a6a7862f8c3d0892b283"),
      Map.entry("cargo-lock-sha256", "70bc5c79a483e96c97406deb8946eec20d3e04f7ad27da2feda9b78e1ce6d2e6"),
      Map.entry("cargo-manifest-sha256", "b73f72114eb69987a429106bbfb82fafc85b45c447c5c2c466653420d99cdfc8"),
      Map.entry("generator-source-sha256", "2bb75f9ce2716040556a53a70993c666ea6ef1eff0531c8b1898e92e77045fdf"),
      Map.entry("rust-toolchain", "1.96.1"),
      Map.entry("rust-toolchain-sha256", "6791dcb04edcb490caaf08590dbe1dd9d9954b46dab2095df12dc984f704f1a8"),
      Map.entry("sha256-domain", "sava:solana-ed25519-curve:v1"),
      Map.entry("category-counts", "sentinel=2,torsion=8,reduced=38,noncanonical=38,low_boundary=128,high_boundary=128,one_hot=510,sha256=512"),
      Map.entry("rows", "1364")
  );

  @Test
  void matchesPinnedSolanaSdkAndAgaveRuntimeBackendVectors() throws IOException {
    final var fixture = loadFixture();
    final var expectedVectors = expectedVectors();
    final var actualIds = new HashSet<String>();
    final var categoryCounts = new HashMap<String, Integer>();

    for (final var vector : fixture.vectors()) {
      assertTrue(actualIds.add(vector.id()), () -> "duplicate vector id: " + vector.id());
      final var expected = expectedVectors.remove(vector.id());
      assertNotNull(expected, () -> "unexpected vector id: " + vector.id());
      assertEquals(expected.category(), vector.category(), () -> "category for " + vector.id());
      assertArrayEquals(
          expected.compressed(),
          vector.compressed(),
          () -> "compressed point for " + vector.id()
      );
      categoryCounts.merge(vector.category(), 1, Integer::sum);
      assertEquals(
          vector.sdkOnCurve(),
          vector.agaveRuntimeBackendOnCurve(),
          () -> "pinned upstream predicates disagree for " + vector.id() + ": " + vector.hex()
      );
      assertEquals(
          vector.sdkOnCurve(),
          !Ed25519Util.isNotOnCurve(vector.compressed()),
          () -> "Sava/Solana disagreement for " + vector.id() + ": " + vector.hex()
      );
    }

    assertTrue(expectedVectors.isEmpty(), () -> "missing vector ids: " + expectedVectors.keySet());
    assertEquals(
        Map.of(
            "sentinel", 2,
            "torsion", 8,
            "reduced", 38,
            "noncanonical", 38,
            "low_boundary", 128,
            "high_boundary", 128,
            "one_hot", 510,
            "sha256", 512
        ),
        categoryCounts
    );
  }

  @Test
  void fixtureProvenanceMatchesCommittedGeneratorInputs() throws IOException {
    final var metadata = loadFixture().metadata();
    assertFileSha256(metadata, "cargo-lock-sha256", "Cargo.lock");
    assertFileSha256(metadata, "cargo-manifest-sha256", "Cargo.toml");
    assertFileSha256(metadata, "generator-source-sha256", "src/main.rs");
    assertFileSha256(metadata, "rust-toolchain-sha256", "rust-toolchain.toml");
  }

  private static Fixture loadFixture() throws IOException {
    final var input = SolanaEd25519CurveConformanceTests.class.getResourceAsStream(RESOURCE);
    assertNotNull(input, "missing fixture " + RESOURCE);
    final var metadata = new LinkedHashMap<String, String>();
    final var vectors = new ArrayList<Vector>();
    boolean sawColumns = false;
    try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        ++lineNumber;
        final int currentLine = lineNumber;
        assertFalse(line.isBlank(), () -> "blank line at " + currentLine);
        if (line.startsWith("# ")) {
          assertFalse(sawColumns, () -> "metadata after columns at " + currentLine);
          final int separator = line.indexOf(": ", 2);
          assertTrue(separator > 2, () -> "malformed metadata at " + currentLine);
          final var key = line.substring(2, separator);
          final var previous = metadata.putIfAbsent(key, line.substring(separator + 2));
          assertNull(previous, () -> "duplicate metadata key at " + currentLine + ": " + key);
        } else if (!sawColumns) {
          assertEquals(COLUMNS, line, () -> "unexpected columns at " + currentLine);
          sawColumns = true;
        } else {
          vectors.add(parseVector(line, currentLine));
        }
      }
    }
    assertTrue(sawColumns, "missing fixture columns");
    assertEquals(EXPECTED_METADATA, metadata, "fixture provenance changed");
    assertEquals(1_364, vectors.size(), "fixture row count");
    return new Fixture(metadata, vectors);
  }

  private static Vector parseVector(final String line, final int lineNumber) {
    final var fields = line.split("\\t", -1);
    assertEquals(5, fields.length, () -> "field count at " + lineNumber);
    assertTrue(ID.matcher(fields[0]).matches(), () -> "invalid id at " + lineNumber);
    assertTrue(CATEGORY.matcher(fields[1]).matches(), () -> "invalid category at " + lineNumber);
    assertTrue(COMPRESSED_POINT.matcher(fields[2]).matches(), () -> "invalid compressed point at " + lineNumber);
    return new Vector(
        fields[0],
        fields[1],
        fields[2],
        HEX.parseHex(fields[2]),
        parseBoolean(fields[3], lineNumber),
        parseBoolean(fields[4], lineNumber)
    );
  }

  private static boolean parseBoolean(final String value, final int lineNumber) {
    assertTrue(value.equals("true") || value.equals("false"), () -> "invalid boolean at " + lineNumber);
    return Boolean.parseBoolean(value);
  }

  private static Map<String, ExpectedVector> expectedVectors() {
    final var vectors = new HashMap<String, ExpectedVector>();
    putExpected(
        vectors,
        "sentinel_basepoint",
        "sentinel",
        HEX.parseHex("5866666666666666666666666666666666666666666666666666666666666666")
    );
    putExpected(vectors, "sentinel_all_ff", "sentinel", HEX.parseHex("ff".repeat(32)));
    for (int index = 0; index < TORSION.length; ++index) {
      putExpected(vectors, "torsion_%02d".formatted(index), "torsion", HEX.parseHex(TORSION[index]));
    }
    for (int k = 0; k < 19; ++k) {
      final var reduced = new byte[32];
      reduced[0] = (byte) k;
      addBothSigns(vectors, "reduced_%02d".formatted(k), "reduced", reduced);

      final var noncanonical = P.clone();
      noncanonical[0] += (byte) k;
      addBothSigns(vectors, "noncanonical_%02d".formatted(k), "noncanonical", noncanonical);
    }
    for (int y = 0; y < 64; ++y) {
      final var compressed = new byte[32];
      compressed[0] = (byte) y;
      addBothSigns(vectors, "low_%02d".formatted(y), "low_boundary", compressed);
    }
    for (int offset = 1; offset <= 64; ++offset) {
      addBothSigns(
          vectors,
          "high_p_minus_%02d".formatted(offset),
          "high_boundary",
          subtractSmall(P, offset)
      );
    }
    for (int bit = 0; bit < 255; ++bit) {
      final var compressed = new byte[32];
      compressed[bit >>> 3] = (byte) (1 << (bit & 7));
      addBothSigns(vectors, "one_hot_%03d".formatted(bit), "one_hot", compressed);
    }
    final var digest = sha256();
    for (int counter = 0; counter < 512; ++counter) {
      digest.update(SHA256_DOMAIN);
      final var littleEndianCounter = new byte[Long.BYTES];
      for (int index = 0; index < littleEndianCounter.length; ++index) {
        littleEndianCounter[index] = (byte) ((long) counter >>> (index << 3));
      }
      putExpected(
          vectors,
          "sha256_%03d".formatted(counter),
          "sha256",
          digest.digest(littleEndianCounter)
      );
    }
    assertEquals(1_364, vectors.size());
    return vectors;
  }

  private static void addBothSigns(
      final Map<String, ExpectedVector> vectors,
      final String id,
      final String category,
      final byte[] compressed
  ) {
    final var sign0 = compressed.clone();
    sign0[31] &= 0x7f;
    putExpected(vectors, id + "_sign0", category, sign0);
    final var sign1 = sign0.clone();
    sign1[31] |= (byte) 0x80;
    putExpected(vectors, id + "_sign1", category, sign1);
  }

  private static void putExpected(
      final Map<String, ExpectedVector> vectors,
      final String id,
      final String category,
      final byte[] compressed
  ) {
    assertNull(vectors.put(id, new ExpectedVector(category, compressed)), () -> "duplicate expected id: " + id);
  }

  private static byte[] subtractSmall(final byte[] value, final int amount) {
    final var difference = value.clone();
    int borrow = amount;
    for (int index = 0; index < difference.length && borrow != 0; ++index) {
      final int next = Byte.toUnsignedInt(difference[index]) - borrow;
      if (next < 0) {
        difference[index] = (byte) (next + 256);
        borrow = 1;
      } else {
        difference[index] = (byte) next;
        borrow = 0;
      }
    }
    assertEquals(0, borrow);
    return difference;
  }

  private static void assertFileSha256(
      final Map<String, String> metadata,
      final String metadataKey,
      final String relativePath
  ) throws IOException {
    final var path = generatorPath(relativePath);
    assertEquals(
        metadata.get(metadataKey),
        HEX.formatHex(sha256().digest(Files.readAllBytes(path))),
        () -> "stale fixture provenance for " + path
    );
  }

  private static Path generatorPath(final String relativePath) {
    final var moduleRelative = Path.of("src/test/solana/ed25519-vectors").resolve(relativePath);
    if (Files.isRegularFile(moduleRelative)) {
      return moduleRelative;
    }
    final var repositoryRelative = Path.of("sava-core").resolve(moduleRelative);
    assertTrue(Files.isRegularFile(repositoryRelative), () -> "missing generator input " + relativePath);
    return repositoryRelative;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new AssertionError("JDK has no SHA-256 provider", e);
    }
  }

  private record Fixture(Map<String, String> metadata, ArrayList<Vector> vectors) {
  }

  private record ExpectedVector(String category, byte[] compressed) {
  }

  private record Vector(
      String id,
      String category,
      String hex,
      byte[] compressed,
      boolean sdkOnCurve,
      boolean agaveRuntimeBackendOnCurve
  ) {
  }
}
