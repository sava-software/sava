package software.sava.core.accounts.meta;

import software.sava.core.accounts.PublicKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

public sealed interface AccountMeta permits AccountMetaReadOnly {

  IntFunction<AccountMeta[]> ACCOUNT_META_ARRAY_GENERATOR = AccountMeta[]::new;
  List<AccountMeta> NO_KEYS = List.of();
  Function<PublicKey, AccountMeta> CREATE_INVOKED = AccountMeta::createInvoked;
  Function<PublicKey, AccountMeta> CREATE_READ = AccountMeta::createRead;
  Function<PublicKey, AccountMeta> CREATE_WRITE = AccountMeta::createWrite;
  Function<PublicKey, AccountMeta> CREATE_READ_ONLY_SIGNER = AccountMeta::createReadOnlySigner;
  Function<PublicKey, AccountMeta> CREATE_WRITE_SIGNER = AccountMeta::createWritableSigner;
  Function<PublicKey, AccountMeta> CREATE_FEE_PAYER = AccountMeta::createFeePayer;

  static AccountMeta createInvoked(final PublicKey publicKey) {
    Objects.requireNonNull(publicKey);
    return new AccountMetaInvoked(publicKey);
  }

  static AccountMeta createRead(final PublicKey publicKey) {
    Objects.requireNonNull(publicKey);
    return new AccountMetaReadOnly(publicKey);
  }

  static AccountMeta createWrite(final PublicKey publicKey) {
    Objects.requireNonNull(publicKey);
    return new AccountMetaWrite(publicKey);
  }

  static AccountMeta createReadOnlySigner(final PublicKey publicKey) {
    Objects.requireNonNull(publicKey);
    return new AccountMetaReadOnlySigner(publicKey);
  }

  static AccountMeta createWritableSigner(final PublicKey publicKey) {
    Objects.requireNonNull(publicKey);
    return new AccountMetaSignerWriter(publicKey);
  }

  static AccountMeta createFeePayer(final PublicKey publicKey) {
    Objects.requireNonNull(publicKey);
    return new AccountMetaFeePayer(publicKey);
  }

  static Map<PublicKey, AccountMeta> createAccountsMap(final int numAccounts, final PublicKey feePayer) {
    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(numAccounts);
    final var feePayerMeta = createFeePayer(feePayer);
    accounts.put(feePayer, feePayerMeta);
    return accounts;
  }

  PublicKey publicKey();

  boolean signer();

  boolean write();

  boolean feePayer();

  boolean invoked();

  AccountMeta merge(final AccountMeta accountMeta);
}
