package software.sava.core.accounts.token;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.extensions.*;
import software.sava.core.encoding.ByteUtil;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BiFunction;

public record Token2022(Mint mint, List<TokenExtension> tokenExtensions) {

  private static final int PADDING_AFTER_MINT = 83;

  public static final BiFunction<PublicKey, byte[], Token2022> FACTORY = Token2022::read;

  public static Token2022 read(final PublicKey address, final byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var mint = Mint.read(address, data);
    int i = Mint.BYTES + PADDING_AFTER_MINT;
    // int accountType = data[i] & 0xFF; // mint
    ++i;
    final var extensions = new ArrayList<TokenExtension>();
    final var extensionTypes = ExtensionType.values();
    while (i < data.length) {
      int extensionType = ByteUtil.getInt16LE(data, i);
      i += Short.BYTES;
      int length = ByteUtil.getInt16LE(data, i);
      i += Short.BYTES;
      final var extensionData = switch (extensionTypes[extensionType]) {
        case TransferFeeConfig -> TransferFeeConfig.read(data, i);
        case TransferFeeAmount -> TransferFeeAmount.read(data, i);
        case MintCloseAuthority -> MintCloseAuthority.read(data, i);
        case ConfidentialTransferMint -> ConfidentialTransferMint.read(data, i);
        case TokenMetadata -> TokenMetadata.read(data, i);
        default -> null;
      };
      if (extensionData != null) {
        extensions.add(extensionData);
      }
      i += length;
    }
    return new Token2022(mint, List.copyOf(extensions));
  }


  public static void main(final String[] args) {
    final byte[] data = Base64.getDecoder().decode("""
        AQAAAL0bqBXg71MaIuhnJGvlnfWFAR/enZ3TzgTF+LFU5oyPAAAAAAAAAAAJAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAARIAQAC9G6gV4O9TGiLoZyRr5Z31hQEf3p2d084ExfixVOaMj70bqBXg71MaIuhnJGvlnfWFAR/enZ3TzgTF+LFU5oyPAwAgAL0bqBXg71MaIuhnJGvlnfWFAR/enZ3TzgTF+LFU5oyPEwCiAr0bqBXg71MaIuhnJGvlnfWFAR/enZ3TzgTF+LFU5oyPvRuoFeDvUxoi6Gcka+Wd9YUBH96dndPOBMX4sVTmjI8NAAAAVGVzdCBGdW5kIDAwMQQAAAB0U09MTgAAAGh0dHBzOi8vYXBpLmdsYW0uc3lzdGVtcy9tZXRhZGF0YS9EakNXcW42RW1TbUFqN0dzeEtaeEN0bm9wZ21HWlljRFIycWoxVkR0VEpuegsAAAAGAAAARnVuZElkLAAAAEJrYllNNkg1emFnN1oxNVUxVnZhRjdhRTVnUVl4RjZVd1M1dEFNN2N4VnhSCAAAAEltYWdlVXJpTwAAAGh0dHBzOi8vYXBpLmdsYW0uc3lzdGVtcy9pbWFnZS9EakNXcW42RW1TbUFqN0dzeEtaeEN0bm9wZ21HWlljRFIycWoxVkR0VEpuei5wbmcSAAAAU2hhcmVDbGFzc0N1cnJlbmN5AwAAAFNPTB0AAABDdXJyZW5jeU9mTWluaW1hbFN1YnNjcmlwdGlvbgMAAABTT0wSAAAARnVsbFNoYXJlQ2xhc3NOYW1lDQAAAFRlc3QgRnVuZCAwMDEQAAAASW52ZXN0bWVudFN0YXR1cwQAAABvcGVuIgAAAE1pbmltYWxJbml0aWFsU3Vic2NyaXB0aW9uQ2F0ZWdvcnkGAAAAYW1vdW50IgAAAE1pbmltYWxJbml0aWFsU3Vic2NyaXB0aW9uSW5BbW91bnQBAAAAMRwAAABTaGFyZUNsYXNzRGlzdHJpYnV0aW9uUG9saWN5DAAAAGFjY3VtdWxhdGluZxQAAABTaGFyZUNsYXNzTGF1bmNoRGF0ZQoAAAAyMDI0LTA4LTI1EwAAAFNoYXJlQ2xhc3NMaWZlY3ljbGUGAAAAYWN0aXZl""");

    final var token2022 = Token2022.read(PublicKey.fromBase58Encoded("DjCWqn6EmSmAj7GsxKZxCtnopgmGZYcDR2qj1VDtTJnz"), data);
    System.out.println(token2022);
  }
}
