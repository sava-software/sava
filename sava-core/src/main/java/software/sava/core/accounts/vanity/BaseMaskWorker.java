package software.sava.core.accounts.vanity;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.accounts.pbkdf.PBKDFEncryption;
import software.sava.core.crypto.Hash;
import software.sava.core.encoding.Base58;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongBinaryOperator;

import static software.sava.core.crypto.ed25519.Ed25519Util.generatePublicKey;

abstract class BaseMaskWorker implements AddressWorker {

  static final LongBinaryOperator SUM = Long::sum;

  private final Path keyPath;
  private final char[] password;
  private final SecureRandom secureRandom;
  private final PrivateKeyEncoding privateKeyEncoding;
  private final KeyFileFormat keyFileFormat;
  private final KeyDerivation keyDerivation;
  private final boolean sigVerify;
  private final Subsequence beginsWith;
  private final long find;
  private final AtomicInteger found;
  protected final AtomicLong searched;
  private final Queue<Result> results;
  protected final int checkFound;
  private final long maxSearches;
  private final MessageDigest digest = Hash.sha512Digest();
  private final byte[] privateKey;
  private final byte[] publicKey;
  protected final char[] encoded;
  private final byte[] mutableKeyPair;
  protected final byte[] mutablePublicKey;

  protected BaseMaskWorker(final Path keyPath,
                           final char[] password,
                           final SecureRandom secureRandom,
                           final PrivateKeyEncoding privateKeyEncoding,
                           final KeyFileFormat keyFileFormat,
                           final KeyDerivation keyDerivation,
                           final boolean sigVerify,
                           final Subsequence beginsWith,
                           final long find,
                           final AtomicInteger found,
                           final AtomicLong searched,
                           final Queue<Result> results,
                           final int checkFound,
                           final long maxSearches) {
    this.keyPath = keyPath;
    this.password = password;
    this.secureRandom = secureRandom;
    this.privateKeyEncoding = privateKeyEncoding;
    this.keyFileFormat = keyFileFormat;
    this.keyDerivation = keyDerivation;
    this.sigVerify = sigVerify;
    this.beginsWith = beginsWith;
    this.find = find;
    this.found = found;
    this.searched = searched;
    this.results = results;
    this.checkFound = checkFound;
    this.maxSearches = maxSearches;
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

  protected static final byte[] VERIFY_MSG = "sava".getBytes();

  protected final boolean queueResult(final long timeStart, final int keyStart) {
    if (beginsWith == null || beginsWith.contains(encoded, keyStart)) {
      final long end = System.currentTimeMillis();

      final byte[] keyPair = new byte[64];
      System.arraycopy(privateKey, 0, keyPair, 0, 32);
      System.arraycopy(publicKey, 0, keyPair, 32, 32);

      final PublicKey publicKey;
      if (sigVerify) {
        final var signer = Signer.createFromKeyPair(keyPair);
        final var sig = signer.sign(VERIFY_MSG);
        publicKey = signer.publicKey();
        if (!publicKey.verifySignature(VERIFY_MSG, sig)) {
          throw new IllegalStateException(
              "Invalid signature for key pair " + Base64.getEncoder().encodeToString(keyPair)
          );
        }
        final var javaPublicKey = publicKey.toJavaPublicKey();
        if (!PublicKey.verifySignature(javaPublicKey, VERIFY_MSG, sig)) {
          throw new IllegalStateException(
              "Failed to verify signature using a Java PublicKey for key pair "
                  + Base64.getEncoder().encodeToString(keyPair)
          );
        }
      } else {
        Signer.validateKeyPair(keyPair);
        publicKey = PublicKey.readPubKey(keyPair, 32);
      }

      final var result = new Result(publicKey, keyPair, end - timeStart);

      if (keyPath != null) {
        try {
          final String keyData;
          final String fileExtension;
          if (password == null) {
            final var rawSecret = switch (privateKeyEncoding) {
              case jsonKeyPairArray -> {
                final var json = new StringBuilder("[");
                for (int i = 0; ; ) {
                  json.append(Byte.toUnsignedInt(keyPair[i]));
                  if (++i == keyPair.length) {
                    break;
                  } else {
                    json.append(',');
                  }
                }
                json.append(']');
                yield json.toString();
              }
              case base64PrivateKey -> Base64.getEncoder().encodeToString(privateKey);
              case base64KeyPair -> Base64.getEncoder().encodeToString(keyPair);
              case base58PrivateKey -> Base58.encode(privateKey);
              case base58KeyPair -> Base58.encode(keyPair);
            };
            if (keyFileFormat == KeyFileFormat.properties) {
              fileExtension = ".properties";
              keyData = String.format(
                  """
                      pubKey=%s
                      encoding=%s
                      secret=%s
                      """,
                  publicKey,
                  privateKeyEncoding,
                  rawSecret
              );
            } else {
              fileExtension = ".json";
              keyData = String.format(
                  """
                      {
                        "pubKey": "%s",
                        "encoding": "%s",
                        "secret": %s
                      }""",
                  publicKey,
                  privateKeyEncoding,
                  privateKeyEncoding == PrivateKeyEncoding.jsonKeyPairArray
                      ? rawSecret
                      : '"' + rawSecret + '"'
              );
            }
          } else {
            final var encrypted = PBKDFEncryption.encrypt(
                password, secureRandom, keyPair, keyDerivation, publicKey.toByteArray()
            );
            if (keyFileFormat == KeyFileFormat.properties) {
              fileExtension = ".properties";
              keyData = encrypted.toPropertiesString(publicKey);
            } else {
              fileExtension = ".json";
              keyData = encrypted.toJson(publicKey);
            }
          }

          Files.writeString(
              keyPath.resolve(publicKey.toBase58() + fileExtension),
              keyData,
              StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
          );
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      results.add(result);
      found.incrementAndGet();
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

  // Zero out the per-worker scratch buffers that hold secret material once the
  // worker is done searching. `privateKey` holds the last generated seed and
  // `mutableKeyPair` holds its SHA-512 expansion (the ed25519 secret scalar).
  // The shared `password` and the `keyPair` handed off via `Result` are owned
  // by the caller and intentionally left untouched here.
  protected final void clearSecrets() {
    Arrays.fill(privateKey, (byte) 0);
    Arrays.fill(mutableKeyPair, (byte) 0);
  }

  protected final boolean foundLimitOrInterrupted() {
    if (found.getOpaque() >= find || Thread.currentThread().isInterrupted()) {
      return true;
    } else {
      searched.getAndAccumulate(checkFound, SUM);
      return false;
    }
  }

  protected final boolean foundHitLimitOrInterrupted() {
    return this.found.get() >= find || Thread.currentThread().isInterrupted();
  }

  /// Bounds an otherwise unbounded search. A worker asked for a subsequence that
  /// its encoder can never produce would spin forever, so callers that cannot
  /// afford that — tests, and any caller wanting a deadline — cap the number of
  /// key pairs generated. [Long#MAX_VALUE] is the unbounded default.
  ///
  /// @param attempts key pairs this worker has generated so far.
  /// @return true once the cap is reached, at which point the worker stops
  /// without having found anything.
  protected final boolean searchExhausted(final long attempts) {
    return attempts >= maxSearches;
  }
}
