package software.sava.core.accounts.sysvar;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.util.function.BiFunction;

record LastRestartSlot(PublicKey address, long lastRestartSlot) implements Borsh {

  public static final int BYTES = Long.BYTES;

  public static final BiFunction<PublicKey, byte[], LastRestartSlot> FACTORY = LastRestartSlot::read;

  public static LastRestartSlot read(final byte[] data) {
    return read(data, 0);
  }

  public static LastRestartSlot read(final byte[] data, final int offset) {
    return read(null, data, offset);
  }

  public static LastRestartSlot read(final PublicKey address, final byte[] data) {
    return read(address, data, 0);
  }

  public static LastRestartSlot read(final PublicKey address, final byte[] data, final int offset) {
    return new LastRestartSlot(address, ByteUtil.getInt64LE(data, offset));
  }

  @Override
  public int write(final byte[] data, final int offset) {
    ByteUtil.putInt64LE(data, offset, lastRestartSlot);
    return BYTES;
  }

  @Override
  public int l() {
    return BYTES;
  }
}
