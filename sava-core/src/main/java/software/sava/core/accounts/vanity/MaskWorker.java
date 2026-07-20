package software.sava.core.accounts.vanity;

import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.encoding.Base58;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class MaskWorker extends BaseMaskWorker {

  private final Subsequence endsWith;

  MaskWorker(final Path keyPath,
             final char[] password,
             final SecureRandom secureRandom,
             final PrivateKeyEncoding privateKeyEncoding,
             final KeyFileFormat keyFileFormat,
             final KeyDerivation keyDerivation,
             final boolean sigVerify,
             final Subsequence beginsWith,
             final Subsequence endsWith,
             final long find,
             final AtomicInteger found,
             final AtomicLong searched,
             final Queue<Result> results,
             final int checkFound,
             final long maxSearches) {
    super(keyPath, password, secureRandom, privateKeyEncoding, keyFileFormat, keyDerivation, sigVerify, beginsWith, find, found, searched, results, checkFound, maxSearches);
    this.endsWith = endsWith;
  }

  @Override
  public void run() {
    try {
      final int endLength = endsWith.length();
      final char[] shortEncoded = new char[endLength << 1];
      final int shortStart = shortEncoded.length - endLength;
      long fastEncodeOffsets;
      long start = System.currentTimeMillis();
      long attempts = 0;
      for (int i = 0, keyStart, shortKeyStart; ; ) {
        generateKeyPair();
        ++attempts;

        fastEncodeOffsets = Base58.beginMutableEncode(mutablePublicKey, endLength, shortEncoded);
        shortKeyStart = (int) fastEncodeOffsets;
        if (endsWith.contains(shortEncoded, shortStart)) {
          final int shortEncodedLen = shortEncoded.length - shortKeyStart;
          final int encodedStart = encoded.length - shortEncodedLen;

          keyStart = Base58.continueMutableEncode(
              mutablePublicKey,
              (int) (fastEncodeOffsets >>> 48),
              (int) (fastEncodeOffsets >>> 32) & 0xFFFF,
              encodedStart,
              encoded
          );
          if (queueResult(start, keyStart)) {
            searched.getAndAccumulate(i, SUM);
            if (foundHitLimitOrInterrupted()) {
              return;
            } else {
              i = 0;
              start = System.currentTimeMillis();
              continue;
            }
          }
        }
        if (++i == checkFound) {
          if (foundLimitOrInterrupted()) {
            return;
          } else {
            i = 0;
          }
        }
        if (searchExhausted(attempts)) {
          searched.getAndAccumulate(i, SUM);
          return;
        }
      }
    } finally {
      clearSecrets();
    }
  }
}
