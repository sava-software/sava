package software.sava.rpc.json.http.response;

import software.sava.core.util.DecimalIntegerAmount;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record AccountTokenAmount(Context context,
                                 String address,
                                 BigInteger amount,
                                 int decimals) implements DecimalIntegerAmount {

  public static List<AccountTokenAmount> parse(final JsonIterator ji, final Context context) {
    final var accounts = new ArrayList<AccountTokenAmount>();
    while (ji.readArray()) {
      accounts.add(ji.testObject(new Builder(context), PARSER).create());
    }
    return accounts;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("address", buf, offset, len)) {
      builder.address(ji.readString());
    } else if (fieldEquals("amount", buf, offset, len)) {
      builder.amount(ji.readBigInteger());
    } else if (fieldEquals("decimals", buf, offset, len)) {
      builder.decimals(ji.readInt());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private String address;
    private BigInteger amount;
    private int decimals;

    private Builder(final Context context) {
      super(context);
    }

    private AccountTokenAmount create() {
      return new AccountTokenAmount(context, address, amount, decimals);
    }

    private void address(final String address) {
      this.address = address;
    }

    private void amount(final BigInteger amount) {
      this.amount = amount;
    }

    private void decimals(final int decimals) {
      this.decimals = decimals;
    }
  }
}
