package software.sava.core.accounts.pbkdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.pbkdf.Argon2id.MAX_ITERATIONS;
import static software.sava.core.accounts.pbkdf.Argon2id.MAX_MEMORY_KB;
import static software.sava.core.accounts.pbkdf.Argon2id.MAX_PARALLELISM;
import static software.sava.core.accounts.pbkdf.Argon2id.MIN_ITERATIONS;
import static software.sava.core.accounts.pbkdf.Argon2id.MIN_MEMORY_KB;
import static software.sava.core.accounts.pbkdf.Argon2id.MIN_PARALLELISM;

/// Boundary tests for the KDF parameter guards in the two compact constructors.
///
/// These bounds are not style: both records are built from values read out of an
/// externally-supplied key file, so the lower bounds are what stop a silently-weak
/// derivation and the upper bounds are what stop a memory/CPU exhaustion on load.
/// A guard that is off by one, or that never fires, accepts exactly the parameters it
/// exists to reject — and says nothing while doing it.
///
/// Every case is a boundary rather than a comfortable middle value, because the middle
/// cannot separate `<` from `<=`. Each guard therefore gets four assertions: the extreme
/// that must be accepted at each end, and the first value past it that must be rejected.
/// Constructing either record is free — the records only carry the parameters, and no
/// derivation runs here — so this costs nothing despite the deliberately expensive KDFs.
final class KeyDerivationBoundsTests {

  @Test
  void argon2idMemoryBoundsAreInclusive() {
    assertEquals(MIN_MEMORY_KB, new Argon2id(MIN_MEMORY_KB, MIN_PARALLELISM, MIN_ITERATIONS).memoryKB());
    assertEquals(MAX_MEMORY_KB, new Argon2id(MAX_MEMORY_KB, MIN_PARALLELISM, MIN_ITERATIONS).memoryKB());

    assertThrows(
        IllegalArgumentException.class,
        () -> new Argon2id(MIN_MEMORY_KB - 1, MIN_PARALLELISM, MIN_ITERATIONS),
        "one KiB below the OWASP floor is a weaker KDF than the floor exists to guarantee"
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> new Argon2id(MAX_MEMORY_KB + 1, MIN_PARALLELISM, MIN_ITERATIONS),
        "one KiB above the ceiling is the memory-exhaustion case the ceiling exists to refuse"
    );
  }

  @Test
  void argon2idParallelismBoundsAreInclusive() {
    assertEquals(MIN_PARALLELISM, new Argon2id(MIN_MEMORY_KB, MIN_PARALLELISM, MIN_ITERATIONS).parallelism());
    assertEquals(MAX_PARALLELISM, new Argon2id(MIN_MEMORY_KB, MAX_PARALLELISM, MIN_ITERATIONS).parallelism());

    assertThrows(
        IllegalArgumentException.class,
        () -> new Argon2id(MIN_MEMORY_KB, MIN_PARALLELISM - 1, MIN_ITERATIONS)
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> new Argon2id(MIN_MEMORY_KB, MAX_PARALLELISM + 1, MIN_ITERATIONS)
    );
  }

  @Test
  void argon2idIterationBoundsAreInclusive() {
    assertEquals(MIN_ITERATIONS, new Argon2id(MIN_MEMORY_KB, MIN_PARALLELISM, MIN_ITERATIONS).iterations());
    assertEquals(MAX_ITERATIONS, new Argon2id(MIN_MEMORY_KB, MIN_PARALLELISM, MAX_ITERATIONS).iterations());

    assertThrows(
        IllegalArgumentException.class,
        () -> new Argon2id(MIN_MEMORY_KB, MIN_PARALLELISM, MIN_ITERATIONS - 1)
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> new Argon2id(MIN_MEMORY_KB, MIN_PARALLELISM, MAX_ITERATIONS + 1)
    );
  }

  @Test
  void argon2idNamesTheOffendingParameterAndItsRange() {
    final var tooSmall = assertThrows(
        IllegalArgumentException.class,
        () -> new Argon2id(MIN_MEMORY_KB - 1, MIN_PARALLELISM, MIN_ITERATIONS)
    );
    assertTrue(tooSmall.getMessage().contains("memoryKB"), tooSmall.getMessage());
    assertTrue(tooSmall.getMessage().contains(Integer.toString(MIN_MEMORY_KB - 1)), tooSmall.getMessage());

    final var badParallelism = assertThrows(
        IllegalArgumentException.class,
        () -> new Argon2id(MIN_MEMORY_KB, MAX_PARALLELISM + 1, MIN_ITERATIONS)
    );
    assertTrue(badParallelism.getMessage().contains("parallelism"), badParallelism.getMessage());

    final var badIterations = assertThrows(
        IllegalArgumentException.class,
        () -> new Argon2id(MIN_MEMORY_KB, MIN_PARALLELISM, MAX_ITERATIONS + 1)
    );
    assertTrue(badIterations.getMessage().contains("iterations"), badIterations.getMessage());
  }

  // Deliberately NOT static-imported: both records declare MIN_ITERATIONS/MAX_ITERATIONS
  // with wildly different values (Argon2id 1..100, PBKDF2 500_000..100_000_000), and a
  // single-static import silently wins over an on-demand one — the test would then assert
  // the wrong record's bounds and still pass.
  private static final int PBKDF2_MIN = PBKDF2WithHmacSHA512.MIN_ITERATIONS;
  private static final int PBKDF2_MAX = PBKDF2WithHmacSHA512.MAX_ITERATIONS;

  @Test
  void pbkdf2IterationBoundsAreInclusive() {
    assertEquals(PBKDF2_MIN, new PBKDF2WithHmacSHA512(PBKDF2_MIN).iterations());
    assertEquals(PBKDF2_MAX, new PBKDF2WithHmacSHA512(PBKDF2_MAX).iterations());

    final var tooFew = assertThrows(
        IllegalArgumentException.class,
        () -> new PBKDF2WithHmacSHA512(PBKDF2_MIN - 1),
        "one iteration below the floor is the silently-weak KDF the floor exists to refuse"
    );
    assertTrue(tooFew.getMessage().contains("at least"), tooFew.getMessage());

    final var tooMany = assertThrows(
        IllegalArgumentException.class,
        () -> new PBKDF2WithHmacSHA512(PBKDF2_MAX + 1),
        "one iteration above the ceiling is the CPU-exhaustion case the ceiling exists to refuse"
    );
    assertTrue(tooMany.getMessage().contains("at most"), tooMany.getMessage());
  }
}
