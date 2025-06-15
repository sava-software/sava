package software.sava.core.accounts.meta;

import software.sava.core.accounts.PublicKey;

public final class AccountMetaInvokedAndWrite extends AccountMetaReadOnly {

  AccountMetaInvokedAndWrite(final PublicKey publicKey) {
    super(publicKey);
  }

  @Override
  public boolean write() {
    return true;
  }

  @Override
  public boolean invoked() {
    return true;
  }

  @Override
  public AccountMeta merge(final AccountMeta accountMeta) {
    return this;
  }

  @Override
  public boolean equals(final Object o) {
    return this == o || (o instanceof AccountMetaInvokedAndWrite account && publicKey.equals(account.publicKey));
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result; // signer
    result = 31 * result + 1; // writer
    result = 31 * result + 1; // invoked
    return result;
  }
}