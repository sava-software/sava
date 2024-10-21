package software.sava.core.accounts.token;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.extensions.*;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BiFunction;

public record Token2022(Mint mint,
                        int accountType,
                        List<TokenExtension> tokenExtensions) implements Serializable {

  private static final int PADDING_AFTER_MINT = 83;

  public static final BiFunction<PublicKey, byte[], Token2022> FACTORY = Token2022::read;

  public static Token2022 read(final PublicKey address, final byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var mint = Mint.read(address, data);
    int i = Mint.BYTES + PADDING_AFTER_MINT;
    final int accountType = data[i] & 0xFF;
    ++i;
    final var extensions = new ArrayList<TokenExtension>();
    final var extensionTypes = ExtensionType.values();
    while (i < data.length) {
      int extensionType = ByteUtil.getInt16LE(data, i);
      i += Short.BYTES;
      int length = ByteUtil.getInt16LE(data, i);
      i += Short.BYTES;
      final var extensionData = switch (extensionTypes[extensionType]) {
        case Uninitialized -> Uninitialized.INSTANCE;
        case TransferFeeConfig -> TransferFeeConfig.read(data, i);
        case TransferFeeAmount -> TransferFeeAmount.read(data, i);
        case MintCloseAuthority -> MintCloseAuthority.read(data, i);
        case ConfidentialTransferMint -> ConfidentialTransferMint.read(data, i);
        case DefaultAccountState -> DefaultAccountState.read(data, i);
        case ImmutableOwner -> ImmutableOwner.INSTANCE;
        case MemoTransfer -> MemoTransfer.read(data, i);
        case NonTransferable -> NonTransferable.INSTANCE;
        case InterestBearingConfig -> InterestBearingConfig.read(data, i);
        case CpiGuard -> CpiGuard.read(data, i);
        case PermanentDelegate -> PermanentDelegate.read(data, i);
        case NonTransferableAccount -> NonTransferableAccount.INSTANCE;
        case TransferHook -> TransferHook.read(data, i);
        case TransferHookAccount -> TransferHookAccount.read(data, i);
        case MetadataPointer -> MetadataPointer.read(data, i);
        case TokenMetadata -> TokenMetadata.read(data, i);
        case GroupPointer -> GroupPointer.read(data, i);
        case TokenGroup -> TokenGroup.INSTANCE;
        case GroupMemberPointer -> GroupMemberPointer.read(data, i);
        case TokenGroupMember -> TokenGroupMember.INSTANCE;
        case ConfidentialTransferAccount, ConfidentialTransferFeeAmount, ConfidentialTransferFeeConfig -> null;
      };
      if (extensionData != null) {
        extensions.add(extensionData);
      }
      i += length;
    }
    return new Token2022(mint, accountType, java.util.List.copyOf(extensions));
  }

  @Override
  public int l() {
    int l = Mint.BYTES + PADDING_AFTER_MINT + 1 + (tokenExtensions.size() * Integer.BYTES);
    for (final var extension : tokenExtensions) {
      l += extension.l();
    }
    return l;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset + mint.write(data, offset) + PADDING_AFTER_MINT;
    data[i] = (byte) accountType;
    ++i;
    for (final var extension : tokenExtensions) {
      i += TokenExtension.write(extension, data, i);
    }
    return i - offset;
  }

  public static void main(final String[] args) {
    final byte[] data = Base64.getDecoder().decode("""
        AQAAAEWL8jQUbMmHNpVOADdvZA1z7a1jyDebZa3bW4Jv0uMVmDvV22fxAAAGAQEAAAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQMAIAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKwwAIAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKwEAbAAXhTJh72q4Uypn8FOGWq0xKT/PB88SCrW5oVcGVI3AKxeFMmHvarhTKmfwU4ZarTEpP88HzxIKtbmhVwZUjcArAAAAAAAAAABdAgAAAAAAAAAAAAAAAAAAAABdAgAAAAAAAAAAAAAAAAAAAAAEAEEAF4UyYe9quFMqZ/BThlqtMSk/zwfPEgq1uaFXBlSNwCsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAIEAF4UyYe9quFMqZ/BThlqtMSk/zwfPEgq1uaFXBlSNwCscN+ZDO3ME3YJzeuQNm4vzxJ9bDmxJqNUzKLPlBpAcVwEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADgBAABeFMmHvarhTKmfwU4ZarTEpP88HzxIKtbmhVwZUjcArAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAASAEAAgnQVMyO1m+9u94xUbiBsQYh7lCdtZO+gj0jz14I76NgXkkg7bIoqh7dHHYFPlZH5OVyECpzj2fTVun06S4p0nhMArgCCdBUzI7Wb7273jFRuIGxBiHuUJ21k76CPSPPXgjvo2BeSSDtsiiqHt0cdgU+Vkfk5XIQKnOPZ9NW6fTpLinSeCgAAAFBheVBhbCBVU0QFAAAAUFlVU0RPAAAAaHR0cHM6Ly90b2tlbi1tZXRhZGF0YS5wYXhvcy5jb20vcHl1c2RfbWV0YWRhdGEvcHJvZC9zb2xhbmEvcHl1c2RfbWV0YWRhdGEuanNvbgAAAAA=
        """.strip()
    );

    final var token2022 = Token2022.read(PublicKey.fromBase58Encoded("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo"), data);
    System.out.println(token2022.mint);
    token2022.tokenExtensions.forEach(System.out::println);
  }
}
