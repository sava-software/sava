package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

// https://github.com/solana-program/token-metadata/tree/main/interface#optional-state
public record TokenMetadata(PublicKey updateAuthority,
                            PublicKey mint,
                            String name,
                            String symbol,
                            String uri,
                            Map<String, String> additionalMetadata) implements MintTokenExtension {

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
    final var name = readUtf8(data, i, nameLength, "name");
    i += nameLength;

    final int symbolLength = ByteUtil.getInt32LE(data, i);
    i += Integer.BYTES;
    final var symbol = readUtf8(data, i, symbolLength, "symbol");
    i += symbolLength;

    final int uriLength = ByteUtil.getInt32LE(data, i);
    i += Integer.BYTES;
    final var uri = readUtf8(data, i, uriLength, "uri");
    i += uriLength;

    final int numExtras = ByteUtil.getInt32LE(data, i);
    i += Integer.BYTES;

    final Map<String, String> additionalMetadata;
    if (numExtras == 0) {
      additionalMetadata = Map.of();
    } else {
      // Each entry needs at least two u32 length prefixes, so the remaining bytes bound
      // the largest possible count before the loop reads any entry. Compare unsigned
      // because the count is a u32: a top-bit count is negative as a Java int and would
      // otherwise make the signed loop execute zero times and silently decode as empty.
      if (Integer.compareUnsigned(numExtras, (data.length - i) >> 3) > 0) {
        throw new IllegalArgumentException("Invalid additional metadata count: " + numExtras);
      }
      final var entries = new LinkedHashMap<String, String>();
      for (int m = 0, l; m < numExtras; ++m) {
        l = ByteUtil.getInt32LE(data, i);
        i += Integer.BYTES;
        final var key = readUtf8(data, i, l, "additional metadata key");
        i += l;
        l = ByteUtil.getInt32LE(data, i);
        i += Integer.BYTES;
        final var val = readUtf8(data, i, l, "additional metadata value");
        i += l;
        if (entries.putIfAbsent(key, val) != null) {
          throw new IllegalArgumentException("Duplicate additional metadata key: " + key);
        }
      }
      // Map.copyOf may return a salted MapN whose iteration order differs from the Borsh Vec.
      additionalMetadata = Collections.unmodifiableMap(entries);
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

  private static String readUtf8(final byte[] data,
                                 final int offset,
                                 final int length,
                                 final String field) {
    try {
      // Borsh's Rust String reader uses String::from_utf8 and rejects malformed bytes;
      // String(byte[], Charset) would silently replace them with U+FFFD.
      return UTF_8.newDecoder().decode(ByteBuffer.wrap(data, offset, length)).toString();
    } catch (final CharacterCodingException exception) {
      throw new IllegalArgumentException("Invalid UTF-8 in TokenMetadata " + field + '.', exception);
    }
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.TokenMetadata;
  }

  @Override
  public int l() {
    final int additionalMetaDataLength = Integer.BYTES
        + additionalMetadata.entrySet().stream()
        .mapToInt(entry -> Borsh.len(entry.getKey()) + Borsh.len(entry.getValue()))
        .sum();
    return PUBLIC_KEY_LENGTH
        + PUBLIC_KEY_LENGTH
        + Borsh.len(name)
        + Borsh.len(symbol)
        + Borsh.len(uri)
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
