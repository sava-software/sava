package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.util.DecimalIntegerAmount;
import software.sava.core.util.LamportDecimal;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.FieldBufferPredicate;
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

  public static BiFunction<PublicKey, byte[], byte[]> BYTES_IDENTITY = (k, data) -> data;

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
    final var parser = new Parser(context, publicKey);
    ji.testObject(parser);
    return parser.create(factory);
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
        final var parser = new Parser(context, key);
        ji.testObject(parser);
        accounts.add(parser.create(factory));
      } else {
        ji.skip();
      }
    }
    return accounts;
  }

  public static <T> List<AccountInfo<T>> parseAccountsFromKeysWithNulls(final SequencedCollection<PublicKey> pubKeys,
                                                                        final JsonIterator ji,
                                                                        final Context context,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    final var accounts = new ArrayList<AccountInfo<T>>(pubKeys.size());
    final var iterator = pubKeys.iterator();
    while (ji.readArray()) {
      final var key = iterator.next();
      if (ji.whatIsNext() == ValueType.OBJECT) {
        final var parser = new Parser(context, key);
        ji.testObject(parser);
        accounts.add(parser.create(factory));
      } else {
        ji.skip();
        accounts.add(null);
      }
    }
    return accounts;
  }

  public static <T> AccountInfo<T> parseAccount(final JsonIterator ji,
                                                final Context context,
                                                final BiFunction<PublicKey, byte[], T> factory) {
    final var parser = new Parser(context);
    return ji.testObject(parser, PARSER).create(factory);
  }

  public static <T> List<AccountInfo<T>> parseAccounts(final JsonIterator ji,
                                                       final Context context,
                                                       final BiFunction<PublicKey, byte[], T> factory) {
    final var accounts = new ArrayList<AccountInfo<T>>();
    while (ji.readArray()) {
      final var parser = new Parser(context);
      ji.testObject(parser, PARSER);
      accounts.add(parser.create(factory));
    }
    return accounts;
  }

  private static final ContextFieldBufferPredicate<Parser> PARSER = (parser, buf, offset, len, ji) -> {
    if (fieldEquals("account", buf, offset, len)) {
      ji.testObject(parser);
    } else if (fieldEquals("pubkey", buf, offset, len)) {
      parser.pubKey = PublicKeyEncoding.parseBase58Encoded(ji);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Parser extends RootBuilder implements FieldBufferPredicate {

    private PublicKey pubKey;
    private boolean executable;
    private long lamports;
    private PublicKey owner;
    private BigInteger rentEpoch;
    private int space;
    private byte[] data;

    private Parser(final Context context) {
      super(context);
    }

    private Parser(final Context context, final PublicKey pubKey) {
      super(context);
      this.pubKey = pubKey;
    }

    private <T> AccountInfo<T> create(final BiFunction<PublicKey, byte[], T> factory) {
      return new AccountInfo<>(pubKey, context, executable, lamports, owner, rentEpoch, space, factory.apply(pubKey, data));
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("data", buf, offset, len)) {
        final var next = ji.whatIsNext();
        data = parseEncodedData(ji, next);
      } else if (fieldEquals("executable", buf, offset, len)) {
        executable = ji.readBoolean();
      } else if (fieldEquals("lamports", buf, offset, len)) {
        lamports = ji.readLong();
      } else if (fieldEquals("owner", buf, offset, len)) {
        owner = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("rentEpoch", buf, offset, len)) {
        rentEpoch = ji.readBigInteger();
      } else if (fieldEquals("space", buf, offset, len)) {
        space = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
