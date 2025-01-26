package software.sava.core.accounts.vanity;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.crypto.Hash;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongBinaryOperator;

import static software.sava.core.crypto.ed25519.Ed25519Util.generatePublicKey;

abstract class BaseMaskWorker implements AddressWorker {

  static final LongBinaryOperator SUM = Long::sum;

  private final SecureRandom secureRandom;
  private final Subsequence beginsWith;
  private final long find;
  private final AtomicInteger found;
  protected final AtomicLong searched;
  private final Queue<Result> results;
  protected final int checkFound;
  private final MessageDigest digest = Hash.sha512Digest();
  private final byte[] privateKey;
  private final byte[] publicKey;
  protected final char[] encoded;
  private final byte[] mutableKeyPair;
  protected final byte[] mutablePublicKey;

  protected BaseMaskWorker(final SecureRandom secureRandom,
                           final Subsequence beginsWith,
                           final long find,
                           final AtomicInteger found,
                           final AtomicLong searched,
                           final Queue<Result> results,
                           final int checkFound) {
    this.secureRandom = secureRandom;
    this.beginsWith = beginsWith;
    this.find = find;
    this.found = found;
    this.searched = searched;
    this.results = results;
    this.checkFound = checkFound;
    this.privateKey = new byte[32];
    this.publicKey = new byte[32];
    this.encoded = new char[64];
    this.mutableKeyPair = new byte[64];
    this.mutablePublicKey = new byte[32];
  }

  @Override
  public final SecureRandom secureRandom() {
    return secureRandom;
  }

  @Override
  public final Subsequence beginsWith() {
    return beginsWith;
  }

  @Override
  public final long find() {
    return find;
  }

  @Override
  public final AtomicInteger found() {
    return found;
  }

  @Override
  public final AtomicLong searched() {
    return searched;
  }

  @Override
  public final Queue<Result> results() {
    return results;
  }

  protected final boolean queueResult(final long timeStart, final int keyStart) {
    if (beginsWith == null || beginsWith.contains(encoded, keyStart)) {
      final long end = System.currentTimeMillis();

      final byte[] keyPair = new byte[64];
      System.arraycopy(privateKey, 0, keyPair, 0, 32);
      System.arraycopy(publicKey, 0, keyPair, 32, 32);
      Signer.validateKeyPair(keyPair);

      final var result = new Result(
          PublicKey.createPubKey(Arrays.copyOfRange(keyPair, 32, 64)),
          keyPair,
          end - timeStart
      );
      results.add(result);
      return true;
    } else {
      return false;
    }
  }

  protected final void generateKeyPair() {
    secureRandom.nextBytes(privateKey);
    generatePublicKey(digest, privateKey, 0, publicKey, 0, mutablePublicKey, mutableKeyPair);
    System.arraycopy(publicKey, 0, mutablePublicKey, 0, 32);
  }

  protected final boolean foundLimitOrInterrupted() {
    if (found.getOpaque() >= find || Thread.currentThread().isInterrupted()) {
      return true;
    } else {
      searched.getAndAccumulate(checkFound, SUM);
      return false;
    }
  }

  protected final boolean incrementFoundHitsLimitOrInterrupted() {
    return this.found.incrementAndGet() >= find || Thread.currentThread().isInterrupted();
  }
}
