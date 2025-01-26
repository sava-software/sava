package software.sava.core.accounts.vanity;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class ConcurrentVanityAddressGenerator implements VanityAddressGenerator {

  private final long findKeys;
  private final BlockingQueue<Result> results;
  private final AtomicInteger found;
  private final AtomicLong searched;

  ConcurrentVanityAddressGenerator(final long findKeys,
                                   final BlockingQueue<Result> results,
                                   final AtomicInteger found,
                                   final AtomicLong searched) {
    this.findKeys = findKeys;
    this.results = results;
    this.found = found;
    this.searched = searched;
  }

  @Override
  public int numFound() {
    return found.get();
  }

  @Override
  public long numSearched() {
    return searched.get();
  }

  @Override
  public void breakOut() {
    this.found.set(Integer.MAX_VALUE);
  }

  @Override
  public Result take() throws InterruptedException {
    if (found.get() >= findKeys) {
      return results.isEmpty() ? null : results.take();
    } else {
      return results.take();
    }
  }

  @Override
  public Result poll(final long timeout, final TimeUnit timeUnit) throws InterruptedException {
    if (found.get() >= findKeys) {
      return results.isEmpty() ? null : results.poll(timeout, timeUnit);
    } else {
      return results.poll(timeout, timeUnit);
    }
  }

  public static void main(final String[] args) throws InterruptedException {
    final int numThreads = 10;
    try (final var executor = Executors.newFixedThreadPool(numThreads)) {
      final var beginsWith = Subsequence.create("sava", false, true, true);
      final var endsWith = Subsequence.create("", false, true, true);
      final int findNumKeys = 32;
      final var generator = VanityAddressGenerator.createGenerator(
          SecureRandomFactory.DEFAULT, executor, numThreads, beginsWith, endsWith, findNumKeys
      );
      final long start = System.currentTimeMillis();
      final long defaultDelay = 8;
      long delay = defaultDelay;
      for (Result result; ; ) {
        result = generator.poll(delay, TimeUnit.SECONDS);
        if (result == null) {
          final long numSearched = generator.numSearched();
          if (numSearched > 0) {
            System.out.printf("""
                    Found %,d out of %,d accounts in %s
                    """,
                generator.numFound(),
                numSearched,
                Duration.ofMillis(System.currentTimeMillis() - start).toString().substring(2)
            );
          } else {
            delay = 1;
            continue;
          }
        } else {
          System.out.printf("""
                  Found account [%s] in %s
                  """,
              result.publicKey(), Duration.ofMillis(result.durationMillis()).toString().substring(2)
          );
          if (generator.numFound() >= findNumKeys) {
            executor.shutdownNow();
            return;
          }
        }
        delay = defaultDelay;
      }
    }
  }
}
