package software.sava.core.accounts.vanity;

import software.sava.core.encoding.Base58;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class BeginsWithMaskWorker extends BaseMaskWorker {

  BeginsWithMaskWorker(final Path keyPath,
                       final SecureRandom secureRandom,
                       final boolean sigVerify,
                       final Subsequence beginsWith,
                       final long find,
                       final AtomicInteger found,
                       final AtomicLong searched,
                       final Queue<Result> results,
                       final int checkFound) {
    super(keyPath, secureRandom, sigVerify, beginsWith, find, found, searched, results, checkFound);
  }

  @Override
  public void run() {
    long start = System.currentTimeMillis();
    for (int i = 0, keyStart; ; ) {
      generateKeyPair();

      keyStart = Base58.mutableEncode(mutablePublicKey, encoded);
      if (queueResult(start, keyStart)) {
        searched.getAndAccumulate(i, SUM);
        if (foundHitLimitOrInterrupted()) {
          return;
        } else {
          i = 0;
          start = System.currentTimeMillis();
        }
      } else if (++i == checkFound) {
        if (foundLimitOrInterrupted()) {
          return;
        } else {
          searched.getAndAccumulate(i, SUM);
          i = 0;
        }
      }
    }
  }
}
