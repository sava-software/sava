package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.CompactU16Encoding;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static software.sava.core.accounts.lookup.AccountIndexLookupTableEntry.lookupAccountIndexOrThrow;
import static software.sava.core.tx.Transaction.MERGE_ACCOUNT_META;
import static software.sava.core.encoding.CompactU16Encoding.getByteLen;

record InstructionRecord(AccountMeta programId,
                         List<AccountMeta> accounts,
                         byte[] data, int offset, int len) implements Instruction {

  @Override
  public Instruction extraAccounts(final List<AccountMeta> accounts) {
    final int numAccounts = accounts.size();
    if (numAccounts == 0) {
      return this;
    } else if (numAccounts == 1) {
      return extraAccount(accounts.getFirst());
    }
    final var joined = new AccountMeta[this.accounts.size() + numAccounts];
    int i = 0;
    for (final var account : this.accounts) {
      joined[i++] = account;
    }
    for (final var account : accounts) {
      joined[i++] = account;
    }
    return new InstructionRecord(programId, Arrays.asList(joined), data, offset, len);
  }

  @Override
  public Instruction extraAccount(final AccountMeta account) {
    if (account == null) {
      return this;
    }
    final var joined = new AccountMeta[this.accounts.size() + 1];
    int i = 0;
    for (final var a : this.accounts) {
      joined[i++] = a;
    }
    joined[i] = account;
    return new InstructionRecord(programId, Arrays.asList(joined), data, offset, len);
  }

  @Override
  public Instruction extraAccounts(final Collection<PublicKey> accounts, final Function<PublicKey, AccountMeta> metaFactory) {
    final int numAccounts = accounts.size();
    if (numAccounts == 0) {
      return this;
    }
    final var joined = new AccountMeta[this.accounts.size() + numAccounts];
    int i = 0;
    for (final var account : this.accounts) {
      joined[i++] = account;
    }
    for (final var account : accounts) {
      joined[i++] = metaFactory.apply(account);
    }
    return new InstructionRecord(programId, Arrays.asList(joined), data, offset, len);
  }

  @Override
  public Instruction extraAccount(final PublicKey account, final Function<PublicKey, AccountMeta> metaFactory) {
    if (account == null) {
      return this;
    }
    final var joined = new AccountMeta[this.accounts.size() + 1];
    int i = 0;
    for (final var a : this.accounts) {
      joined[i++] = a;
    }
    joined[i] = metaFactory.apply(account);
    return new InstructionRecord(programId, Arrays.asList(joined), data, offset, len);
  }

  @Override
  public int serializedLength() {
    final int numAccounts = accounts.size();
    return 1 // programId index
        + getByteLen(numAccounts) + numAccounts + getByteLen(len) + len;
  }

  @Override
  public int mergeAccounts(final Map<PublicKey, AccountMeta> accounts) {
    for (final var meta : this.accounts) {
      accounts.merge(meta.publicKey(), meta, MERGE_ACCOUNT_META);
    }
    accounts.merge(programId.publicKey(), programId, MERGE_ACCOUNT_META);
    return serializedLength();
  }

  @Override
  public int serialize(final byte[] out, int i, final AccountIndexLookupTableEntry[] accountIndexLookupTable) {
    out[i] = lookupAccountIndexOrThrow(accountIndexLookupTable, programId.publicKey());
    ++i;
    i += CompactU16Encoding.encodeLength(out, i, accounts.size());
    for (final var account : accounts) {
      out[i++] = lookupAccountIndexOrThrow(accountIndexLookupTable, account.publicKey());
    }
    i += CompactU16Encoding.encodeLength(out, i, len);
    System.arraycopy(data, offset, out, i, len);
    return i + len;
  }

  @Override
  public int[] discriminator(final int len) {
    final int[] discriminator = new int[len];
    for (int i = offset, d = 0; d < len; ++i, ++d) {
      discriminator[d] = data[i] & 0xFF;
    }
    return discriminator;
  }
}
