package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.core.util.DecimalIntegerAmount;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record AccountTokenAmount(Context context,
                                 PublicKey addressKey,
                                 BigInteger amount,
                                 int decimals) implements DecimalIntegerAmount {

  @Deprecated
  public String address() {
    return addressKey.toString();
  }

  public static List<AccountTokenAmount> parse(final JsonIterator ji, final Context context) {
    final var accounts = new ArrayList<AccountTokenAmount>();
    while (ji.readArray()) {
      final var parser = new Parser(context);
      ji.testObject(parser);
      accounts.add(parser.create());
    }
    return accounts;
  }

  private static final class Parser extends RootBuilder implements FieldBufferPredicate {

    private PublicKey address;
    private BigInteger amount;
    private int decimals;

    private Parser(final Context context) {
      super(context);
    }

    private AccountTokenAmount create() {
      return new AccountTokenAmount(context, address, amount, decimals);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("address", buf, offset, len)) {
        address = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("amount", buf, offset, len)) {
        amount = ji.readBigInteger();
      } else if (fieldEquals("decimals", buf, offset, len)) {
        decimals = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
