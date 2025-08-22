package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Supply(Context context,
                     long total,
                     long circulating,
                     long nonCirculating,
                     List<PublicKey> nonCirculatingAccountKeys) {

  @Deprecated
  public List<String> nonCirculatingAccounts() {
    return nonCirculatingAccountKeys.stream()
        .map(PublicKey::toBase58)
        .toList();
  }

  public static Supply parse(final JsonIterator ji, final Context context) {
    final var parser = new Parser(context);
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser extends RootBuilder implements FieldBufferPredicate {

    private long total;
    private long circulating;
    private long nonCirculating;
    private List<PublicKey> nonCirculatingAccounts;

    private Parser(final Context context) {
      super(context);
    }

    private Supply create() {
      return new Supply(context, total, circulating, nonCirculating,
          nonCirculatingAccounts == null ? List.of() : nonCirculatingAccounts
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("total", buf, offset, len)) {
        total = ji.readLong();
      } else if (fieldEquals("circulating", buf, offset, len)) {
        circulating = ji.readLong();
      } else if (fieldEquals("nonCirculating", buf, offset, len)) {
        nonCirculating = ji.readLong();
      } else if (fieldEquals("nonCirculatingAccounts", buf, offset, len)) {
        if (ji.readArray()) {
          final var accounts = new ArrayList<PublicKey>();
          do {
            accounts.add(PublicKeyEncoding.parseBase58Encoded(ji));
          } while (ji.readArray());
          nonCirculatingAccounts = accounts;
        } else {
          nonCirculatingAccounts = List.of();
        }
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
