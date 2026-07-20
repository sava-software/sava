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
    // an account that is written by one instruction and invoked as the program of
    // another arrives here, because InstructionRecord merges an instruction's
    // accounts before its program id. Dropping invoked would let the program be
    // moved into an address lookup table, which the runtime rejects.
    if (accountMeta.invoked()) {
      return accountMeta.write() ? accountMeta : new AccountMetaInvokedAndWrite(publicKey);
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