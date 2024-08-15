package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.programs.Discriminator;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public interface Instruction {

  static Instruction createInstruction(final AccountMeta programId,
                                       final List<AccountMeta> keys,
                                       final byte[] data, int offset, int len) {
    return new InstructionRecord(programId, keys, data, offset, len);
  }

  static Instruction createInstruction(final AccountMeta programId,
                                       final List<AccountMeta> keys,
                                       final byte[] data) {
    return createInstruction(programId, keys, data, 0, data.length);
  }

  static Instruction createInstruction(final AccountMeta programId,
                                       final List<AccountMeta> keys,
                                       final Discriminator discriminator) {
    return createInstruction(programId, keys, discriminator.data());
  }

  static Instruction createInstruction(final PublicKey programId,
                                       final List<AccountMeta> keys,
                                       final byte[] data, int offset, int len) {
    return createInstruction(AccountMeta.createInvoked(programId), keys, data, offset, len);
  }

  static Instruction createInstruction(final PublicKey programId,
                                       final List<AccountMeta> keys,
                                       final byte[] data) {
    return createInstruction(programId, keys, data, 0, data.length);
  }

  Instruction extraAccounts(final List<AccountMeta> accounts);

  Instruction extraAccount(final AccountMeta account);

  Instruction extraAccounts(final Collection<PublicKey> accounts, final Function<PublicKey, AccountMeta> metaFactory);

  Instruction extraAccount(final PublicKey account, final Function<PublicKey, AccountMeta> metaFactory);

  int serializedLength();

  int mergeAccounts(final Map<PublicKey, AccountMeta> accounts);

  int serialize(final byte[] out, int i, final AccountIndexLookupTableEntry[] accountIndexLookupTable);

  AccountMeta programId();

  List<AccountMeta> accounts();

  int[] discriminator(final int len);

  byte[] data();

  int offset();

  int len();
}
