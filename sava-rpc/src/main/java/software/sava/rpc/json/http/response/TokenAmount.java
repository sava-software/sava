package software.sava.rpc.json.http.response;

import software.sava.core.util.DecimalIntegerAmount;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TokenAmount(Context context, BigInteger amount, int decimals) implements DecimalIntegerAmount {

  public static TokenAmount parse(final JsonIterator ji, final Context context) {
    final var parser = new Parser(context);
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser extends RootBuilder implements FieldBufferPredicate {

    private BigInteger amount;
    private int decimals;

    private Parser(final Context context) {
      super(context);
    }

    private TokenAmount create() {
      return new TokenAmount(context, amount, decimals);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("amount", buf, offset, len)) {
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
