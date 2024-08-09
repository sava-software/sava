package software.sava.core.accounts.meta;

import software.sava.core.accounts.PublicKey;

final class AccountMetaSignerWriter extends AccountMetaReadOnly {

  AccountMetaSignerWriter(final PublicKey publicKey) {
    super(publicKey);
  }

  @Override
  public boolean signer() {
    return true;
  }

  @Override
  public boolean write() {
    return true;
  }

  @Override
  public AccountMeta merge(final AccountMeta accountMeta) {
    return accountMeta.feePayer() ? accountMeta : this;
  }

  @Override
  public boolean equals(final Object o) {
    return this == o || (o instanceof AccountMetaSignerWriter account && publicKey.equals(account.publicKey));
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result + 1; // signer
    result = 31 * result + 1; // writer
    result = 31 * result; // invoked
    return result;
  }
}
