package software.sava.core.accounts.pbkdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Arrays;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Known answers for the key derivations, from outside this library. A round trip through the
/// same derivation cannot tell whether every parameter reached it; a published vector can.
final class Argon2idVectorTests {

  /// The reference implementation's Argon2id vector (phc-winner-argon2 `src/test.c`): version
  /// 0x13, two passes over 65536 KiB with one lane, the password `password` over the salt
  /// `somesalt`, 32 bytes out. It pins the version, the salt, the memory, the parallelism and
  /// the iteration count sava hands the generator, each of which a dropped builder call would
  /// change, and the UTF-8 encoding of the password.
  @Test
  @ResourceLock("argon2id")
  void argon2idMatchesTheReferenceVector() {
    final var kdf = KeyDerivation.createArgon2id(65_536, 1, 2);
    assertArrayEquals(
        HexFormat.of().parseHex("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7"),
        kdf.derive("password".toCharArray(), "somesalt".getBytes(US_ASCII), 256)
    );
  }

  /// Every Argon2id parameter sava passes is part of the hash: changing the memory, the
  /// parallelism or the iteration count on its own changes the key. The reference vector above
  /// runs with one lane, which is also the generator's default, so a dropped parallelism call
  /// is visible only here. The sizes are the cheapest the bounds admit.
  @Test
  @ResourceLock("argon2id")
  void everyArgon2idParameterChangesTheKey() {
    final char[] password = "password".toCharArray();
    final byte[] salt = "somesalt".getBytes(US_ASCII);
    final byte[] base = KeyDerivation.createArgon2id(Argon2id.MIN_MEMORY_KB, 1, 1).derive(password, salt, 256);
    assertFalse(Arrays.equals(base, KeyDerivation.createArgon2id(Argon2id.MIN_MEMORY_KB + 1024, 1, 1).derive(password, salt, 256)), "memory");
    assertFalse(Arrays.equals(base, KeyDerivation.createArgon2id(Argon2id.MIN_MEMORY_KB, 2, 1).derive(password, salt, 256)), "parallelism");
    assertFalse(Arrays.equals(base, KeyDerivation.createArgon2id(Argon2id.MIN_MEMORY_KB, 1, 2).derive(password, salt, 256)), "iterations");
    assertArrayEquals(base, KeyDerivation.createArgon2id(Argon2id.MIN_MEMORY_KB, 1, 1).derive(password, salt, 256), "deterministic");
  }

  /// The password reaches the derivation as its UTF-8 bytes, filled in from the encoder's
  /// buffer: a non-ASCII character takes two bytes, and nothing is left as a zero byte.
  @Test
  void passwordsAreEncodedAsUtf8() {
    assertArrayEquals("pässwörd".getBytes(UTF_8), PBKDFEncryption.toUtf8Bytes("pässwörd".toCharArray()));
    assertArrayEquals(new byte[0], PBKDFEncryption.toUtf8Bytes(new char[0]));
  }
}
