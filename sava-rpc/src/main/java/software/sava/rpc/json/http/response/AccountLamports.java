package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record AccountLamports(Context context, String address, long lamports) {

  public static List<AccountLamports> parseAccounts(final JsonIterator ji, final Context context) {
    final var accounts = new ArrayList<AccountLamports>();
    while (ji.readArray()) {
      accounts.add(ji.testObject(new Builder(context), PARSER).create());
    }
    return accounts;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("lamports", buf, offset, len)) {
      builder.lamports(ji.readLong());
    } else if (fieldEquals("address", buf, offset, len)) {
      builder.address(ji.readString());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private long lamports;
    private String address;

    private Builder(final Context context) {
      super(context);
    }

    private AccountLamports create() {
      return new AccountLamports(context, address, lamports);
    }

    private void lamports(final long lamports) {
      this.lamports = lamports;
    }

    private void address(final String address) {
      this.address = address;
    }
  }
}
