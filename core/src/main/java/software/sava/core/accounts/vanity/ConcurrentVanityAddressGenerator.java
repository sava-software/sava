package software.sava.core.accounts.vanity;

import java.util.concurrent.BlockingQueue;
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
    return results.isEmpty() ? null : results.take();
  }

  @Override
  public Result poll(final long timeout, final TimeUnit timeUnit) throws InterruptedException {
    if (found.get() >= findKeys) {
      return results.isEmpty() ? null : results.poll(timeout, timeUnit);
    } else {
      return results.poll(timeout, timeUnit);
    }
  }
}
