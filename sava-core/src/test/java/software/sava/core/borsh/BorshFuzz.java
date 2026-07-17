package software.sava.core.borsh;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/// Jazzer entry point for the Borsh readers, which deserialize untrusted account data in
/// consumer projects' generated clients. Same malformed-input contract as the other
/// parsing surfaces: "garbage in -> RuntimeException out" — Jazzer hunts hangs, memory
/// exhaustion (a u32 length prefix must never size an allocation the data cannot back),
/// and any non-RuntimeException throwable. Whenever a read succeeds, re-serializing must
/// consume exactly the promised len* bytes and re-read equal.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :sava-core:fuzzBorsh [-PmaxFuzzTime=<seconds>]`.
public final class BorshFuzz {

  record TestStruct(long a, int b) implements Borsh {

    static final int BYTES = Long.BYTES + Integer.BYTES;

    static TestStruct read(final byte[] data, final int offset) {
      return new TestStruct(ByteUtil.getInt64LE(data, offset), ByteUtil.getInt32LE(data, offset + Long.BYTES));
    }

    @Override
    public int l() {
      return BYTES;
    }

    @Override
    public int write(final byte[] data, final int offset) {
      ByteUtil.putInt64LE(data, offset, a);
      ByteUtil.putInt32LE(data, offset + Long.BYTES, b);
      return BYTES;
    }
  }

  @FunctionalInterface
  private interface Family {

    void run(final byte[] data, final int fixedLength);
  }

