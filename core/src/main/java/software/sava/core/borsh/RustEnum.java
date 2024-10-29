package software.sava.core.borsh;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import java.math.BigInteger;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

public interface RustEnum extends Borsh {

  int ordinal();

  default String name() {
    return getClass().getSimpleName();
  }

  default int writeOrdinal(final byte[] data, final int offset) {
    data[offset] = (byte) ordinal();
    return 1 + offset;
  }

  interface EnumNone extends RustEnum {

    default int l() {
      return 1;
    }

    default int write(final byte[] data, final int offset) {
      return 1;
    }
  }

  interface EnumBool extends RustEnum {

    boolean val();

    default int l() {
      return 2;
    }

    default int write(final byte[] data, final int offset) {
      writeOrdinal(data, offset);
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
      ByteUtil.putFloat32LE(data, writeOrdinal(data, offset), val());
      return l();
    }
  }

  interface EnumFloat64 extends RustEnum {

    double val();

    default int l() {
      return 1 + Double.BYTES;
    }

    default int write(final byte[] data, final int offset) {
      ByteUtil.putFloat64LE(data, writeOrdinal(data, offset), val());
      return l();
    }
  }

  interface EnumInt8 extends RustEnum {

    int val();

    default int l() {
      return 2;
    }

    default int write(final byte[] data, final int offset) {
      data[writeOrdinal(data, offset)] = (byte) val();
      return 2;
    }
  }

  interface EnumInt16 extends RustEnum {

    int val();

    default int l() {
      return 1 + Short.BYTES;
    }

    default int write(final byte[] data, final int offset) {
      ByteUtil.putInt16LE(data, writeOrdinal(data, offset), (short) val());
      return l();
    }
  }

  interface EnumInt32 extends RustEnum {

    int val();

    default int l() {
      return 1 + Integer.BYTES;
    }

    default int write(final byte[] data, final int offset) {
      ByteUtil.putInt32LE(data, writeOrdinal(data, offset), val());
      return l();
    }
  }

  interface EnumInt64 extends RustEnum {

    long val();

    default int l() {
      return 1 + Long.BYTES;
    }

    default int write(final byte[] data, final int offset) {
      ByteUtil.putInt64LE(data, writeOrdinal(data, offset), val());
      return l();
    }
  }

  interface EnumInt128 extends RustEnum {

    BigInteger val();

    default int l() {
      return 129;
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.write128(val(), data, writeOrdinal(data, offset));
    }
  }

  interface EnumInt256 extends RustEnum {

    BigInteger val();

    default int l() {
      return 257;
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.write256(val(), data, writeOrdinal(data, offset));
    }
  }

  interface EnumBytes extends RustEnum {

    byte[] val();

    default int l() {
      return 1 + Borsh.lenVector(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeVector(val(), data, writeOrdinal(data, offset));
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
      return 1 + val().write(data, writeOrdinal(data, offset));
    }
  }

  interface BorshEnum extends RustEnum {

    Borsh val();

    default int l() {
      return 1 + val().l();
    }

    default int write(final byte[] data, final int offset) {
      return 1 + val().write(data, writeOrdinal(data, offset));
    }
  }

  interface BorshVectorEnum extends RustEnum {

    Borsh[] val();

    default int l() {
      return 1 + Borsh.lenVector(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeVector(val(), data, writeOrdinal(data, offset));
    }
  }

  interface BorshArrayEnum extends RustEnum {

    Borsh[] val();

    default int l() {
      return 1 + Borsh.lenArray(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeArray(val(), data, writeOrdinal(data, offset));
    }
  }

  interface PublicKeyVectorEnum extends RustEnum {

    PublicKey[] val();

    default int l() {
      return 1 + Borsh.lenVector(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeVector(val(), data, writeOrdinal(data, offset));
    }
  }

  interface PublicKeyArrayEnum extends RustEnum {

    PublicKey[] val();

    default int l() {
      return 1 + Borsh.lenArray(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeArray(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalEnumBool extends RustEnum {

    Boolean val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeOptional(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalEnumFloat32 extends RustEnum {

    OptionalDouble val();

    default int l() {
      return 1 + Borsh.lenOptionalfloat(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeOptionalfloat(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalEnumFloat64 extends RustEnum {

    OptionalDouble val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeOptional(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalEnumInt8 extends RustEnum {

    OptionalInt val();

    default int l() {
      return 1 + Borsh.lenOptionalbyte(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeOptionalbyte(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalEnumInt16 extends RustEnum {

    OptionalInt val();

    default int l() {
      return 1 + Borsh.lenOptionalshort(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeOptionalshort(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalEnumInt32 extends RustEnum {

    OptionalInt val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeOptional(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalEnumInt64 extends RustEnum {

    OptionalLong val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeOptional(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalEnumInt128 extends RustEnum {

    BigInteger val();

    default int l() {
      return 1 + Borsh.len128Optional(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.write128Optional(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalEnumInt256 extends RustEnum {

    BigInteger val();

    default int l() {
      return 1 + Borsh.len256Optional(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.write256Optional(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalEnumBytes extends RustEnum {

    byte[] val();

    default int l() {
      return 1 + Borsh.lenOptionalVector(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeOptionalVector(val(), data, writeOrdinal(data, offset));
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
      return 1 + Borsh.writeOptional(val(), data, writeOrdinal(data, offset));
    }
  }

  interface OptionalBorshEnum extends RustEnum {

    Borsh val();

    default int l() {
      return 1 + Borsh.lenOptional(val());
    }

    default int write(final byte[] data, final int offset) {
      return 1 + Borsh.writeOptional(val(), data, writeOrdinal(data, offset));
    }
  }
}
