package software.sava.rpc.json.http.response;

import software.sava.core.util.DecimalIntegerAmount;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TokenAmount(Context context, BigInteger amount, int decimals) implements DecimalIntegerAmount {

  public static TokenAmount parse(final JsonIterator ji, final Context context) {
    return ji.testObject(new Builder(context), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("amount", buf, offset, len)) {
      builder.amount = ji.readBigInteger();
    } else if (fieldEquals("decimals", buf, offset, len)) {
      builder.decimals = ji.readInt();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private BigInteger amount;
    private int decimals;

    private Builder(final Context context) {
      super(context);
    }

    private TokenAmount create() {
      return new TokenAmount(context, amount, decimals);
    }

  }
}
