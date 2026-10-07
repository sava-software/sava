package software.sava.core.accounts;

import org.junit.jupiter.api.Test;
import software.sava.core.borsh.Borsh;
import software.sava.core.crypto.ed25519.Ed25519Util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.SolanaAccounts.MAIN_NET;

final class PublicKeyTest {

  @Test
  public void invalidKeys() {
    assertThrows(IllegalArgumentException.class, () -> PublicKey.createPubKey(new byte[]{3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
    assertThrows(IllegalArgumentException.class, () -> PublicKey.fromBase58Encoded("300000000000000000000000000000000000000000000000000000000000000000000"));
    assertThrows(IllegalArgumentException.class, () -> PublicKey.fromBase58Encoded("300000000000000000000000000000000000000000000000000000000000000"));
  }

  @Test
  public void validKeys() {
    final var key = PublicKey.createPubKey(new byte[]{3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,});
    assertEquals("CiDwVBFgWV9E5MvXWoLgnEgn2hK7rJikbvfWavzAQz3", key.toString());

    final var key1 = PublicKey.fromBase58Encoded("CiDwVBFgWV9E5MvXWoLgnEgn2hK7rJikbvfWavzAQz3");
    assertEquals("CiDwVBFgWV9E5MvXWoLgnEgn2hK7rJikbvfWavzAQz3", key1.toBase58());

    final var key2 = PublicKey.fromBase58Encoded("11111111111111111111111111111111");
    assertEquals("11111111111111111111111111111111", key2.toBase58());

    final byte[] byteKey = new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,};
    final var key3 = PublicKey.createPubKey(byteKey);
    assertArrayEquals(byteKey, PublicKey.fromBase58Encoded(key3.toBase58()).toByteArray());
  }

  /// The factory retains caller storage; callers that may mutate it must pass a copy.
  /// Deliberately violating that ownership rule exposes stale Base58 and hash-code caches.
  /// This pins the consequence of mutating borrowed bytes, not a pending factory change.
  @Test
  void mutatingBorrowedPublicKeyBytesLeavesCachedViewsStale() {
    final byte[] backing = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    final var key = PublicKey.createPubKey(backing);
    final String cachedBase58 = key.toBase58();
    final int cachedHash = key.hashCode();

    assertSame(backing, key.toByteArray(), "the current implementation exposes its backing array");
    backing[backing.length - 1] = 1;
    final var equalCurrentKey = PublicKey.createPubKey(backing.clone());

    assertEquals(equalCurrentKey, key, "equals observes the mutated bytes");
    assertNotEquals(equalCurrentKey.hashCode(), key.hashCode(),
        "the cached hash remains from before the mutation");
    assertEquals(cachedHash, key.hashCode());
    assertEquals(cachedBase58, key.toBase58(), "Base58 also remains cached");
    assertNotEquals(equalCurrentKey.toBase58(), key.toBase58(),
        "an equal key encodes the current bytes instead");
  }

  @Test
  public void readPubKeyRejectsTruncatedData() {
    final byte[] data = new byte[PublicKey.PUBLIC_KEY_LENGTH + 7];
    data[7 + PublicKey.PUBLIC_KEY_LENGTH - 1] = 1;

    final var key = PublicKey.readPubKey(data, 7);
    final byte[] expected = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    expected[PublicKey.PUBLIC_KEY_LENGTH - 1] = 1;
    assertArrayEquals(expected, key.toByteArray());

    assertThrows(IndexOutOfBoundsException.class, () -> PublicKey.readPubKey(data, 8));
    assertThrows(IndexOutOfBoundsException.class, () -> PublicKey.readPubKey(new byte[PublicKey.PUBLIC_KEY_LENGTH - 1], 0));
    assertThrows(IndexOutOfBoundsException.class, () -> PublicKey.readPubKey(new byte[0], 0));
  }

  @Test
  public void equals() {
    final var key = PublicKey.fromBase58Encoded("11111111111111111111111111111111");
    assertNotEquals(key, PublicKey.fromBase58Encoded("11111111111111111111111111111112"));
  }

  /// Solana orders addresses by their bytes as unsigned values: Rust's `Address` derives `Ord`
  /// over `[u8; 32]`. Wrapped SOL (first byte `0x06`) therefore sorts below USDC (first byte
  /// `0xc6`) on chain, which is what a program computing `min(mint_x, mint_y)` for a seed sees.
  /// A signed byte comparison reverses this pair and every other one that straddles `0x80`.
  @Test
  void naturalOrderPlacesWrappedSolBelowUsdcAsTheChainDoes() {
    final var wrappedSol = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
    final var usdc = PublicKey.fromBase58Encoded("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
    assertEquals(0x06, wrappedSol.toByteArray()[0] & 0xFF);
    assertEquals(0xc6, usdc.toByteArray()[0] & 0xFF);

    assertTrue(wrappedSol.compareTo(usdc) < 0);
    assertTrue(usdc.compareTo(wrappedSol) > 0);
    assertEquals(0, usdc.compareTo(PublicKey.fromBase58Encoded(usdc.toBase58())));
  }

  /// The chain as oracle. Orca's Whirlpool program rejects a pool unless
  /// `token_mint_a < token_mint_b` and seeds the pool address with the mints in that order, so
  /// the mainnet SOL/USDC pool with tick spacing 64 sits at the address derived from the lower
  /// mint first. Seeded the other way round, the derivation lands on an address the program
  /// refuses to initialize as a pool.
  @Test
  void naturalOrderSeedsTheWhirlpoolAddressThatExistsOnMainnet() {
    final var wrappedSol = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
    final var usdc = PublicKey.fromBase58Encoded("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
    final boolean usdcIsLower = usdc.compareTo(wrappedSol) < 0;

    final var whirlpool = PublicKey.findProgramAddress(
        List.of(
            "whirlpool".getBytes(US_ASCII),
            PublicKey.fromBase58Encoded("2LecshUwdy9xi7meFgHtFJQNSKk4KdTrcpvaB56dP2NQ").toByteArray(),
            (usdcIsLower ? usdc : wrappedSol).toByteArray(),
            (usdcIsLower ? wrappedSol : usdc).toByteArray(),
            new byte[]{64, 0} // tick spacing, u16 little-endian
        ),
        PublicKey.fromBase58Encoded("whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc")
    );
    assertEquals(PublicKey.fromBase58Encoded("HJPjoWUrhoZzkNfRpHuieeFk9WcZWjwy6PBjZ81ngndJ"), whirlpool.publicKey());
    assertEquals(255, whirlpool.nonce());
  }

  /// A key that shares the `0x80` prefix before `index`, holds `value` there and `tail` after.
  private static PublicKey keyWith(final int index, final int value, final int tail) {
    final byte[] key = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(key, 0, index, (byte) 0x80);
    key[index] = (byte) value;
    Arrays.fill(key, index + 1, key.length, (byte) tail);
    return PublicKey.createPubKey(key);
  }

  /// The first differing byte decides, read as `0..255`, wherever it sits: the lower key carries
  /// `0xff` in every later byte and the higher key `0x00`, so only that byte can order them.
  @Test
  void naturalOrderComparesTheFirstDifferingByteUnsigned() {
    final int[] ascending = {0x00, 0x01, 0x7f, 0x80, 0x81, 0xff};
    for (int index = 0; index < PublicKey.PUBLIC_KEY_LENGTH; ++index) {
      for (int lo = 0; lo < ascending.length; ++lo) {
        final var lower = keyWith(index, ascending[lo], 0xff);
        assertEquals(0, lower.compareTo(keyWith(index, ascending[lo], 0xff)));
        for (int hi = lo + 1; hi < ascending.length; ++hi) {
          final var higher = keyWith(index, ascending[hi], 0x00);
          final var pair = String.format("byte %d: 0x%02x vs 0x%02x", index, ascending[lo], ascending[hi]);
          assertTrue(lower.compareTo(higher) < 0, pair);
          assertTrue(higher.compareTo(lower) > 0, pair);
        }
      }
    }
  }

  /// Differential check against an oracle that shares nothing with the implementation: read as
  /// big-endian unsigned 256-bit integers, keys order exactly as their bytes do. The shared
  /// prefix moves the deciding byte across the whole key; at full length the keys are equal.
  @Test
  void naturalOrderMatchesBigEndianUnsignedIntegers() {
    final var random = new Random(0x5361_7661_4b65_79L);
    for (int i = 0; i < 4_096; ++i) {
      final byte[] a = new byte[PublicKey.PUBLIC_KEY_LENGTH];
      final byte[] b = new byte[PublicKey.PUBLIC_KEY_LENGTH];
      random.nextBytes(a);
      random.nextBytes(b);
      System.arraycopy(a, 0, b, 0, random.nextInt(PublicKey.PUBLIC_KEY_LENGTH + 1));
      final int expected = new BigInteger(1, a).compareTo(new BigInteger(1, b));
      final var keyA = PublicKey.createPubKey(a);
      final var keyB = PublicKey.createPubKey(b);
      assertEquals(expected, Integer.signum(keyA.compareTo(keyB)), () -> keyA + " vs " + keyB);
      assertEquals(-expected, Integer.signum(keyB.compareTo(keyA)), () -> keyB + " vs " + keyA);
    }
  }

  @Test
  public void readPubKey() {
    final var key = PublicKey.fromBase58Encoded("11111111111111111111111111111111");

    final byte[] bytes = new byte[33];
    bytes[0] = 1;
    key.write(bytes, 1);
    assertEquals(key.toString(), PublicKey.readPubKey(bytes, 1).toString());
  }

  @Test
  public void createProgramAddress() {
    final var programId = PublicKey.fromBase58Encoded("BPFLoader1111111111111111111111111111111111");

    var programAddress = PublicKey.createProgramAddress(
        List.of(PublicKey.fromBase58Encoded("SeedPubey1111111111111111111111111111111111").toByteArray()), programId);
    assertEquals(programAddress, PublicKey.fromBase58Encoded("GUs5qLUfsEHkcMB9T38vjr18ypEhRuNWiePW2LoK4E3K"));

    programAddress = PublicKey.createProgramAddress(Arrays.asList("".getBytes(), new byte[]{1}), programId);
    assertEquals(programAddress, PublicKey.fromBase58Encoded("3gF2KMe9KiC6FNVBmfg9i267aMPvK37FewCip4eGBFcT"));

    programAddress = PublicKey.createProgramAddress(Arrays.asList("Talking".getBytes(), "Squirrels".getBytes()),
        programId
    );
    assertEquals(programAddress, PublicKey.fromBase58Encoded("HwRVBufQ4haG5XSgpspwKtNd3PC9GM9m1196uJW36vds"));

    final var programAddress2 = PublicKey.createProgramAddress(List.of("Talking".getBytes()), programId);
    assertNotEquals(programAddress, programAddress2);
  }

  @Test
  public void findProgramAddress() {
    final var programId = PublicKey.fromBase58Encoded("BPFLoader1111111111111111111111111111111111");

    final var programAddress = PublicKey.findProgramAddress(List.of("".getBytes()), programId);
    assertEquals(programAddress.publicKey(), PublicKey.createProgramAddress(
        Arrays.asList("".getBytes(), new byte[]{(byte) programAddress.nonce()}), programId)
    );
  }

  @Test
  public void findProgramAddress1() {
    final var programId = PublicKey.fromBase58Encoded("6Cust2JhvweKLh4CVo1dt21s2PJ86uNGkziudpkNPaCj");
    final var programId2 = PublicKey.fromBase58Encoded("BPFLoader1111111111111111111111111111111111");

    final var programAddress = PublicKey.findProgramAddress(List.of(
        PublicKey.fromBase58Encoded("8VBafTNv1F8k5Bg7DTVwhitw3MGAMTmekHsgLuMJxLC8").toByteArray()), programId
    );
    assertEquals(programAddress.publicKey(), PublicKey.fromBase58Encoded("FGnnqkzkXUGKD7wtgJCqTemU3WZ6yYqkYJ8xoQoXVvUG"));

    final var programAddress2 = PublicKey.findProgramAddress(
        Arrays.asList(PublicKey.fromBase58Encoded("SeedPubey1111111111111111111111111111111111").toByteArray(),
            PublicKey.fromBase58Encoded("3gF2KMe9KiC6FNVBmfg9i267aMPvK37FewCip4eGBFcT").toByteArray(),
            PublicKey.fromBase58Encoded("HwRVBufQ4haG5XSgpspwKtNd3PC9GM9m1196uJW36vds").toByteArray()
        ),
        programId2
    );
    assertEquals(programAddress2.publicKey(), PublicKey.fromBase58Encoded("GXLbx3CbJuTTtJDZeS1PGzwJJ5jGYVEqcXum7472kpUp"));
    assertEquals(254, programAddress2.nonce());
  }

  @Test
  void programAddressSeedLimitIncludesTheBump() {
    final var programId = PublicKey.fromBase58Encoded("BPFLoader1111111111111111111111111111111111");
    final var maximumFindSeeds = Collections.nCopies(PublicKey.MAX_SEEDS - 1, new byte[0]);

    final var programAddress = PublicKey.findProgramAddress(maximumFindSeeds, programId);
    final var seedsWithBump = new ArrayList<>(maximumFindSeeds);
    seedsWithBump.add(new byte[]{(byte) programAddress.nonce()});
    assertEquals(programAddress.publicKey(), PublicKey.createProgramAddress(seedsWithBump, programId));

    final var maximumCreateSeeds = Collections.nCopies(PublicKey.MAX_SEEDS, new byte[0]);
    assertDoesNotThrow(() -> PublicKey.createProgramAddress(maximumCreateSeeds, programId));

    final var findException = assertThrows(
        IllegalArgumentException.class,
        () -> PublicKey.findProgramAddress(maximumCreateSeeds, programId)
    );
    assertEquals("Maximum number of seeds [16] exceeded. Given [17].", findException.getMessage());

    final var tooManyCreateSeeds = Collections.nCopies(PublicKey.MAX_SEEDS + 1, new byte[0]);
    final var createException = assertThrows(
        IllegalArgumentException.class,
        () -> PublicKey.createProgramAddress(tooManyCreateSeeds, programId)
    );
    assertEquals("Maximum number of seeds [16] exceeded. Given [17].", createException.getMessage());
  }

  @Test
  void canonicalProgramAddressSearchIncludesBumpOne() {
    final var classifications = new AtomicInteger();

    final var programAddress = PublicKeyBytes.findProgramAddress(
        List.of(),
        PublicKey.NONE,
        ignored -> classifications.incrementAndGet() == 255
    );

    assertEquals(255, classifications.get());
    assertEquals(1, programAddress.nonce());
  }

  @Test
  void canonicalProgramAddressSearchExcludesBumpZero() {
    final var classifications = new AtomicInteger();

    final var exception = assertThrows(RuntimeException.class, () -> PublicKeyBytes.findProgramAddress(
        List.of(),
        PublicKey.NONE,
        ignored -> {
          assertTrue(classifications.incrementAndGet() <= 255, "bump zero must not be classified");
          return false;
        }
    ));

    assertEquals(255, classifications.get());
    assertEquals("Unable to find a viable program derived address nonce", exception.getMessage());
  }

  @Test
  public void createWithSeed() {
    final var derived = PublicKey.createWithSeed(
        MAIN_NET.systemProgram(),
        "limber chicken: 4/45",
        MAIN_NET.systemProgram()
    );
    assertEquals("9h1HyLCW5dZnBVap8C5egQ9Z6pHyjsh5MNy83iPqqRuq", derived.toBase58());


    assertThrows(IllegalArgumentException.class, () -> PublicKey.createWithSeed(
            MAIN_NET.systemProgram(),
            "1".repeat(33),
            MAIN_NET.systemProgram()
        )
    );
  }

  /// Vectors and boundaries generated with solana-address 2.7.0 at commit
  /// 7e8f4a52f044e7729406bd24ae7c586de92e7f58 (`Address::create_with_seed`).
  @Test
  void createWithSeedUsesUtf8BytesAndTheirLength() {
    assertEquals(
        "EFAeyNPdBbGap5t7Y8MP3gZkiaLe8Mk8SqZ6GTUkjLq2",
        PublicKey.createWithSeed(PublicKey.NONE, "☉", PublicKey.NONE).toBase58()
    );
    assertEquals(
        "ASACxL8kA3p7wF3GGjAipDD5XpjW4Rjsh3Qm1o9zFjAJ",
        PublicKey.createWithSeed(PublicKey.NONE, "é", PublicKey.NONE).toBase58()
    );

    final String eightMaximumCodePoints = "\uDBFF\uDFFF".repeat(8);
    assertEquals(
        "HH9C1nu8NqC8Z7SoqGg6vzmoNCzCSSxNMVBc65yz2kub",
        PublicKey.createWithSeed(PublicKey.NONE, eightMaximumCodePoints, PublicKey.NONE).toBase58()
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> PublicKey.createWithSeed(PublicKey.NONE, "x" + eightMaximumCodePoints, PublicKey.NONE)
    );
  }

  /// Rust's `Address::create_with_seed` accepts an `&str`, so every accepted seed is valid
  /// UTF-8. Java strings can additionally contain unpaired UTF-16 surrogates; those must be
  /// rejected instead of being replaced with `?` and hashed as a different seed.
  @Test
  void createWithSeedRejectsStringsRustCannotRepresent() {
    final List<String> malformedSeeds = List.of(
        "GOLD 🥇".substring(0, 6),
        "\uDFFF",
        "\uDC00\uDC01",
        "\uD800x"
    );

    for (final String seed : malformedSeeds) {
      final var failure = assertThrowsExactly(
          IllegalArgumentException.class,
          () -> PublicKey.createWithSeed(PublicKey.NONE, seed, PublicKey.NONE)
      );
      assertEquals("Seed contains an unpaired UTF-16 surrogate.", failure.getMessage());
    }
  }

  /// Seed derivation and Borsh both encode Java strings as Rust-compatible UTF-8. Exercise
  /// the same boundary corpus through both validators so their acceptance cannot drift.
  @Test
  void seededAccountAndBorshUtf8ValidationAgree() {
    final List<String> strings = List.of(
        "",
        "ASCII",
        "\uD7FF",
        "\uE000",
        "\uD800\uDC00",
        "\uDBFF\uDFFF",
        "GOLD 🥇".substring(0, 6),
        "\uDFFF",
        "\uDC00\uDC01",
        "\uD800x"
    );

    for (final String string : strings) {
      boolean seedAccepted = true;
      try {
        PublicKeyBytes.encodeUtf8Seed(string);
      } catch (final IllegalArgumentException ignored) {
        seedAccepted = false;
      }

      boolean borshAccepted = true;
      try {
        Borsh.len(string);
      } catch (final IllegalArgumentException ignored) {
        borshAccepted = false;
      }

      assertEquals(seedAccepted, borshAccepted);
    }
  }

  /// solana-address rejects owners ending in the program-derived-address domain marker.
  @Test
  void createWithSeedRejectsIllegalOwnerMarker() {
    final byte[] marker = "ProgramDerivedAddress".getBytes(US_ASCII);
    final byte[] ownerBytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    System.arraycopy(marker, 0, ownerBytes, ownerBytes.length - marker.length, marker.length);
    final var illegalOwner = PublicKey.createPubKey(ownerBytes);

    assertThrows(
        IllegalArgumentException.class,
        () -> PublicKey.createWithSeed(PublicKey.NONE, "seed", illegalOwner)
    );

    final byte[] legalOwnerBytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    System.arraycopy(marker, 1, legalOwnerBytes, legalOwnerBytes.length - marker.length + 1, marker.length - 1);
    assertDoesNotThrow(() -> PublicKey.createWithSeed(
        PublicKey.NONE,
        "seed",
        PublicKey.createPubKey(legalOwnerBytes)
    ));
  }

  @Test
  void accountWithSeedStringFactoryUsesUtf8() {
    // Exercise the scalar values immediately around the surrogate range as well as
    // valid pairs at both supplementary-plane endpoints. Rust `str` accepts all of
    // these values, so the Java boundary must preserve their exact UTF-8 bytes.
    final var seed = new String(new int[]{0x2609, 0xD7FF, 0x10000, 0x10FFFF, 0xE000}, 0, 5);
    final var account = AccountWithSeed.createAccount(
        PublicKey.NONE,
        PublicKey.NONE,
        seed,
        MAIN_NET.systemProgram()
    );

    assertArrayEquals(seed.getBytes(UTF_8), account.asciiSeed());
  }

  @Test
  void accountWithSeedStringFactoryRejectsStringsRustCannotRepresent() {
    final List<String> malformedSeeds = List.of(
        "GOLD 🥇".substring(0, 6),
        "\uDFFF",
        "\uDC00\uDC01",
        "\uD800x"
    );

    for (final String seed : malformedSeeds) {
      final var failure = assertThrowsExactly(
          IllegalArgumentException.class,
          () -> AccountWithSeed.createAccount(
              PublicKey.NONE,
              PublicKey.NONE,
              seed,
              MAIN_NET.systemProgram()
          )
      );
      assertEquals("Seed contains an unpaired UTF-16 surrogate.", failure.getMessage());
    }
  }

  @Test
  void createOffCurveAccountWithAsciiSeedDerivesTheReturnedSeedMetadata() throws Exception {
    final var account = PublicKey.createOffCurveAccountWithAsciiSeed(
        PublicKey.NONE,
        "sava",
        MAIN_NET.systemProgram()
    );

    assertEquals(PublicKey.NONE, account.baseKey());
    assertEquals(MAIN_NET.systemProgram(), account.program());
    assertEquals("3PbbfN46p4JGPHAaqb9yvEhKZ7wTNHusxcGRPhVgkYZp", account.publicKey().toBase58());
    assertArrayEquals(new byte[]{'s', 'a', 'v', 'a', 125}, account.asciiSeed());

    final var digest = MessageDigest.getInstance("SHA-256");
    digest.update(account.baseKey().toByteArray());
    digest.update(account.asciiSeed());
    digest.update(account.program().toByteArray());
    assertArrayEquals(digest.digest(), account.publicKey().toByteArray());
    assertTrue(Ed25519Util.isNotOnCurve(account.publicKey().toByteArray()));
  }

  @Test
  void createOffCurveAccountWithAsciiSeedEnforcesTheEncodedSeedLimit() {
    final var maximumSeed = assertDoesNotThrow(() -> PublicKey.createOffCurveAccountWithAsciiSeed(
        PublicKey.NONE,
        "a".repeat(PublicKey.MAX_SEED_LENGTH - 1),
        MAIN_NET.systemProgram()
    ));
    assertEquals(PublicKey.MAX_SEED_LENGTH, maximumSeed.asciiSeed().length);

    final var exception = assertThrows(
        IllegalArgumentException.class,
        () -> PublicKey.createOffCurveAccountWithAsciiSeed(
            PublicKey.NONE,
            "a".repeat(PublicKey.MAX_SEED_LENGTH),
            MAIN_NET.systemProgram()
        )
    );
    assertEquals(
        "Seed [aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa] plus nonce exceeds maximum length of [32].",
        exception.getMessage()
    );

    final var nonAscii = PublicKey.createOffCurveAccountWithAsciiSeed(
        PublicKey.NONE,
        "é",
        MAIN_NET.systemProgram()
    );
    final var replacement = PublicKey.createOffCurveAccountWithAsciiSeed(
        PublicKey.NONE,
        "?",
        MAIN_NET.systemProgram()
    );
    assertEquals(replacement.publicKey(), nonAscii.publicKey());
    assertArrayEquals(replacement.asciiSeed(), nonAscii.asciiSeed());
  }

  @Test
  void createOffCurveAccountWithAsciiSeedRejectsIllegalOwnerMarker() {
    final byte[] marker = "ProgramDerivedAddress".getBytes(US_ASCII);
    final byte[] ownerBytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    System.arraycopy(marker, 0, ownerBytes, ownerBytes.length - marker.length, marker.length);

    final var exception = assertThrows(
        IllegalArgumentException.class,
        () -> PublicKey.createOffCurveAccountWithAsciiSeed(
            PublicKey.NONE,
            "seed",
            PublicKey.createPubKey(ownerBytes)
        )
    );
    assertEquals("Owner cannot end with the program derived address marker.", exception.getMessage());

    final byte[] legalOwnerBytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    System.arraycopy(marker, 1, legalOwnerBytes, legalOwnerBytes.length - marker.length + 1, marker.length - 1);
    assertDoesNotThrow(() -> PublicKey.createOffCurveAccountWithAsciiSeed(
        PublicKey.NONE,
        "seed",
        PublicKey.createPubKey(legalOwnerBytes)
    ));
  }

  @Test
  void createOffCurveAccountWithAsciiSeedTriesEveryNonceFrom127ThroughZero() {
    final var firstClassification = new AtomicInteger();
    final var first = PublicKeyBytes.createOffCurveAccountWithAsciiSeed(
        PublicKey.NONE,
        new byte[0],
        MAIN_NET.systemProgram(),
        ignored -> firstClassification.incrementAndGet() == 1
    );
    assertEquals(1, firstClassification.get());
    assertArrayEquals(new byte[]{127}, first.asciiSeed());

    final var lastClassification = new AtomicInteger();
    final var last = PublicKeyBytes.createOffCurveAccountWithAsciiSeed(
        PublicKey.NONE,
        new byte[0],
        MAIN_NET.systemProgram(),
        ignored -> lastClassification.incrementAndGet() == 128
    );
    assertEquals(128, lastClassification.get());
    assertArrayEquals(new byte[]{0}, last.asciiSeed());

    final var exhaustedClassifications = new AtomicInteger();
    final var exception = assertThrows(
        RuntimeException.class,
        () -> PublicKeyBytes.createOffCurveAccountWithAsciiSeed(
            PublicKey.NONE,
            new byte[0],
            MAIN_NET.systemProgram(),
            ignored -> {
              assertTrue(
                  exhaustedClassifications.incrementAndGet() <= 128,
                  "nonce search exceeded its finite 127-through-0 domain"
              );
              return false;
            }
        )
    );
    assertEquals(128, exhaustedClassifications.get());
    assertEquals("Unable to find a viable program derived address nonce", exception.getMessage());
  }

  /// RFC 8032 section 7.1, TEST 2: an Ed25519 secret key, its public key, the one-byte message
  /// `0x72` and its signature. Ed25519 signing is deterministic, so the vector is an oracle for
  /// both the library's signing and its verification that owes nothing to this implementation.
  private static final String RFC8032_SECRET_KEY = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb";
  private static final String RFC8032_PUBLIC_KEY = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
  /// [#RFC8032_PUBLIC_KEY] in base58, computed outside this library.
  private static final String RFC8032_PUBLIC_KEY_BASE58 = "586Z7H2vpX9qNhN2T4e9Utugie3ogjbxzGaMtM3E6HR5";
  private static final String RFC8032_SIGNATURE = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
      + "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00";

  /// The ranged instance verifier checks the signature over exactly `msgLength` bytes from
  /// `msgOffset`: it accepts the RFC 8032 signature over the signed byte embedded in a larger
  /// buffer, and rejects the same signature over a neighbouring range and a copy of it with one
  /// bit flipped. The fixed secret key signs through the library first, so the signature being
  /// verified is the library's own, checked byte for byte against the vector.
  @Test
  void rangedVerificationAcceptsOnlyTheSignedRangeAndAnUntamperedSignature() {
    final var hex = HexFormat.of();
    final var publicKey = PublicKey.createPubKey(hex.parseHex(RFC8032_PUBLIC_KEY));
    final byte[] buffer = {(byte) 0xEE, 0x72, (byte) 0xEE};

    final var signer = Signer.createFromPrivateKey(hex.parseHex(RFC8032_SECRET_KEY));
    assertEquals(publicKey, signer.publicKey());
    final byte[] signature = signer.sign(buffer, 1, 1);
    assertArrayEquals(hex.parseHex(RFC8032_SIGNATURE), signature);

    assertTrue(publicKey.verifySignature(buffer, 1, 1, signature));
    assertFalse(publicKey.verifySignature(buffer, 0, 1, signature), "a range other than the signed byte");
    assertFalse(publicKey.verifySignature(buffer, 1, 2, signature), "the signed byte plus a trailing one");

    final byte[] tampered = signature.clone();
    tampered[0] ^= 1;
    assertFalse(publicKey.verifySignature(buffer, 1, 1, tampered), "a signature with one bit flipped");
  }

  /// The `char[]` overloads and the ASCII `byte[]` overload decode the same key as the base58
  /// text names, reading only the requested range: the key is wrapped in JSON-style quotes that
  /// are not base58 digits, so a decoder that read past the range would reject the input. The
  /// expected bytes are the RFC 8032 public key, not the all-zero key a skipped decode leaves.
  @Test
  void charArrayAndAsciiByteOverloadsDecodeTheRequestedRange() {
    final byte[] expected = HexFormat.of().parseHex(RFC8032_PUBLIC_KEY);
    final var base58 = RFC8032_PUBLIC_KEY_BASE58;
    final var quoted = '"' + base58 + '"';

    assertArrayEquals(expected, PublicKey.fromBase58Encoded(base58.toCharArray()).toByteArray());
    assertArrayEquals(expected, PublicKey.fromBase58Encoded(quoted.toCharArray(), 1, base58.length()).toByteArray());
    assertArrayEquals(expected, PublicKey.fromBase58Encoded(quoted.getBytes(US_ASCII), 1, base58.length()).toByteArray());
    assertEquals(base58, PublicKey.fromBase58Encoded(quoted.getBytes(US_ASCII), 1, base58.length()).toBase58());
  }
}
