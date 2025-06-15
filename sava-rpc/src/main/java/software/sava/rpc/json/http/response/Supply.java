package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Supply(Context context,
                     long total,
                     long circulating,
                     long nonCirculating,
                     List<String> nonCirculatingAccounts) {

  public static Supply parse(final JsonIterator ji, final Context context) {
    return ji.testObject(new Builder(context), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("total", buf, offset, len)) {
      builder.total(ji.readLong());
    } else if (fieldEquals("circulating", buf, offset, len)) {
      builder.circulating(ji.readLong());
    } else if (fieldEquals("nonCirculating", buf, offset, len)) {
      builder.nonCirculating(ji.readLong());
    } else if (fieldEquals("nonCirculatingAccounts", buf, offset, len)) {
      if (ji.readArray()) {
        final var accounts = new ArrayList<String>();
        do {
          accounts.add(ji.readString());
        } while (ji.readArray());
        builder.nonCirculatingAccounts(accounts);
      } else {
        builder.nonCirculatingAccounts(List.of());
      }
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private long total;
    private long circulating;
    private long nonCirculating;
    private List<String> nonCirculatingAccounts;

    private Builder(final Context context) {
      super(context);
    }

    private Supply create() {
      return new Supply(context, total, circulating, nonCirculating, nonCirculatingAccounts == null ? List.of() : nonCirculatingAccounts);
    }

    private void total(final long total) {
      this.total = total;
    }

    private void circulating(final long circulating) {
      this.circulating = circulating;
    }

    private void nonCirculating(final long nonCirculating) {
      this.nonCirculating = nonCirculating;
    }

    private void nonCirculatingAccounts(final List<String> nonCirculatingAccounts) {
      this.nonCirculatingAccounts = nonCirculatingAccounts;
    }
  }
}
