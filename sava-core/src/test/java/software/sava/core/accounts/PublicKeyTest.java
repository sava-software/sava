package software.sava.core.accounts;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

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
}
