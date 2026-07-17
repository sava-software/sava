package software.sava.core.borsh;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/// Every RustEnum variant shape: l() must equal the write() return, the ordinal byte must
/// lead the payload, and the payload must match the corresponding Borsh wire format.
final class RustEnumTests {

  private static final int ORDINAL = 3;

  /// Serializes into a dirty buffer at offset 1 and asserts l() == write() == expected
  /// payload, with the ordinal byte in front.
  private static byte[] writeAndCheck(final RustEnum val, final byte[] expectedPayload) {
    final int l = val.l();
    assertEquals(1 + expectedPayload.length, l, val.getClass().getSimpleName());
    final byte[] data = new byte[1 + l + 1];
    Arrays.fill(data, (byte) 0xAA);
    assertEquals(l, val.write(data, 1), val.getClass().getSimpleName());
    assertEquals(ORDINAL, data[1], val.getClass().getSimpleName());
    assertArrayEquals(expectedPayload, Arrays.copyOfRange(data, 2, 1 + l), val.getClass().getSimpleName());
    assertEquals((byte) 0xAA, data[0]);
    assertEquals((byte) 0xAA, data[data.length - 1]);
    return data;
  }

  private static byte[] bytes(final int... values) {
    final byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; ++i) {
      out[i] = (byte) values[i];
    }
    return out;
  }

  record None(int ordinal) implements RustEnum.EnumNone {
  }

  @Test
  void enumNone() {
    final var none = new None(ORDINAL);
    assertEquals(1, none.l());
    final byte[] data = {(byte) 0xAA, (byte) 0xAA};
    // EnumNone.write intentionally writes nothing, generated union writers stamp the
    // ordinal themselves via writeOrdinal
    assertEquals(1, none.write(data, 1));
    assertEquals("None", none.name());
    assertEquals(2, none.writeOrdinal(data, 1));
    assertEquals(ORDINAL, data[1]);
  }

  record Bool(int ordinal, boolean val) implements RustEnum.EnumBool {
  }

  record F32(int ordinal, float val) implements RustEnum.EnumFloat32 {
  }

  record F64(int ordinal, double val) implements RustEnum.EnumFloat64 {
  }

  record I8(int ordinal, int val) implements RustEnum.EnumInt8 {
  }

  record I16(int ordinal, int val) implements RustEnum.EnumInt16 {
  }

  record I32(int ordinal, int val) implements RustEnum.EnumInt32 {
  }

  record I64(int ordinal, long val) implements RustEnum.EnumInt64 {
  }

  record I128(int ordinal, BigInteger val) implements RustEnum.EnumInt128 {
  }

  record I256(int ordinal, BigInteger val) implements RustEnum.EnumInt256 {
  }

  record Bytes(int ordinal, byte[] val) implements RustEnum.EnumBytes {
  }

  record Key(int ordinal, PublicKey val) implements RustEnum.EnumPublicKey {
  }

  record Nested(int ordinal, Borsh val) implements RustEnum.BorshEnum {
  }

  @Test
  void primitivePayloads() {
    writeAndCheck(new Bool(ORDINAL, true), bytes(1));
    writeAndCheck(new Bool(ORDINAL, false), bytes(0));
    writeAndCheck(new I8(ORDINAL, 0xFE), bytes(0xFE));

    final byte[] i16 = new byte[Short.BYTES];
    ByteUtil.putInt16LE(i16, 0, (short) -2);
    writeAndCheck(new I16(ORDINAL, -2), i16);

    final byte[] i32 = new byte[Integer.BYTES];
    ByteUtil.putInt32LE(i32, 0, Integer.MIN_VALUE);
    writeAndCheck(new I32(ORDINAL, Integer.MIN_VALUE), i32);

    final byte[] i64 = new byte[Long.BYTES];
    ByteUtil.putInt64LE(i64, 0, Long.MAX_VALUE);
    writeAndCheck(new I64(ORDINAL, Long.MAX_VALUE), i64);

    final byte[] f32 = new byte[Float.BYTES];
    ByteUtil.putFloat32LE(f32, 0, 2.25f);
    writeAndCheck(new F32(ORDINAL, 2.25f), f32);

    final byte[] f64 = new byte[Double.BYTES];
    ByteUtil.putFloat64LE(f64, 0, -3.75);
    writeAndCheck(new F64(ORDINAL, -3.75), f64);
  }

  @Test
  void bigIntegerPayloadsMatchTheirWrittenWidth() {
    // l() said 129 and 257 before 2026-07-17 — bits, not bytes; write() returns 17 and 33
    final byte[] i128 = new byte[16];
    ByteUtil.putInt128LE(i128, 0, BigInteger.TEN);
    writeAndCheck(new I128(ORDINAL, BigInteger.TEN), i128);

    final byte[] i256 = new byte[32];
    ByteUtil.putInt256LE(i256, 0, BigInteger.ONE.shiftLeft(200));
    writeAndCheck(new I256(ORDINAL, BigInteger.ONE.shiftLeft(200)), i256);
  }

  @Test
  void referencePayloads() {
    final byte[] vec = {(byte) 3, 0, 0, 0, 7, 8, 9};
    writeAndCheck(new Bytes(ORDINAL, bytes(7, 8, 9)), vec);

    final byte[] keyBytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(keyBytes, (byte) 5);
    writeAndCheck(new Key(ORDINAL, PublicKey.createPubKey(keyBytes)), keyBytes);

    final var struct = new BorshReferenceVectorTests.TestStruct(42L, -1);
    final byte[] structBytes = new byte[BorshReferenceVectorTests.TestStruct.BYTES];
    struct.write(structBytes, 0);
    writeAndCheck(new Nested(ORDINAL, struct), structBytes);
  }

  record VecOfBorsh(int ordinal, Borsh[] val) implements RustEnum.BorshVectorEnum {
  }

  record ArrOfBorsh(int ordinal, Borsh[] val) implements RustEnum.BorshArrayEnum {
  }

  record VecOfKeys(int ordinal, PublicKey[] val) implements RustEnum.PublicKeyVectorEnum {
  }

  record ArrOfKeys(int ordinal, PublicKey[] val) implements RustEnum.PublicKeyArrayEnum {
  }

  @Test
  void collectionPayloads() {
    final var structs = new Borsh[]{new BorshReferenceVectorTests.TestStruct(1L, 2)};
    final byte[] structBytes = new byte[BorshReferenceVectorTests.TestStruct.BYTES];
    structs[0].write(structBytes, 0);

    final byte[] structVector = new byte[Integer.BYTES + structBytes.length];
    ByteUtil.putInt32LE(structVector, 0, 1);
    System.arraycopy(structBytes, 0, structVector, Integer.BYTES, structBytes.length);
    writeAndCheck(new VecOfBorsh(ORDINAL, structs), structVector);
    writeAndCheck(new ArrOfBorsh(ORDINAL, structs), structBytes);

    final byte[] keyBytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(keyBytes, (byte) 6);
    final var keys = new PublicKey[]{PublicKey.createPubKey(keyBytes)};
    final byte[] keyVector = new byte[Integer.BYTES + keyBytes.length];
    ByteUtil.putInt32LE(keyVector, 0, 1);
    System.arraycopy(keyBytes, 0, keyVector, Integer.BYTES, keyBytes.length);
    writeAndCheck(new VecOfKeys(ORDINAL, keys), keyVector);
    writeAndCheck(new ArrOfKeys(ORDINAL, keys), keyBytes);
  }

  record OptBool(int ordinal, Boolean val) implements RustEnum.OptionalEnumBool {
  }

  record OptF32(int ordinal, OptionalDouble val) implements RustEnum.OptionalEnumFloat32 {
  }

  record OptF64(int ordinal, OptionalDouble val) implements RustEnum.OptionalEnumFloat64 {
  }

  record OptI8(int ordinal, OptionalInt val) implements RustEnum.OptionalEnumInt8 {
  }

  record OptI16(int ordinal, OptionalInt val) implements RustEnum.OptionalEnumInt16 {
  }

  record OptI32(int ordinal, OptionalInt val) implements RustEnum.OptionalEnumInt32 {
  }

  record OptI64(int ordinal, OptionalLong val) implements RustEnum.OptionalEnumInt64 {
  }

  record OptI128(int ordinal, BigInteger val) implements RustEnum.OptionalEnumInt128 {
  }

  record OptI256(int ordinal, BigInteger val) implements RustEnum.OptionalEnumInt256 {
  }

  record OptBytes(int ordinal, byte[] val) implements RustEnum.OptionalEnumBytes {
  }

  record OptKey(int ordinal, PublicKey val) implements RustEnum.OptionalEnumPublicKey {
  }

  record OptNested(int ordinal, Borsh val) implements RustEnum.OptionalBorshEnum {
  }

  @Test
  void optionalPayloadsAbsent() {
    writeAndCheck(new OptBool(ORDINAL, null), bytes(0));
    writeAndCheck(new OptF32(ORDINAL, OptionalDouble.empty()), bytes(0));
    writeAndCheck(new OptF64(ORDINAL, OptionalDouble.empty()), bytes(0));
    writeAndCheck(new OptI8(ORDINAL, OptionalInt.empty()), bytes(0));
    writeAndCheck(new OptI16(ORDINAL, OptionalInt.empty()), bytes(0));
    writeAndCheck(new OptI32(ORDINAL, OptionalInt.empty()), bytes(0));
    writeAndCheck(new OptI64(ORDINAL, OptionalLong.empty()), bytes(0));
    writeAndCheck(new OptI128(ORDINAL, null), bytes(0));
    writeAndCheck(new OptI256(ORDINAL, null), bytes(0));
    writeAndCheck(new OptBytes(ORDINAL, null), bytes(0));
    writeAndCheck(new OptBytes(ORDINAL, new byte[0]), bytes(0));
    writeAndCheck(new OptKey(ORDINAL, null), bytes(0));
    writeAndCheck(new OptNested(ORDINAL, null), bytes(0));
  }

  @Test
  void optionalPayloadsPresent() {
    writeAndCheck(new OptBool(ORDINAL, Boolean.TRUE), bytes(1, 1));
    writeAndCheck(new OptI8(ORDINAL, OptionalInt.of(7)), bytes(1, 7));

    final byte[] i16 = new byte[1 + Short.BYTES];
    i16[0] = 1;
    ByteUtil.putInt16LE(i16, 1, (short) 300);
    writeAndCheck(new OptI16(ORDINAL, OptionalInt.of(300)), i16);

    final byte[] i32 = new byte[1 + Integer.BYTES];
    i32[0] = 1;
    ByteUtil.putInt32LE(i32, 1, -5);
    writeAndCheck(new OptI32(ORDINAL, OptionalInt.of(-5)), i32);

    final byte[] i64 = new byte[1 + Long.BYTES];
    i64[0] = 1;
    ByteUtil.putInt64LE(i64, 1, 9L);
    writeAndCheck(new OptI64(ORDINAL, OptionalLong.of(9L)), i64);

    final byte[] f32 = new byte[1 + Float.BYTES];
    f32[0] = 1;
    ByteUtil.putFloat32LE(f32, 1, 1.5f);
    writeAndCheck(new OptF32(ORDINAL, OptionalDouble.of(1.5)), f32);

    final byte[] f64 = new byte[1 + Double.BYTES];
    f64[0] = 1;
    ByteUtil.putFloat64LE(f64, 1, 2.5);
    writeAndCheck(new OptF64(ORDINAL, OptionalDouble.of(2.5)), f64);

    final byte[] i128 = new byte[17];
    i128[0] = 1;
    ByteUtil.putInt128LE(i128, 1, BigInteger.TEN);
    writeAndCheck(new OptI128(ORDINAL, BigInteger.TEN), i128);

    final byte[] i256 = new byte[33];
    i256[0] = 1;
    ByteUtil.putInt256LE(i256, 1, BigInteger.TWO);
    writeAndCheck(new OptI256(ORDINAL, BigInteger.TWO), i256);

    writeAndCheck(new OptBytes(ORDINAL, bytes(7, 8)), bytes(1, 2, 0, 0, 0, 7, 8));

    final byte[] keyBytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(keyBytes, (byte) 9);
    final byte[] key = new byte[1 + PublicKey.PUBLIC_KEY_LENGTH];
    key[0] = 1;
    System.arraycopy(keyBytes, 0, key, 1, keyBytes.length);
    writeAndCheck(new OptKey(ORDINAL, PublicKey.createPubKey(keyBytes)), key);

    final var struct = new BorshReferenceVectorTests.TestStruct(4L, 5);
    final byte[] nested = new byte[1 + BorshReferenceVectorTests.TestStruct.BYTES];
    nested[0] = 1;
    struct.write(nested, 1);
    writeAndCheck(new OptNested(ORDINAL, struct), nested);
  }

  record Str(int ordinal, String _val, byte[] val) implements RustEnum.EnumString {

    static Str of(final int ordinal, final String val) {
      return new Str(ordinal, val, val.getBytes(UTF_8));
    }
  }

  record OptStr(int ordinal, String _val, byte[] val) implements RustEnum.OptionalEnumString {
  }

  @Test
  void stringVariantsSerializeTheirUtf8Bytes() {
    final var str = Str.of(ORDINAL, "abc");
    writeAndCheck(str, bytes(3, 0, 0, 0, 'a', 'b', 'c'));
    assertEquals("abc", str._val());

    writeAndCheck(new OptStr(ORDINAL, null, null), bytes(0));
    writeAndCheck(new OptStr(ORDINAL, "ab", "ab".getBytes(UTF_8)), bytes(1, 2, 0, 0, 0, 'a', 'b'));
  }
}
