package software.sava.vanity;

import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.accounts.vanity.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
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
      throw new IllegalStateException(
          "An output directory is required so the generated keys are saved to disk; "
              + "set the " + moduleName + ".outDir system property."
      );
    }
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

  private static final String PASSWORD_ENV_VAR = "SAVA_VANITY_ENCRYPT_PASSWORD";

  private static char[] readPassword(final String moduleName) {
    final var val = System.getProperty(moduleName + ".encrypt");
    if (val == null || val.equalsIgnoreCase("false")) {
      return null;
    }
    // Allow the password to be supplied securely via an environment variable so that
    // generation can run non-interactively (e.g. headless / detached sessions). The
    // value is never placed on the command line or in a system property, so it is not
    // exposed in process listings; environment variables are only readable by the
    // process owner.
    final var envPassword = System.getenv(PASSWORD_ENV_VAR);
    if (envPassword != null && !envPassword.isEmpty()) {
      return envPassword.toCharArray();
    }
    final var console = System.console();
    if (console == null) {
      throw new IllegalStateException(
          "A console is required to securely read the encryption password, "
              + "or supply it via the " + PASSWORD_ENV_VAR + " environment variable."
      );
    }
    for (; ; ) {
      final char[] password = console.readPassword("Enter encryption password: ");
      final char[] confirm = console.readPassword("Confirm encryption password: ");
      try {
        if (password == null || password.length == 0) {
          console.printf("Password must not be empty.%n");
          continue;
        }
        if (!Arrays.equals(password, confirm)) {
          console.printf("Passwords did not match, please try again.%n");
          continue;
        }
        return password.clone();
      } finally {
        if (password != null) {
          Arrays.fill(password, '\0');
        }
        if (confirm != null) {
          Arrays.fill(confirm, '\0');
        }
      }
    }
  }

  static void main() throws InterruptedException {
    final var moduleName = Entrypoint.class.getModule().getName();

    final char[] password = readPassword(moduleName);
    try {
      run(moduleName, password);
    } finally {
      if (password != null) {
        Arrays.fill(password, '\0');
      }
    }
  }

  private static void run(final String moduleName, final char[] password) throws InterruptedException {
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
      final var keyFormat = System.getProperty(moduleName + ".keyFormat");
      final var privateKeyEncoding = keyFormat == null || keyFormat.isBlank()
          ? PrivateKeyEncoding.base64KeyPair
          : PrivateKeyEncoding.valueOf(keyFormat);
      final var keyFileFormatProp = System.getProperty(moduleName + ".keyFileFormat");
      final var keyFileFormat = keyFileFormatProp == null || keyFileFormatProp.isBlank()
          ? KeyFileFormat.properties
          : KeyFileFormat.valueOf(keyFileFormatProp);
      final var kdfProp = System.getProperty(moduleName + ".kdf");
      final KeyDerivation keyDerivation;
      if (kdfProp == null || kdfProp.isBlank() || kdfProp.equalsIgnoreCase("pbkdf2")) {
        final int iterations = intProp(moduleName, "kdfIterations", 0);
        keyDerivation = iterations > 0
            ? KeyDerivation.createPBKDF2WithHmacSHA512(iterations)
            : KeyDerivation.defaultPBKDF2WithHmacSHA512();
      } else if (kdfProp.equalsIgnoreCase("argon2id")) {
        // Argon2id parameters are all-or-nothing: either none are supplied (use the
        // defaults) or all three (memory, parallelism, iterations) must be provided.
        final int memoryKb = intProp(moduleName, "kdfMemoryKB", 0);
        final int parallelism = intProp(moduleName, "kdfParallelism", 0);
        final int iterations = intProp(moduleName, "kdfIterations", 0);
        final int provided = (memoryKb > 0 ? 1 : 0) + (parallelism > 0 ? 1 : 0) + (iterations > 0 ? 1 : 0);
        if (provided == 0) {
          keyDerivation = KeyDerivation.defaultArgon2id();
        } else if (provided == 3) {
          keyDerivation = KeyDerivation.createArgon2id(memoryKb, parallelism, iterations);
        } else {
          throw new IllegalStateException(
              "Argon2id parameters are all-or-nothing: provide all of kdfMemoryKB, "
                  + "kdfParallelism and kdfIterations, or none of them."
          );
        }
      } else {
        throw new IllegalStateException("Unsupported key derivation function: " + kdfProp);
      }
      final var generator = VanityAddressGenerator.createGenerator(
          keyPath,
          password,
          privateKeyEncoding,
          keyFileFormat,
          keyDerivation,
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
