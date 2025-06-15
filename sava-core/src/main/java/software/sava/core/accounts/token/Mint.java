package software.sava.core.accounts.token;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

import java.util.function.BiFunction;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record Mint(PublicKey address,
                   PublicKey mintAuthority,
                   long supply,
                   int decimals,
                   boolean initialized,
                   PublicKey freezeAuthority) implements Serializable {

  public static final int BYTES = 82;

  public static final BiFunction<PublicKey, byte[], Mint> FACTORY = Mint::read;

  public static Mint read(final PublicKey address, final byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }
    final boolean hasMintAuthority = ByteUtil.getInt32LE(data, 0) == 1;
    final var mintAuthority = hasMintAuthority
        ? readPubKey(data, Integer.BYTES)
        : null;
    int i = Integer.BYTES + PUBLIC_KEY_LENGTH;
    final long supply = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final int decimals = data[i] & 0xFF;
    ++i;
    final boolean initialized = data[i] == 1;
    ++i;
    final boolean hasFreezeAuthority = ByteUtil.getInt32LE(data, i) == 1;
    i += Integer.BYTES;
    final var freezeAuthority = hasFreezeAuthority
        ? readPubKey(data, i)
        : null;
    return new Mint(
        address,
        mintAuthority,
        supply,
        decimals,
        initialized,
        freezeAuthority
    );
  }

  @Override
  public int write(final byte[] data, final int offset) {
    if (mintAuthority != null) {
      ByteUtil.putInt32LE(data, 0, 1);
      mintAuthority.write(data, Integer.BYTES);
    }
    int i = Integer.BYTES + PUBLIC_KEY_LENGTH;
    ByteUtil.putInt64LE(data, i, supply);
    i += Long.BYTES;
    data[i] = (byte) decimals;
    ++i;
    data[i] = (byte) (initialized ? 1 : 0);
    ++i;
    if (freezeAuthority != null) {
      ByteUtil.putInt32LE(data, i, 1);
      freezeAuthority.write(data, i + Integer.BYTES);
    }
    return BYTES;
  }

  @Override
  public int l() {
    return BYTES;
  }
}