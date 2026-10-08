package software.sava.core.accounts.vanity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import software.sava.core.accounts.Signer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.SignatureSpi;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/// The worker re-checks every key pair before handing it out. The private half must
/// derive the public half, whatever `sigVerify` says; with `sigVerify`, a fresh
/// signature must also verify under the library's verifier and under the JDK's, which
/// `Signature.getInstance("Ed25519")` resolves by provider preference order. A pair
/// that fails is refused with an `IllegalStateException` that names it, and is never
/// queued, counted as found, or written.
///
/// No check can fail on a pair the worker derived with a working crypto stack, so each
/// test injects the fault its check exists for, through something the caller controls:
/// an entropy source and a mask that corrupt the seed between derivation and hand-off,
/// or a JCA provider, first in preference order for the length of one test, whose
/// verifier rejects every signature. That provider is JVM-wide state, hence [Isolated].
/// The library verifier is reached through neither, so its check has no test here.
@Isolated
final class FoundKeySelfCheckTests {

  /// Draws each seed from a fixed-seed source and keeps the array it filled, which is the
  /// worker's own seed buffer.
  private static final class RetainingSecureRandom extends SecureRandom {

    private final FixedSeedSecureRandom source;
    private byte[] lastFilled;

    RetainingSecureRandom(final long seed, final long maxDraws) {
      this.source = new FixedSeedSecureRandom(seed, maxDraws);
    }

    @Override
    public void nextBytes(final byte[] bytes) {
      source.nextBytes(bytes);
      lastFilled = bytes;
    }
  }

  /// A prefix mask that matches every key after flipping the low bit of the first seed byte
  /// in the buffer the entropy source last filled. The worker asks the mask after deriving
  /// the public half and before copying the pair out, so the pair it copies disagrees.
  private static final class CorruptingMask implements Subsequence {

    private final RetainingSecureRandom entropy;
    private byte[] seedBefore;
    private byte[] seedAfter;

    CorruptingMask(final RetainingSecureRandom entropy) {
      this.entropy = entropy;
    }

    @Override
    public boolean contains(final char[] encoded, final int from) {
      final byte[] seed = entropy.lastFilled;
      seedBefore = seed.clone();
      seed[0] ^= 1;
      seedAfter = seed.clone();
      return true;
    }

    // the worker only asks a prefix mask whether it matches

    @Override
    public String subsequence() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int length() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean caseSensitive() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean _1337Numbers() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean _1337Letters() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int numCombinations() {
      throw new UnsupportedOperationException();
    }
  }

  /// A JCA provider whose Ed25519 verifier rejects every signature and counts the
  /// verifications it was asked for. Installed first in preference order, it is what
  /// `Signature.getInstance("Ed25519")` resolves to; a lookup that names its provider, as
  /// the signer's SunEC lookup does, never reaches it.
  private static final class RejectingEd25519Provider extends Provider {

    private final AtomicInteger verifications = new AtomicInteger();

    RejectingEd25519Provider() {
      super("SavaTestRejectingEd25519", "1", "an Ed25519 verifier that rejects every signature");
      putService(new Service(this, "Signature", "Ed25519", RejectingVerifier.class.getName(), null, null) {
        @Override
        public Object newInstance(final Object constructorParameter) {
          return new RejectingVerifier(verifications);
        }
      });
    }

    /// Fails unless the lookup the worker's JDK check makes reaches this provider;
    /// otherwise the tests below would assert nothing about that check.
    void assertPreferredForEd25519(final PublicKey key) throws GeneralSecurityException {
      final var probe = Signature.getInstance("Ed25519");
      probe.initVerify(key);
      assertSame(this, probe.getProvider(), "Ed25519 no longer resolves by provider preference order");
    }
  }

  private static final class RejectingVerifier extends SignatureSpi {

    private final AtomicInteger verifications;

    RejectingVerifier(final AtomicInteger verifications) {
      this.verifications = verifications;
    }

    @Override
    protected void engineInitVerify(final PublicKey publicKey) {
    }

    @Override
    protected void engineInitSign(final PrivateKey privateKey) throws InvalidKeyException {
      throw new InvalidKeyException("verification only");
    }

    @Override
    protected void engineUpdate(final byte b) {
    }

    @Override
    protected void engineUpdate(final byte[] b, final int off, final int len) {
    }

    @Override
    protected byte[] engineSign() throws SignatureException {
      throw new SignatureException("verification only");
    }

    @Override
    protected boolean engineVerify(final byte[] signature) {
      verifications.incrementAndGet();
      return false;
    }

    @Override
    @Deprecated
    protected void engineSetParameter(final String param, final Object value) {
      throw new InvalidParameterException(param);
    }

    @Override
    @Deprecated
    protected Object engineGetParameter(final String param) {
      throw new InvalidParameterException(param);
    }
  }

  /// The key pair a worker on `FixedSeedSecureRandom(seed)` generates first, re-derived
  /// from the replayed seed: the seed, then the public key that
  /// [Signer#createFromPrivateKey] derives from it.
  private static byte[] firstKeyPair(final long seed) {
    final byte[] privateKey = new byte[32];
    new FixedSeedSecureRandom(seed).nextBytes(privateKey);
    final byte[] keyPair = Arrays.copyOf(privateKey, 64);
    final byte[] publicKey = Signer.createFromPrivateKey(privateKey).publicKey().toByteArray();
    System.arraycopy(publicKey, 0, keyPair, 32, 32);
    return keyPair;
  }