  private static final Family[] FAMILIES = {
      (data, _) -> {
        final byte[] vec = Borsh.readbyteVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(vec)];
        assertConsumed(Borsh.writeVector(vec, out, 0), out.length);
        assertState(Arrays.equals(vec, Borsh.readbyteVector(out, 0)), "byte vector");
      },
      (data, _) -> {
        final boolean[] vec = Borsh.readbooleanVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(vec)];
        assertConsumed(Borsh.writeVector(vec, out, 0), out.length);
        // 1 -> true; any other byte reads false and re-serializes to 0, so compare the
        // parsed values, not the original bytes
        assertState(Arrays.equals(vec, Borsh.readbooleanVector(out, 0)), "boolean vector");
      },
      (data, _) -> {
        final short[] vec = Borsh.readshortVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(vec)];
        assertConsumed(Borsh.writeVector(vec, out, 0), out.length);
        assertState(Arrays.equals(vec, Borsh.readshortVector(out, 0)), "short vector");
      },
      (data, _) -> {
        final int[] vec = Borsh.readintVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(vec)];
        assertConsumed(Borsh.writeVector(vec, out, 0), out.length);
        assertState(Arrays.equals(vec, Borsh.readintVector(out, 0)), "int vector");
      },
      (data, _) -> {
        final long[] vec = Borsh.readlongVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(vec)];
        assertConsumed(Borsh.writeVector(vec, out, 0), out.length);
        assertState(Arrays.equals(vec, Borsh.readlongVector(out, 0)), "long vector");
      },
      (data, _) -> {
        // NaN payload bits survive the float round trip via the raw-bits comparison
        final float[] vec = Borsh.readfloatVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(vec)];
        assertConsumed(Borsh.writeVector(vec, out, 0), out.length);
        final float[] reread = Borsh.readfloatVector(out, 0);
        assertState(vec.length == reread.length, "float vector length");
        for (int i = 0; i < vec.length; ++i) {
          assertState(Float.floatToRawIntBits(vec[i]) == Float.floatToRawIntBits(reread[i]), "float bits");
        }
      },
      (data, _) -> {
        final double[] vec = Borsh.readdoubleVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(vec)];
        assertConsumed(Borsh.writeVector(vec, out, 0), out.length);
        final double[] reread = Borsh.readdoubleVector(out, 0);
        assertState(vec.length == reread.length, "double vector length");
        for (int i = 0; i < vec.length; ++i) {
          assertState(Double.doubleToRawLongBits(vec[i]) == Double.doubleToRawLongBits(reread[i]), "double bits");
        }
      },
      (data, _) -> {
        final BigInteger[] vec = Borsh.read128Vector(data, 0);
        final byte[] out = new byte[Borsh.len128Vector(vec)];
        assertConsumed(Borsh.write128Vector(vec, out, 0), out.length);
        assertState(Arrays.equals(vec, Borsh.read128Vector(out, 0)), "u128 vector");
      },
      (data, _) -> {
        final BigInteger[] vec = Borsh.read256Vector(data, 0);
        final byte[] out = new byte[Borsh.len256Vector(vec)];
        assertConsumed(Borsh.write256Vector(vec, out, 0), out.length);
        assertState(Arrays.equals(vec, Borsh.read256Vector(out, 0)), "u256 vector");
      },
      (data, _) -> {
        final PublicKey[] vec = Borsh.readPublicKeyVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(vec)];
        assertConsumed(Borsh.writeVector(vec, out, 0), out.length);
        assertState(Arrays.equals(vec, Borsh.readPublicKeyVector(out, 0)), "PublicKey vector");
      },
      (data, _) -> {
        // invalid UTF-8 collapses to replacement chars on read; the re-serialized form is
        // canonical, so the round trip is checked on the parsed values
        final String[] vec = Borsh.readStringVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(vec)];
        assertConsumed(Borsh.writeVector(vec, out, 0), out.length);
        assertState(Arrays.equals(vec, Borsh.readStringVector(out, 0)), "String vector");
      },
      (data, _) -> {
        final TestStruct[] vec = Borsh.readVector(TestStruct.class, TestStruct::read, data, 0);
        final byte[] out = new byte[Borsh.lenVector(vec)];
        assertConsumed(Borsh.writeVector(vec, out, 0), out.length);
        assertState(Arrays.equals(vec, Borsh.readVector(TestStruct.class, TestStruct::read, out, 0)), "Borsh vector");
      },
      (data, _) -> {
        final int[][] matrix = Borsh.readMultiDimensionintVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(matrix)];
        assertConsumed(Borsh.writeVector(matrix, out, 0), out.length);
        assertState(Arrays.deepEquals(matrix, Borsh.readMultiDimensionintVector(out, 0)), "int matrix vector");
      },
      (data, fixedLength) -> {
        final long[][] matrix = Borsh.readMultiDimensionlongVectorArray(fixedLength, data, 0);
        final byte[] out = new byte[Borsh.lenVectorArray(matrix)];
        assertConsumed(Borsh.writeVectorArray(matrix, out, 0), out.length);
        assertState(Arrays.deepEquals(matrix, Borsh.readMultiDimensionlongVectorArray(fixedLength, out, 0)), "long matrix vector array");
      },
      (data, _) -> {
        final String[][] matrix = Borsh.readMultiDimensionStringVector(data, 0);
        final byte[] out = new byte[Borsh.lenVector(matrix)];
        assertConsumed(Borsh.writeVector(matrix, out, 0), out.length);
        assertState(Arrays.deepEquals(matrix, Borsh.readMultiDimensionStringVector(out, 0)), "String matrix vector");
      },
      (data, fixedLength) -> {
        final TestStruct[][] matrix = Borsh.readMultiDimensionVectorArray(TestStruct.class, TestStruct::read, fixedLength, data, 0);
        final byte[] out = new byte[Borsh.lenVectorArray(matrix)];
        assertConsumed(Borsh.writeVectorArray(matrix, out, 0), out.length);
        assertState(
            Arrays.deepEquals(matrix, Borsh.readMultiDimensionVectorArray(TestStruct.class, TestStruct::read, fixedLength, out, 0)),
            "Borsh matrix vector array"
        );
      },
      (data, _) -> {
        final String str = Borsh.readString(data, 0);
        final byte[] out = new byte[Borsh.len(str)];
        assertConsumed(Borsh.write(str, out, 0), out.length);
        assertState(Objects.equals(str, Borsh.readString(out, 0)), "String");
      },
  };

  public static void fuzzerTestOneInput(final byte[] input) {
    if (input.length == 0) {
      return;
    }
    final int fixedLength = input[0] & 0x07;
    final byte[] data = Arrays.copyOfRange(input, 1, input.length);
    for (final var family : FAMILIES) {
      try {
        family.run(data, fixedLength);
      } catch (final RuntimeException tolerated) {
        // malformed input may fail to parse, that is in contract
      }
    }
  }

  private static void assertConsumed(final int written, final int promised) {
    if (written != promised) {
      throw new AssertionError("write consumed " + written + " bytes but len promised " + promised);
    }
  }

  private static void assertState(final boolean valid, final String what) {
    if (!valid) {
      throw new AssertionError(what + " did not survive a serialization round trip");
    }
  }

  private BorshFuzz() {
  }
}
