package software.sava.core.accounts.meta;

import software.sava.core.accounts.PublicKey;

public final class AccountMetaInvoked extends AccountMetaReadOnly {

  AccountMetaInvoked(final PublicKey publicKey) {
    super(publicKey);
  }

  @Override
  public boolean invoked() {
    return true;
  }

  @Override
  public AccountMeta merge(final AccountMeta accountMeta) {
    if (accountMeta.write()) {
      return accountMeta.invoked() ? accountMeta : new AccountMetaInvokedAndWrite(publicKey);
    } else {
      return this;
    }
  }

  @Override
  public boolean equals(final Object o) {
    return this == o || (o instanceof AccountMetaInvoked account && publicKey.equals(account.publicKey));
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result; // signer
    result = 31 * result; // writer
    result = 31 * result + 1; // invoked
    return result;
  }
}