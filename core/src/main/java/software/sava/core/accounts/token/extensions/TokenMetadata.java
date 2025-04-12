package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.util.Map;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record TokenMetadata(PublicKey updateAuthority,
                            PublicKey mint,
                            String name,
                            String symbol,
                            String uri,
                            Map<String, String> additionalMetadata) implements TokenExtension {

  @SuppressWarnings("unchecked")
  public static TokenMetadata read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }

    int i = offset;

    final var updateAuthority = readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;

    final var mint = PublicKey.readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;

    final int nameLength = ByteUtil.getInt32LE(data, i);
    i += Integer.BYTES;
    final var name = new String(data, i, nameLength);
    i += nameLength;

    final int symbolLength = ByteUtil.getInt32LE(data, i);
    i += Integer.BYTES;
    final var symbol = new String(data, i, symbolLength);
    i += symbolLength;

    final int uriLength = ByteUtil.getInt32LE(data, i);
    i += Integer.BYTES;
    final var uri = new String(data, i, uriLength);
    i += uriLength;

    final int numExtras = ByteUtil.getInt32LE(data, i);
    i += Integer.BYTES;

    final Map<String, String> additionalMetadata;
    if (numExtras == 0) {
      additionalMetadata = Map.of();
    } else {
      final var entries = new Map.Entry[numExtras];
      for (int m = 0, l; m < numExtras; ++m) {
        l = ByteUtil.getInt32LE(data, i);
        i += Integer.BYTES;
        final var key = new String(data, i, l);
        i += l;
        l = ByteUtil.getInt32LE(data, i);
        i += Integer.BYTES;
        final var val = new String(data, i, l);
        i += l;
        entries[m] = Map.entry(key, val);
      }
      additionalMetadata = Map.ofEntries(entries);
    }
    return new TokenMetadata(
        updateAuthority,
        mint,
        name,
        symbol,
        uri,
        additionalMetadata
    );
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.TokenMetadata;
  }

  @Override
  public int l() {
    final int additionalMetaDataLength = Integer.BYTES
        + (Long.BYTES * additionalMetadata.size())
        + additionalMetadata.entrySet().stream()
        .mapToInt(entry -> entry.getKey().length() + entry.getValue().length())
        .sum();
    return PUBLIC_KEY_LENGTH
        + PUBLIC_KEY_LENGTH
        + Integer.BYTES
        + name.length()
        + Integer.BYTES
        + symbol.length()
        + Integer.BYTES
        + uri.length()
        + additionalMetaDataLength;
  }


  @Override
  public int write(final byte[] data, final int offset) {
    updateAuthority.write(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    mint.write(data, i);
    i += PUBLIC_KEY_LENGTH;
    i += Borsh.write(name, data, i);
    i += Borsh.write(symbol, data, i);
    i += Borsh.write(uri, data, i);
    ByteUtil.putInt32LE(data, i, additionalMetadata.size());
    i += Integer.BYTES;
    for (final var entry : additionalMetadata.entrySet()) {
      i += Borsh.write(entry.getKey(), data, i);
      i += Borsh.write(entry.getValue(), data, i);
    }
    return i - offset;
  }
}
