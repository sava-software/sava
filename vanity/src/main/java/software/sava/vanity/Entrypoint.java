package software.sava.vanity;

import software.sava.core.accounts.vanity.Result;
import software.sava.core.accounts.vanity.Subsequence;
import software.sava.core.accounts.vanity.VanityAddressGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Entrypoint {

  private static int intProp(final String module,
                             final String property,
                             final int def) {
    final var val = System.getProperty(module + '.' + property);
    return val == null || val.isBlank() ? def : Integer.parseInt(val);
  }

  private static boolean boolProp(final String module,
                                  final String property,
                                  final boolean def) {
    final var val = System.getProperty(module + '.' + property);
    return val == null || val.isBlank() ? def : Boolean.parseBoolean(val);
  }

  private static Duration durationProp(final String module,
                                       final String property,
                                       final Duration def) {
    final var val = System.getProperty(module + '.' + property);
    return val == null || val.isBlank()
        ? def
        : Duration.parse(val.startsWith("PT") ? val : "PT" + val);
  }

  private static Subsequence getSequence(final String module,
                                         final String property,
                                         final char ps) {
    final var prefix = System.getProperty(module + '.' + property);
    if (prefix == null || prefix.isBlank()) {
      return null;
    } else {
      return Subsequence.create(
          prefix,
          boolProp(module, ps + "CaseSensitive", false),
          boolProp(module, ps + "1337Numbers", true),
          boolProp(module, ps + "1337Letters", true)
      );
    }
  }

  public static void main(final String[] args) throws InterruptedException {
    final var module = Entrypoint.class.getModule().getName();

    final var beginsWith = getSequence(module, "prefix", 'p');
    final var endsWith = getSequence(module, "suffix", 's');

    if (beginsWith == null && endsWith == null) {
      throw new IllegalStateException("Must configure a prefix or suffix.");
    }

    final int numThreads = intProp(
        module,
        "numThreads",
        Math.max(1, Runtime.getRuntime().availableProcessors() >> 1)
    );

    try (final var executor = Executors.newFixedThreadPool(numThreads)) {
      final int findNumKeys = intProp(module, "numKeys", 1);
      final var outDir = System.getProperty(module + ".outDir");
      Path keyPath;
      if (outDir == null || outDir.isBlank()) {
        keyPath = null;
      } else {
        keyPath = Path.of(outDir);
        if (beginsWith != null) {
          keyPath = keyPath.resolve('[' + beginsWith.subsequence());
        }
        if (endsWith != null) {
          keyPath = keyPath.resolve(endsWith.subsequence() + ']');
        }
        try {
          Files.createDirectories(keyPath);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      final int checkFound = intProp(module, "checkFound", 131_072);
      final boolean sigVerify = boolProp(module, "sigVerify", false);
      final var generator = VanityAddressGenerator.createGenerator(
          keyPath,
          sigVerify,
          executor,
          numThreads,
          beginsWith,
          endsWith,
          findNumKeys,
          checkFound
      );

      final var logDelay = durationProp(module, "logDelay", Duration.ofSeconds(5));
      final long delayNanos = logDelay.toNanos();
      final long upperBound = numThreads * (long) checkFound;
      final long start = System.nanoTime();
      for (Result result; ; ) {
        result = generator.poll(delayNanos, TimeUnit.NANOSECONDS);
        if (result == null) {
          final long duration = System.nanoTime() - start;
          final long numSearched = generator.numSearched();
          System.out.printf(
              """
                  Found %,d keys out of [%,d, %,d) in %s
                  """,
              generator.numFound(),
              numSearched, numSearched + upperBound,
              Duration.ofNanos(duration).truncatedTo(ChronoUnit.SECONDS).toString().substring(2)
          );
        } else {
          System.out.printf(
              """
                  Found account [%s] in %s
                  """,
              result.publicKey(),
              Duration.ofMillis(result.durationMillis()).truncatedTo(ChronoUnit.SECONDS).toString().substring(2)
          );
          if (generator.numFound() >= findNumKeys) {
            executor.shutdownNow();
            return;
          }
        }
      }
    }
  }
}
