package software.sava.core.accounts.vanity;

import software.sava.core.accounts.pbkdf.KeyDerivation;

import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public interface VanityAddressGenerator {

  /// Searches without a cap: workers generate key pairs until `findKeys` matches are found or
  /// they are interrupted. To cap attempts, use
  /// [#createGenerator(Path, char\[\], SecureRandomFactory, PrivateKeyEncoding, KeyFileFormat, KeyDerivation, boolean, ExecutorService, int, Subsequence, Subsequence, long, int, long)].
  static VanityAddressGenerator createGenerator(final Path keyPath,
                                                final char[] password,
                                                final SecureRandomFactory secureRandomFactory,
                                                final PrivateKeyEncoding privateKeyEncoding,
                                                final KeyFileFormat keyFileFormat,
                                                final KeyDerivation keyDerivation,
                                                final boolean sigVerify,
                                                final ExecutorService executor,
                                                final int numThreads,
                                                final Subsequence beginsWith,
                                                final Subsequence endsWith,
                                                final long findKeys,
                                                final int checkFound) {
    return createGenerator(
        keyPath, password, secureRandomFactory, privateKeyEncoding, keyFileFormat, keyDerivation,
        sigVerify, executor, numThreads, beginsWith, endsWith, findKeys, checkFound, Long.MAX_VALUE
    );
  }

  /// Starts `numThreads` search workers on `executor`.
  ///
  /// @param maxSearches key pairs after which each worker gives up; [Long#MAX_VALUE], as the
  ///                    other overloads use, is unbounded. Set a cap when the subsequence may
  ///                    be unsatisfiable. It is not a strict maximum: a worker generates at
  ///                    least one key pair, and an `endsWith` worker skips the check after a
  ///                    match. A worker that gives up queues no further results, so fewer than
  ///                    `findKeys` may arrive.
  /// @throws IllegalArgumentException if `findKeys` exceeds [Integer#MAX_VALUE]
  static VanityAddressGenerator createGenerator(final Path keyPath,
                                                final char[] password,
                                                final SecureRandomFactory secureRandomFactory,
                                                final PrivateKeyEncoding privateKeyEncoding,
                                                final KeyFileFormat keyFileFormat,
                                                final KeyDerivation keyDerivation,
                                                final boolean sigVerify,
                                                final ExecutorService executor,
                                                final int numThreads,
                                                final Subsequence beginsWith,
                                                final Subsequence endsWith,
                                                final long findKeys,
                                                final int checkFound,
                                                final long maxSearches) {
    if (findKeys > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Max find keys is " + Integer.MAX_VALUE);
    } else {
      try {
        final var found = new AtomicInteger(0);
        final var searched = new AtomicLong(0);
        final var results = new ArrayBlockingQueue<Result>(checkFound * numThreads);
        for (int i = 0; i < numThreads; ++i) {
          final var secureRandom = secureRandomFactory.createSecureRandom();
          final AddressWorker worker;
          if (endsWith == null) {
            worker = new BeginsWithMaskWorker(
                keyPath,
                password,
                secureRandom,
                privateKeyEncoding,
                keyFileFormat,
                keyDerivation,
                sigVerify,
                beginsWith,
                findKeys,
                found,
                searched,
                results,
                checkFound,
                maxSearches
            );
          } else {
            worker = new MaskWorker(
                keyPath,
                password,
                secureRandom,
                privateKeyEncoding,
                keyFileFormat,
                keyDerivation,
                sigVerify,
                beginsWith,
                endsWith,
                findKeys,
                found,
                searched,
                results,
                checkFound,
                maxSearches
            );
          }
          executor.execute(worker);
        }
        return new ConcurrentVanityAddressGenerator(findKeys, results, found, searched);
      } catch (final NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
      }
    }
  }

  static VanityAddressGenerator createGenerator(final Path keyPath,
                                                final char[] password,
                                                final PrivateKeyEncoding privateKeyEncoding,
                                                final KeyFileFormat keyFileFormat,
                                                final KeyDerivation keyDerivation,
                                                final boolean sigVerify,
                                                final ExecutorService executor,
                                                final int numThreads,
                                                final Subsequence beginsWith,
                                                final Subsequence endsWith,
                                                final long findKeys,
                                                final int checkFound) {
    return createGenerator(
        keyPath,
        password,
        SecureRandomFactory.DEFAULT,
        privateKeyEncoding,
        keyFileFormat,
        keyDerivation,
        sigVerify,
        executor,
        numThreads,
        beginsWith,
        endsWith,
        findKeys,
        checkFound
    );
  }

  int numFound();

  long numSearched();

  void breakOut();

  /// Returns the next queued result, or `null` if none is queued. It waits only if another
  /// thread takes the last result concurrently.
  Result take() throws InterruptedException;

  Result poll(final long timeout, final TimeUnit timeUnit) throws InterruptedException;
}
