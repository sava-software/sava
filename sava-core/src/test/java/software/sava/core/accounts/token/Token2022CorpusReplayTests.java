package software.sava.core.accounts.token;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Deterministically replays the committed token2022 seed corpus through
/// [Token2022Fuzz] on every `check`, so the corpus cannot rot between fuzz
/// runs and PIT's mutants face the same round-trip oracle as the fuzzer —
/// real TLV chains (the PYUSD mint's 8 extensions, a confidential token
/// account) that from-scratch tests don't assemble. New seeds, including
/// minimized fuzz findings, replay here automatically.
final class Token2022CorpusReplayTests {

  @Test
  void token2022SeedCorpusReplay() throws IOException, URISyntaxException {
    final var url = Token2022CorpusReplayTests.class.getResource("/fuzz/token2022");
    assumeTrue(url != null && "file".equals(url.getProtocol()), "seed corpus not on the classpath as a directory");
    final var dir = Path.of(url.toURI());
    try (final var files = Files.list(dir)) {
      final var seeds = files.filter(Files::isRegularFile).sorted().toList();
      assertFalse(seeds.isEmpty(), "empty seed corpus at " + dir);
      for (final var seed : seeds) {
        final byte[] data = Files.readAllBytes(seed);
        assertDoesNotThrow(() -> Token2022Fuzz.fuzzerTestOneInput(data), seed.getFileName().toString());
      }
    }
  }
}
