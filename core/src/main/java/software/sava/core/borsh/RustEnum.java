package software.sava.core.borsh;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

public interface RustEnum extends Borsh {

  int ordinal();

  default String name() {
    return getClass().getSimpleName();
  }

  interface EnumNone extends RustEnum {

    default int l() {
      return 1;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1;
    }
  }

  interface EnumBool extends RustEnum {

    boolean val();

    default int l() {
      return 2;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      data[offset + 1] = (byte) (val() ? 1 : 0);
      return 2;
    }
  }

  interface EnumFloat32 extends RustEnum {

    float val();

    default int l() {
      return 1 + Float.BYTES;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      ByteUtil.putFloat32LE(data, 1 + offset, val());
      return l();
    }
  }

  interface EnumFloat64 extends RustEnum {

    double val();

    default int l() {
      return 1 + Double.BYTES;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      ByteUtil.putFloat64LE(data, 1 + offset, val());
      return l();
    }
  }

  interface EnumInt8 extends RustEnum {

    int val();

    default int l() {
      return 1 + Byte.BYTES;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      data[1 + offset] = (byte) val();
      return l();
    }
  }

  interface EnumInt16 extends RustEnum {

    int val();

    default int l() {
      return 1 + Short.BYTES;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      ByteUtil.putInt16LE(data, 1 + offset, (short) val());
      return l();
    }
  }

  interface EnumInt32 extends RustEnum {

    int val();

    default int l() {
      return 1 + Integer.BYTES;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      ByteUtil.putInt32LE(data, 1 + offset, val());
      return l();
    }
  }

  interface EnumInt64 extends RustEnum {

    long val();

    default int l() {
      return 1 + Long.BYTES;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      ByteUtil.putInt64LE(data, 1 + offset, val());
      return l();
    }
  }

  interface EnumInt128 extends RustEnum {

    BigInteger val();

    default int l() {
      return 129;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeInt128(val(), data, 1 + offset);
    }
  }

  interface EnumBytes extends RustEnum {

    byte[] val();

    default int l() {
      return 1 + Borsh.len(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 +  Borsh.write(val(), data, 1 + offset);
    }
  }

  interface EnumString extends EnumBytes {

    String _val();
  }

  interface EnumPublicKey extends RustEnum {

    PublicKey val();

    default int l() {
      return 1 + PublicKey.PUBLIC_KEY_LENGTH;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + val().write(data, 1 + offset);
    }
  }

  interface BorshEnum extends RustEnum {

    Borsh val();

    default int l() {
      return 1 + val().l();
    }

    default int write(final byte[] data, final int offset) {
      return 1 + val().write(data, offset);
    }
  }

  interface BorshVectorEnum extends RustEnum {

    Borsh[] val();

    default int l() {
      return 1 + Integer.BYTES + Arrays.stream(val()).mapToInt(Borsh::l).sum();
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.write(val(), data, 1 + offset);
    }
  }

  interface BorshArrayEnum extends RustEnum {

    Borsh[] val();

    default int l() {
      return 1 + Arrays.stream(val()).mapToInt(Borsh::l).sum();
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.write(val(), data, 1 + offset);
    }
  }

  interface OptionalEnumBool extends RustEnum {

    Boolean val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptional(val(), data, 1 + offset);
    }
  }

  interface OptionalEnumFloat32 extends RustEnum {

    OptionalDouble val();

    default int l() {
      return 1 + Borsh.lenOptionalFloat(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptionalFloat(val(), data, 1 + offset);
    }
  }

  interface OptionalEnumFloat64 extends RustEnum {

    OptionalDouble val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptional(val(), data, 1 + offset);
    }
  }

  interface OptionalEnumInt8 extends RustEnum {

    OptionalInt val();

    default int l() {
      return 1 + Borsh.lenOptionalByte(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptionalByte(val(), data, 1 + offset);
    }
  }

  interface OptionalEnumInt16 extends RustEnum {

    OptionalInt val();

    default int l() {
      return 1 + Borsh.lenOptionalShort(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptionalShort(val(), data, 1 + offset);
    }
  }

  interface OptionalEnumInt32 extends RustEnum {

    OptionalInt val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptional(val(), data, 1 + offset);
    }
  }

  interface OptionalEnumInt64 extends RustEnum {

    OptionalLong val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptional(val(), data, 1 + offset);
    }
  }

  interface OptionalEnumInt128 extends RustEnum {

    BigInteger val();

    default int l() {
      return 1 + Borsh.lenOptionalInt128(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptionalInt128(val(), data, 1 + offset);
    }
  }

  interface OptionalEnumBytes extends RustEnum {

    byte[] val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptional(val(), data, 1 + offset);
    }
  }

  interface OptionalEnumString extends OptionalEnumBytes {

    String _val();
  }

  interface OptionalEnumPublicKey extends RustEnum {

    PublicKey val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptional(val(), data, 1 + offset);
    }
  }

  interface OptionalBorshEnum extends RustEnum {

    Borsh val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1 + Borsh.writeOptional(val(), data, 1 + offset);
    }
  }
}