  private static PublicKey javaPublicKey(final byte[] keyPair) {
    return software.sava.core.accounts.PublicKey.readPubKey(keyPair, 32).toJavaPublicKey();
  }

  private static void assertNoFiles(final Path dir) throws IOException {
    try (final var files = Files.list(dir)) {
      assertEquals(List.of(), files.toList(), "a refused pair must not be written");
    }
  }

  /// With or without `sigVerify`, a pair whose private half no longer derives the public
  /// half it carries is refused, and the refusal names both public keys.
  @Test
  void aPairWhosePrivateHalfNoLongerDerivesItsPublicHalfIsRefused(@TempDir final Path tempDir)
      throws IOException {
    for (final boolean sigVerify : new boolean[]{false, true}) {
      final var label = "sigVerify=" + sigVerify;
      final var keyPath = Files.createDirectories(tempDir.resolve(label));
      final var entropy = new RetainingSecureRandom(FixedSeedSecureRandom.SEEDS[0], 4);
      final var mask = new CorruptingMask(entropy);
      final var found = new AtomicInteger(0);
      final var results = new ArrayBlockingQueue<Result>(1);
      final var worker = new BeginsWithMaskWorker(
          keyPath, null, entropy, PrivateKeyEncoding.base58KeyPair, KeyFileFormat.json, null, sigVerify,
          mask, 1, found, new AtomicLong(0), results, 16, 4
      );

      final var error = assertThrows(IllegalStateException.class, worker::run, label);

      final var carried = Signer.createFromPrivateKey(mask.seedBefore).publicKey().toBase58();
      final var derived = Signer.createFromPrivateKey(mask.seedAfter).publicKey().toBase58();
      assertNotEquals(carried, derived, label);
      assertTrue(error.getMessage().contains(carried) && error.getMessage().contains(derived),
          label + ": " + error.getMessage());
      assertTrue(results.isEmpty(), label);
      assertEquals(0, found.get(), label);
      assertNoFiles(keyPath);
    }
  }

  /// With `sigVerify`, a pair the JDK verifier rejects is refused: the worker asked it
  /// once, names the pair in the refusal, and queues, counts and writes nothing.
  @Test
  void sigVerifyRefusesAPairTheJdkVerifierRejects(@TempDir final Path keyPath)
      throws IOException, GeneralSecurityException {
    final long seed = FixedSeedSecureRandom.SEEDS[0];
    final byte[] keyPair = firstKeyPair(seed);
    final var found = new AtomicInteger(0);
    final var results = new ArrayBlockingQueue<Result>(1);
    final var worker = new BeginsWithMaskWorker(
        keyPath, null, new FixedSeedSecureRandom(seed, 4), PrivateKeyEncoding.base58KeyPair, KeyFileFormat.json,
        null, true, null, 1, found, new AtomicLong(0), results, 16, 4
    );

    final var provider = new RejectingEd25519Provider();
    assertEquals(1, Security.insertProviderAt(provider, 1), "a provider of that name is already installed");
    final IllegalStateException error;
    try {
      provider.assertPreferredForEd25519(javaPublicKey(keyPair));
      error = assertThrows(IllegalStateException.class, worker::run);
    } finally {
      Security.removeProvider(provider.getName());
    }

    assertEquals(
        "Failed to verify signature using a Java PublicKey for key pair "
            + Base64.getEncoder().encodeToString(keyPair),
        error.getMessage()
    );
    assertEquals(1, provider.verifications.get());
    assertTrue(results.isEmpty());
    assertEquals(0, found.get());
    assertNoFiles(keyPath);
  }

  /// Without `sigVerify` the JDK verifier is never asked: with the same rejecting
  /// provider in place, the pair is handed out and written.
  @Test
  void withoutSigVerifyTheJdkVerifierIsNotConsulted(@TempDir final Path keyPath)
      throws GeneralSecurityException {
    final long seed = FixedSeedSecureRandom.SEEDS[0];
    final byte[] keyPair = firstKeyPair(seed);
    final var results = new ArrayBlockingQueue<Result>(1);
    final var worker = new BeginsWithMaskWorker(
        keyPath, null, new FixedSeedSecureRandom(seed, 4), PrivateKeyEncoding.base58KeyPair, KeyFileFormat.json,
        null, false, null, 1, new AtomicInteger(0), new AtomicLong(0), results, 16, 4
    );

    final var provider = new RejectingEd25519Provider();
    assertEquals(1, Security.insertProviderAt(provider, 1), "a provider of that name is already installed");
    try {
      provider.assertPreferredForEd25519(javaPublicKey(keyPair));
      worker.run();
    } finally {
      Security.removeProvider(provider.getName());
    }

    assertEquals(0, provider.verifications.get());
    final var result = results.poll();
    assertNotNull(result);
    assertArrayEquals(keyPair, result.keyPair());
    assertTrue(Files.isRegularFile(keyPath.resolve(result.publicKey().toBase58() + ".json")));
  }
}
