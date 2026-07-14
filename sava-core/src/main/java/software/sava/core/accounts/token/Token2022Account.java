package software.sava.core.accounts.token;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.extensions.AccountType;
import software.sava.core.accounts.token.extensions.ExtensionType;
import software.sava.core.accounts.token.extensions.TokenExtension;
import software.sava.core.accounts.token.extensions.UnknownTokenExtension;
import software.sava.core.serial.Serializable;

import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static software.sava.core.accounts.token.Token2022.parseAccountType;

public record Token2022Account(TokenAccount tokenAccount,
                               AccountType type,
                               Set<TokenExtension> tokenExtensions) implements Serializable {

  public static final BiFunction<PublicKey, byte[], Token2022Account> FACTORY = Token2022Account::read;

  public static Token2022Account read(final PublicKey address, final byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var tokenAccount = TokenAccount.read(address, data);
    int i = tokenAccount.l();
    final var accountType = parseAccountType(data, i);
    ++i;
    return new Token2022Account(tokenAccount, accountType, Token2022.parseExtensions(data, i));
  }

  /// Deprecated with [ExtensionType], use [#tokenExtensions()] and switch on the sealed
  /// [TokenExtension] type. Built dynamically on each call, [UnknownTokenExtension]
  /// entries are dropped since they cannot be keyed by [ExtensionType].
  @Deprecated(forRemoval = true)
  public Map<ExtensionType, TokenExtension> extensions() {
    return Token2022.parseExtensionsMap(tokenExtensions);
  }

  @Override
  public int l() {
    int l = tokenAccount.l() + 1 + (tokenExtensions.size() * Integer.BYTES);
    for (final var extension : tokenExtensions) {
      l += extension.l();
    }
    return l;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset + tokenAccount.write(data, offset);
    data[i] = (byte) type.ordinal();
    ++i;
    for (final var extension : tokenExtensions) {
      i += TokenExtension.write(extension, data, i);
    }
    return i - offset;
  }
}
