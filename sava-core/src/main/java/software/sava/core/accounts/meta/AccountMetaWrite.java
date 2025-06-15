package software.sava.core.accounts.meta;

import software.sava.core.accounts.PublicKey;

final class AccountMetaWrite extends AccountMetaReadOnly {

  AccountMetaWrite(final PublicKey publicKey) {
    super(publicKey);
  }

  @Override
  public boolean write() {
    return true;
  }

  @Override
  public AccountMeta merge(final AccountMeta accountMeta) {
    if (accountMeta.feePayer()) {
      return accountMeta;
    }
    if (accountMeta.signer()) {
      return accountMeta.write() ? accountMeta : new AccountMetaSignerWriter(publicKey);
    } else {
      return this;
    }
  }

  @Override
  public boolean equals(final Object o) {
    return this == o || (o instanceof AccountMetaWrite account && publicKey.equals(account.publicKey));
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result; // signer
    result = 31 * result + 1; // writer
    result = 31 * result; // invoked
    return result;
  }
}