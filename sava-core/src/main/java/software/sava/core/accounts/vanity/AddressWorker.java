package software.sava.core.accounts.vanity;

import java.security.SecureRandom;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public interface AddressWorker extends Runnable {
  SecureRandom secureRandom();

  Subsequence beginsWith();

  long find();

  AtomicInteger found();

  AtomicLong searched();

  Queue<Result> results();
}
