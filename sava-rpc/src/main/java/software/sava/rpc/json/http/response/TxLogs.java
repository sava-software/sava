package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxLogs(Context context, String signature, TransactionError error, List<String> logs) {

  public static TxLogs parse(final JsonIterator ji, final Context context) {
    final var parser = new Parser(context);
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser extends RootBuilder implements FieldBufferPredicate {

    private String signature;
    private TransactionError error;
    private List<String> logs;

    private Parser(final Context context) {
      super(context);
    }

    private TxLogs create() {
      return new TxLogs(context, signature, error, logs);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("signature", buf, offset, len)) {
        signature = ji.readString();
      } else if (fieldEquals("err", buf, offset, len)) {
        error = TransactionError.parseError(ji);
      } else if (fieldEquals("logs", buf, offset, len)) {
        final var logs = new ArrayList<String>();
        while (ji.readArray()) {
          logs.add(ji.readString());
        }
        this.logs = logs;
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
