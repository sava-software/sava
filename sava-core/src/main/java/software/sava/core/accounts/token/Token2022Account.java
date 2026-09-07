package software.sava.core.accounts.token;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.extensions.AccountType;
import software.sava.core.accounts.token.extensions.TokenExtension;
import software.sava.core.serial.Serializable;

import java.util.Set;
import java.util.function.BiFunction;

import static software.sava.core.accounts.token.Token2022.parseAccountType;

/// A Token-2022 token account: the [TokenAccount] base state, the account-type discriminant
/// that follows it, and the TLV extensions after that.
///
/// @param tokenAccount    the 165-byte base account state.
/// @param type            `null` when the account carries no discriminant — either because
///                        the buffer has no room for one, the shape of a token account that
///                        never had extension space allocated ([TokenAccount#BYTES] exactly),
///                        or because the byte on the wire is an [AccountType] released after
///                        this library was last synced. With no extensions either, both
///                        re-serialize as the base state alone. [AccountType#Uninitialized]
///                        alongside an [AccountState#Uninitialized] base is not a defect:
///                        extension initializers run before `InitializeAccount`, so that is
///                        what an account looks like between the two instructions.
/// @param tokenExtensions the parsed TLV entries, empty when there are none.
public record Token2022Account(TokenAccount tokenAccount,
                               AccountType type,
                               Set<TokenExtension> tokenExtensions) implements Serializable {

  public static final BiFunction<PublicKey, byte[], Token2022Account> FACTORY = Token2022Account::read;

  public static Token2022Account read(final PublicKey address, final byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }
    Token2022.rejectMultisigLength(data.length);
    if (data.length < TokenAccount.BYTES) {
      // TokenAccount.read only touches its option-guarded fields when the tag says to, so a
      // truncated buffer can decode as a whole account. The base state is not optional.
      throw new IllegalArgumentException(String.format(
          "A token account of %d bytes is malformed: an extension-free token account is %d"
              + " bytes, and a token account with extensions is at least %d.",
          data.length, TokenAccount.BYTES, TokenAccount.BYTES + 1
      ));
    }
    final var tokenAccount = TokenAccount.read(address, data);
    int i = tokenAccount.l();
    if (data.length == i) {
      // No remainder after the base state: an extension-free token account, which carries no
      // account-type byte at all.
      return new Token2022Account(tokenAccount, null, Set.of());
    }
    final var accountType = parseAccountType(data, i);
    Token2022.requireAccountType(
        AccountType.Account, accountType, tokenAccount.state() != AccountState.Uninitialized
    );
    ++i;
    return new Token2022Account(tokenAccount, accountType, Token2022.parseExtensions(data, i));
  }

  private boolean baseStateOnly() {
    return type == null && tokenExtensions.isEmpty();
  }

  @Override
  public int l() {
    if (baseStateOnly()) {
      return tokenAccount.l();
    }
    int l = tokenAccount.l() + 1 + (tokenExtensions.size() * Integer.BYTES);
    for (final var extension : tokenExtensions) {
      l += extension.l();
    }
    return Token2022.adjustLengthForMultisig(l);
  }

  @Override
  public int write(final byte[] data, final int offset) {
    final int accountLength = tokenAccount.write(data, offset);
    if (baseStateOnly()) {
      return accountLength;
    }
    int i = offset + accountLength;
    data[i] = (byte) type.ordinal();
    ++i;
    final int written = (i - offset) + Token2022.writeExtensions(tokenExtensions, data, i);
    return Token2022.padLengthForMultisig(data, offset, written);
  }
}
