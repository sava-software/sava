package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.util.DecimalIntegerAmount;
import software.sava.core.util.LamportDecimal;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.math.BigInteger;
import java.util.*;
import java.util.function.BiFunction;

import static software.sava.rpc.json.http.response.JsonUtil.parseEncodedData;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record AccountInfo<T>(PublicKey pubKey,
                             Context context,
                             boolean executable,
                             long lamports,
                             PublicKey owner,
                             BigInteger rentEpoch,
                             int space,
                             T data) implements DecimalIntegerAmount {

  public static BiFunction<PublicKey, byte[], byte[]> BYTES_IDENTITY = (_, data) -> data;

  public static void cacheTables(final Collection<AccountInfo<AddressLookupTable>> tableAccounts,
                                 final Map<PublicKey, AddressLookupTable> cache) {
    for (final var tableAccount : tableAccounts) {
      cache.put(tableAccount.pubKey, tableAccount.data);
    }
  }

  @Override
  public int decimals() {
    return LamportDecimal.LAMPORT_DIGITS;
  }

  @Override
  public BigInteger amount() {
    return BigInteger.valueOf(lamports);
  }

  @Override
  public long asLong() {
    return lamports;
  }

  public static <T> AccountInfo<T> parse(final PublicKey publicKey,
                                         final JsonIterator ji,
                                         final Context context,
                                         final BiFunction<PublicKey, byte[], T> factory) {
    return ji.testObject(new Builder(context, publicKey), ACCOUNT_PARSER).create(factory);
  }

  public static <T> List<AccountInfo<T>> parseAccountsFromKeys(final SequencedCollection<PublicKey> pubKeys,
                                                               final JsonIterator ji,
                                                               final Context context,
                                                               final BiFunction<PublicKey, byte[], T> factory) {
    final var accounts = new ArrayList<AccountInfo<T>>(pubKeys.size());
    final var iterator = pubKeys.iterator();
    while (ji.readArray()) {
      final var key = iterator.next();
      if (ji.whatIsNext() == ValueType.OBJECT) {
        final var builder = new Builder(context, key);
        ji.testObject(builder, ACCOUNT_PARSER);
        accounts.add(builder.create(factory));
      } else {
        ji.skip();
      }
    }
    return accounts;
  }

  public static <T> AccountInfo<T> parseAccount(final JsonIterator ji,
                                                final Context context,
                                                final BiFunction<PublicKey, byte[], T> factory) {
    final var builder = new Builder(context);
    return ji.testObject(builder, PARSER).create(factory);
  }

  public static <T> List<AccountInfo<T>> parseAccounts(final JsonIterator ji,
                                                       final Context context,
                                                       final BiFunction<PublicKey, byte[], T> factory) {
    final var accounts = new ArrayList<AccountInfo<T>>();
    while (ji.readArray()) {
      final var builder = new Builder(context);
      ji.testObject(builder, PARSER);
      accounts.add(builder.create(factory));
    }
    return accounts;
  }

  private static final ContextFieldBufferPredicate<Builder> ACCOUNT_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("data", buf, offset, len)) {
      final var next = ji.whatIsNext();
      builder.data = parseEncodedData(ji, next);
    } else if (fieldEquals("executable", buf, offset, len)) {
      builder.executable = ji.readBoolean();
    } else if (fieldEquals("lamports", buf, offset, len)) {
      builder.lamports = ji.readLong();
    } else if (fieldEquals("owner", buf, offset, len)) {
      builder.owner = PublicKeyEncoding.parseBase58Encoded(ji);
    } else if (fieldEquals("rentEpoch", buf, offset, len)) {
      builder.rentEpoch = ji.readBigInteger();
    } else if (fieldEquals("space", buf, offset, len)) {
      builder.space = ji.readInt();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("account", buf, offset, len)) {
      ji.testObject(builder, ACCOUNT_PARSER);
    } else if (fieldEquals("pubkey", buf, offset, len)) {
      builder.pubKey = PublicKeyEncoding.parseBase58Encoded(ji);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private PublicKey pubKey;
    private boolean executable;
    private long lamports;
    private PublicKey owner;
    private BigInteger rentEpoch;
    private int space;
    private byte[] data;

    private Builder(final Context context) {
      super(context);
    }

    private Builder(final Context context, final PublicKey pubKey) {
      super(context);
      this.pubKey = pubKey;
    }

    private <T> AccountInfo<T> create(final BiFunction<PublicKey, byte[], T> factory) {
      return new AccountInfo<>(pubKey, context, executable, lamports, owner, rentEpoch, space, factory.apply(pubKey, data));
    }
  }
}
