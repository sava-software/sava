package software.sava.core.accounts.sysvar;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.math.BigInteger;
import java.util.function.BiFunction;

// https://github.com/anza-xyz/agave/blob/df27fb3a7386f0d2cc64df81c46a09ab79b18ba0/sdk/epoch-rewards/src/lib.rs#L26
public record EpochRewards(PublicKey address,
                           long distributionStartingBlockHeight,
                           long numPartitions,
                           byte[] parentBlockHash,
                           BigInteger totalPoints,
                           long totalRewards,
                           long distributedRewards,
                           boolean active) implements Borsh {

  public static final int BYTES = Long.BYTES
      + Long.BYTES
      + 32
      + (Long.BYTES << 1)
      + Long.BYTES
      + Long.BYTES
      + 1;

  public static final BiFunction<PublicKey, byte[], EpochRewards> FACTORY = EpochRewards::read;

  public static EpochRewards read(final byte[] data) {
    return read(data, 0);
  }

  public static EpochRewards read(final byte[] data, final int offset) {
    return read(null, data, offset);
  }

  public static EpochRewards read(final PublicKey address, final byte[] data) {
    return read(address, data, 0);
  }

  public static EpochRewards read(final PublicKey address, final byte[] data, int offset) {
    final long distributionStartingBlockHeight = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long numPartitions = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final byte[] parentBlockHash = new byte[32];
    System.arraycopy(data, offset, parentBlockHash, 0, 32);
    offset += 32;
    final var totalPoints = ByteUtil.getInt128LE(data, offset);
    offset += (Long.BYTES << 1);
    final long totalRewards = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long distributedRewards = ByteUtil.getInt64LE(data, offset);
    return new EpochRewards(
        address,
        distributionStartingBlockHeight,
        numPartitions,
        parentBlockHash,
        totalPoints,
        totalRewards,
        distributedRewards,
        data[++offset] == 1
    );
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset;
    ByteUtil.putInt64LE(data, i, distributionStartingBlockHeight);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, numPartitions);
    i += Long.BYTES;
    System.arraycopy(parentBlockHash, 0, data, i, 32);
    i += 32;
    ByteUtil.putInt128LE(data, i, totalPoints);
    i += (Long.BYTES << 1);
    ByteUtil.putInt64LE(data, i, totalRewards);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, distributedRewards);
    i += Long.BYTES;
    data[i] = (byte) (active ? 1 : 0);
    ++i;
    return i - offset;
  }

  @Override
  public int l() {
    return BYTES;
  }
}
