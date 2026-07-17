package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record Supply(Context context,
                     long total,
                     long circulating,
                     long nonCirculating,
                     List<PublicKey> nonCirculatingAccountKeys) {

  public static Supply parse(final JsonIterator ji, final Context context) {
    return ji.parseObject(Parser.FIELDS, new Parser(context));
  }

  private static final class Parser extends RootBuilder implements FieldIndexPredicate, Supplier<Supply> {

    private long total;
    private long circulating;
    private long nonCirculating;
    private List<PublicKey> nonCirculatingAccounts;

    private Parser(final Context context) {
      super(context);
    }

    @Override
    public Supply get() {
      return new Supply(context, total, circulating, nonCirculating,
          nonCirculatingAccounts == null ? List.of() : nonCirculatingAccounts
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "total",
        "circulating",
        "nonCirculating",
        "nonCirculatingAccounts"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> total = ji.readLong();
        case 1 -> circulating = ji.readLong();
        case 2 -> nonCirculating = ji.readLong();
        case 3 -> {
          if (ji.readArray()) {
            final var accounts = new ArrayList<PublicKey>();
            do {
              accounts.add(PublicKeyEncoding.parseBase58Encoded(ji));
            } while (ji.readArray());
            nonCirculatingAccounts = accounts;
          } else {
            nonCirculatingAccounts = List.of();
          }
        }
        default -> ji.skip();
      }
      return true;
    }
  }
}
