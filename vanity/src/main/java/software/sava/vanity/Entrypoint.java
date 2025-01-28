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

  private static int intProp(final String moduleName, final String property, final int def) {
    final var val = System.getProperty(moduleName + '.' + property);
    return val == null || val.isBlank() ? def : Integer.parseInt(val);
  }

  private static boolean boolProp(final String moduleName, final String property, final boolean def) {
    final var val = System.getProperty(moduleName + '.' + property);
    return val == null || val.isBlank() ? def : Boolean.parseBoolean(val);
  }

  private static Duration durationProp(final String moduleName, final String property, final Duration def) {
    final var val = System.getProperty(moduleName + '.' + property);
    return val == null || val.isBlank()
        ? def
        : Duration.parse(val.startsWith("PT") ? val : "PT" + val);
  }

  private static Subsequence getSequence(final String moduleName, final String property, final char ps) {
    final var prefix = System.getProperty(moduleName + '.' + property);
    if (prefix == null || prefix.isBlank()) {
      return null;
    } else {
      return Subsequence.create(
          prefix,
          boolProp(moduleName, ps + "CaseSensitive", false),
          boolProp(moduleName, ps + "1337Numbers", true),
          boolProp(moduleName, ps + "1337Letters", true)
      );
    }
  }

  private static Path readKeyPath(final String moduleName, final Subsequence beginsWith, final Subsequence endsWith) {
    final var outDir = System.getProperty(moduleName + ".outDir");
    if (outDir == null || outDir.isBlank()) {
      return null;
    } else {
      var keyPath = Path.of(outDir);
      if (beginsWith != null) {
        keyPath = keyPath.resolve(beginsWith.subsequence() + '_');
      }
      if (endsWith != null) {
        keyPath = keyPath.resolve('_' + endsWith.subsequence());
      }
      try {
        Files.createDirectories(keyPath);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      return keyPath;
    }
  }

  public static void main(final String[] args) throws InterruptedException {
    final var moduleName = Entrypoint.class.getModule().getName();

    final var beginsWith = getSequence(moduleName, "prefix", 'p');
    final var endsWith = getSequence(moduleName, "suffix", 's');

    if (beginsWith == null && endsWith == null) {
      throw new IllegalStateException("Must configure a prefix or suffix.");
    }

    final int numThreads = intProp(
        moduleName,
        "numThreads",
        Math.max(1, Runtime.getRuntime().availableProcessors() >> 1)
    );

    try (final var executor = Executors.newFixedThreadPool(numThreads)) {
      final var keyPath = readKeyPath(moduleName, beginsWith, endsWith);
      final int findNumKeys = intProp(moduleName, "numKeys", 1);
      final int checkFound = intProp(moduleName, "checkFound", 131_072);
      final boolean sigVerify = boolProp(moduleName, "sigVerify", false);
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

      final long delayNanos = durationProp(moduleName, "logDelay", Duration.ofSeconds(5)).toNanos();
      final long upperBound = numThreads * (long) checkFound;

      final int numCombinations;
      final String basePattern;
      if (beginsWith == null) {
        numCombinations = endsWith.numCombinations();
        basePattern = "..." + endsWith.subsequence();
      } else if (endsWith == null) {
        numCombinations = beginsWith.numCombinations();
        basePattern = beginsWith.subsequence() + "...";
      } else {
        numCombinations = beginsWith.numCombinations() * endsWith.numCombinations();
        basePattern = beginsWith.subsequence() + "..." + endsWith.subsequence();
      }

      System.out.format(
          """
              
              %s searching for %s against %s of %s
              
              """,
          maybePlural(numThreads, "thread"),
          maybePlural(findNumKeys, "key"),
          maybePlural(numCombinations, "Base58 character combination"),
          basePattern
      );

      int numFound = 0;
      final long start = System.nanoTime();
      for (Result result; ; ) {
        result = generator.poll(delayNanos, TimeUnit.NANOSECONDS);
        if (result == null) {
          final long duration = System.nanoTime() - start;
          final long numSearched = generator.numSearched();
          System.out.printf(
              """
                  Found %,d key(s) out of [%,d, %,d) in %s
                  """,
              numFound,
              numSearched, numSearched + upperBound,
              formatDuration(Duration.ofNanos(duration))
          );
        } else {
          System.out.printf(
              """
                  Found account [%s] in %s
                  """,
              result.publicKey(),
              formatDuration(Duration.ofMillis(result.durationMillis()))
          );
          if (++numFound >= findNumKeys) {
            executor.shutdownNow();
            return;
          }
        }
      }
    }
  }

  private static String maybePlural(final long val, final String context) {
    final var prefix = Long.toUnsignedString(val) + ' ' + context;
    return val == 1 ? prefix : prefix + 's';
  }

  private static String formatDuration(final Duration duration) {
    return duration.truncatedTo(ChronoUnit.SECONDS).toString().substring(2);
  }
}
