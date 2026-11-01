package software.sava.core.rpc;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;

public sealed interface Filter permits DataSizeFilter, MemCmpFilter, TokenAccountStateFilter {

  int MAX_MEM_COMP_LENGTH = 128;

  static Filter createDataSizeFilter(final int dataSize) {
    return new DataSizeFilter(dataSize);
  }

  static Filter createMemCompFilter(final int offset, final byte[] data) {
    if (data.length > MAX_MEM_COMP_LENGTH) {
      throw new IllegalStateException("Maximum memory compare filter size is 128 bytes.");
    }
    return new MemCmpFilter(offset, Base58.encode(data));
  }

  static Filter createMemCompFilter(final int offset, final PublicKey publicKey) {
    return new MemCmpFilter(offset, publicKey.toBase58());
  }

  static Filter createMemCompFilter(final int offset, final PublicKey publicKey, final PublicKey publicKey2) {
    final byte[] data = new byte[PublicKey.PUBLIC_KEY_LENGTH << 1];
    publicKey.write(data, 0);
    publicKey2.write(data, PublicKey.PUBLIC_KEY_LENGTH);
    return createMemCompFilter(offset, data);
  }

  /// Matches accounts whose data is an initialized token account of either token program, whatever
  /// its length: Agave's `tokenAccountState` filter. The node applies spl-generic-token's
  /// `token_2022::Account::valid_account_data` to the data alone, which accepts exactly 165 bytes,
  /// the SPL Token layout, or more than 165 bytes, other than the 355 of a multisig, whose
  /// account-type byte at offset 165 is `Account` (`2`). Either way the state byte at offset 108
  /// must not be `Uninitialized`, so frozen accounts match too.
  ///
  /// Agave counts it as the token-account requisite of its by-owner and by-mint secondary
  /// indexes, exactly as it counts a 165-byte `dataSize` filter or Token-2022's account-type
  /// memcmp (byte `2` at offset 165): paired with a mint memcmp at offset 0 or an owner memcmp at
  /// offset 32 on either token program, the query is answered from those indexes on a node that
  /// keeps them. Use it in place of a 165-byte `dataSize` filter, such as
  /// `TokenAccount.TOKEN_ACCOUNT_SIZE_FILTER`, whenever Token-2022 accounts must be included: any
  /// extension makes an account longer than 165 bytes, and the size filter drops it.
  ///
  /// It is valid for `getProgramAccounts` and `programSubscribe` alike, and is sent as the bare
  /// JSON string `"tokenAccountState"` rather than an object. Agave rejects a request with more
  /// than four filters (`MAX_GET_PROGRAM_ACCOUNT_FILTERS`), this one included; sava does not
  /// enforce the limit.
  ///
  /// @return the shared instance: every call returns the same filter.
  static Filter tokenAccountState() {
    return TokenAccountStateFilter.INSTANCE;
  }

  String toJson();
}
