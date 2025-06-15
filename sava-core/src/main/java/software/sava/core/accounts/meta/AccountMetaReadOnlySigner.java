package software.sava.core.accounts.meta;

import software.sava.core.accounts.PublicKey;

final class AccountMetaReadOnlySigner extends AccountMetaReadOnly {

  AccountMetaReadOnlySigner(final PublicKey publicKey) {
    super(publicKey);
  }

  @Override
  public boolean signer() {
    return true;
  }

  @Override
  public AccountMeta merge(final AccountMeta accountMeta) {
    if (accountMeta.feePayer()) {
      return accountMeta;
    }
    return accountMeta.write()
        ? accountMeta.signer() ? accountMeta : new AccountMetaSignerWriter(publicKey)
        : this;
  }

  @Override
  public boolean equals(final Object o) {
    return this == o || (o instanceof AccountMetaReadOnlySigner account && publicKey.equals(account.publicKey));
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result + 1; // signer
    result = 31 * result; // writer
    result = 31 * result; // invoked
    return result;
  }
}
