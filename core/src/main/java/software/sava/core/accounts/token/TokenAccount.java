package software.sava.core.accounts.token;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.rpc.Filter;

import java.util.function.BiFunction;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.rpc.Filter.createMemCompFilter;

public record TokenAccount(PublicKey address,
                           PublicKey mint,
                           PublicKey owner,
                           long amount,
                           int delegateOption,
                           PublicKey delegate,
                           AccountState state,
                           int isNativeOption,
                           long isNative,
                           long delegatedAmount,
                           int closeAuthorityOption,
                           PublicKey closeAuthority) implements Borsh {

  public static final int BYTES = 165;
  public static final Filter TOKEN_ACCOUNT_SIZE_FILTER = Filter.createDataSizeFilter(BYTES);

  public static final int MINT_OFFSET = 0;
  public static final int OWNER_OFFSET = MINT_OFFSET + PUBLIC_KEY_LENGTH;
  public static final int AMOUNT_OFFSET = OWNER_OFFSET + PUBLIC_KEY_LENGTH;
  public static final int DELEGATE_OPTION_OFFSET = AMOUNT_OFFSET + Long.BYTES;
  public static final int DELEGATE_OFFSET = DELEGATE_OPTION_OFFSET + Integer.BYTES;
  public static final int STATE_OFFSET = DELEGATE_OFFSET + PUBLIC_KEY_LENGTH;
  public static final int IS_NATIVE_OPTION_OFFSET = STATE_OFFSET + 1;
  public static final int IS_NATIVE_OFFSET = IS_NATIVE_OPTION_OFFSET + Integer.BYTES;
  public static final int DELEGATED_AMOUNT_OFFSET = IS_NATIVE_OFFSET + Long.BYTES;
  public static final int CLOSE_AUTHORITY_OPTION_OFFSET = DELEGATED_AMOUNT_OFFSET + Long.BYTES;
  public static final int CLOSE_AUTHORITY_OFFSET = CLOSE_AUTHORITY_OPTION_OFFSET + Integer.BYTES;

  public static Filter createMintFilter(final PublicKey mint) {
    return createMemCompFilter(MINT_OFFSET, mint);
  }

  public static Filter createOwnerFilter(final PublicKey owner) {
    return createMemCompFilter(OWNER_OFFSET, owner);
  }

  public static Filter createDelegateFilter(final PublicKey delegate) {
    return createMemCompFilter(DELEGATE_OFFSET, delegate);
  }

  public static Filter createCloseAuthorityFilter(final PublicKey closeAuthority) {
    return createMemCompFilter(CLOSE_AUTHORITY_OFFSET, closeAuthority);
  }

  public enum AccountState {
    Uninitialized,
    Initialized,
    Frozen
  }

  public static final BiFunction<PublicKey, byte[], TokenAccount> FACTORY = TokenAccount::read;

  public static TokenAccount read(final PublicKey publicKey, final byte[] data) {
    return read(publicKey, data, 0);
  }

  public static TokenAccount read(final PublicKey publicKey, final byte[] data, final int offset) {
    int i = offset;
    final var mint = PublicKey.readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;
    final var owner = PublicKey.readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;
    final long amount = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final int delegateOption = ByteUtil.getInt32LE(data, i);
    i += Integer.BYTES;
    final var delegate = delegateOption == 1
        ? PublicKey.readPubKey(data, i)
        : null;
    i += PUBLIC_KEY_LENGTH;
    final var state = TokenAccount.AccountState.values()[data[i]];
    ++i;
    final int isNativeOption = ByteUtil.getInt32LE(data, i);
    i += Integer.BYTES;
    final long isNative = isNativeOption == 1
        ? ByteUtil.getInt64LE(data, i)
        : 0;
    i += Long.BYTES;
    final long delegatedAmount = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final int closeAuthorityOption = ByteUtil.getInt32LE(data, i);
    i += Integer.BYTES;
    final var closeAuthority = closeAuthorityOption == 1
        ? PublicKey.readPubKey(data, i)
        : null;
    return new TokenAccount(
        publicKey,
        mint,
        owner,
        amount,
        delegateOption,
        delegate,
        state,
        isNativeOption,
        isNative,
        delegatedAmount,
        closeAuthorityOption,
        closeAuthority
    );
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset;
    i += mint.write(data, i);
    i += owner.write(data, i);
    ByteUtil.putInt64LE(data, i, amount);
    i += Long.BYTES;
    ByteUtil.putInt32LE(data, i, delegateOption);
    i += Integer.BYTES;
    i += delegate.write(data, i);
    data[i] = (byte) state.ordinal();
    ++i;
    ByteUtil.putInt32LE(data, i, isNativeOption);
    i += Integer.BYTES;
    ByteUtil.putInt64LE(data, i, isNative);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, delegatedAmount);
    i += Long.BYTES;
    ByteUtil.putInt32LE(data, i, closeAuthorityOption);
    i += Integer.BYTES;
    i += closeAuthority.write(data, i);
    return i - offset;
  }

  @Override
  public int l() {
    return BYTES;
  }
}
