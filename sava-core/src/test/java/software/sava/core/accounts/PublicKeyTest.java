package software.sava.core.accounts;

import org.junit.jupiter.api.Test;
import software.sava.core.crypto.ed25519.Ed25519Util;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

  /// `createPubKey` and `toByteArray` expose one mutable backing array while Base58 and
  /// hashCode are cached. Java's independent contract requires equal objects to have equal
  /// hash codes, and a public key's Base58 text should encode its current bytes. Pin the
  /// present contradiction pending an owner decision; do not present it as intended value
  /// semantics.
  @Test
  void backingMutationLeavesCachedPublicKeyViewsStalePendingOwnerDecision() {
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
    final var account = AccountWithSeed.createAccount(
        PublicKey.NONE,
        PublicKey.NONE,
        "☉",
        MAIN_NET.systemProgram()
    );

    assertArrayEquals("☉".getBytes(UTF_8), account.asciiSeed());
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
}
