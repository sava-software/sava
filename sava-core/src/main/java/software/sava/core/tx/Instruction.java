package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.programs.Discriminator;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/// For valid data spans, built-in instructions compare and hash their program, accounts, and data by value.
/// Keep retained accounts, public keys, and data unchanged while using an instruction as a hash-based
/// collection key.
public interface Instruction {

  /// Retains the supplied array and span without validation. Diagnostic `toString()` renders
  /// empty data when `data == null` or `len <= 0`, without reading the span. For a positive
  /// length, it uses {@link java.util.Arrays#copyOfRange(byte[], int, int) Arrays.copyOfRange}, which can zero-pad
  /// beyond the array's end. Diagnostic rendering does not validate the span for serialization.
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

  /// Appends the supplied accounts. The built-in implementation ignores a singleton null entry,
  /// as [#extraAccount(AccountMeta)] does, but retains null entries in larger lists. This existing
  /// size-dependent behavior is preserved for compatibility; callers should supply non-null metas.
  Instruction extraAccounts(final List<AccountMeta> accounts);

  Instruction extraAccount(final AccountMeta account);

  Instruction extraAccounts(final Collection<PublicKey> accounts, final Function<PublicKey, AccountMeta> metaFactory);

  Instruction extraAccount(final PublicKey account, final Function<PublicKey, AccountMeta> metaFactory);

  int serializedLength();

  int mergeAccounts(final Map<PublicKey, AccountMeta> accounts);

  int serialize(final byte[] out, int i, final AccountIndexLookupTableEntry[] accountIndexLookupTable);

  int serialize(final byte[] out, int i, final Map<PublicKey, Integer> accountIndexLookupTable);

  AccountMeta programId();

  List<AccountMeta> accounts();

  int[] discriminator(final int len);

  Discriminator wrapDiscriminator(final int len);

  boolean beginsWith(final byte[] data);

  byte[] data();

  byte[] copyData();

  int offset();

  int len();
}
