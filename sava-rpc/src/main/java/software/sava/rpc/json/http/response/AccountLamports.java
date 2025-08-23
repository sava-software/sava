package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record AccountLamports(Context context, PublicKey addressKey, long lamports) {

  @Deprecated
  public String address() {
    return addressKey.toString();
  }

  public static List<AccountLamports> parseAccounts(final JsonIterator ji, final Context context) {
    final var accounts = new ArrayList<AccountLamports>();
    while (ji.readArray()) {
      final var parser = new Parser(context);
      ji.testObject(parser);
      accounts.add(parser.create());
    }
    return accounts;
  }

  private static final class Parser extends RootBuilder implements FieldBufferPredicate {

    private long lamports;
    private PublicKey address;

    private Parser(final Context context) {
      super(context);
    }

    private AccountLamports create() {
      return new AccountLamports(context, address, lamports);
    }
    
    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("lamports", buf, offset, len)) {
        lamports = ji.readLong();
      } else if (fieldEquals("address", buf, offset, len)) {
        address = PublicKeyEncoding.parseBase58Encoded(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
