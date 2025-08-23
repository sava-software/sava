package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxResult(Context context, String value, TransactionError error) {

  public static TxResult parseResult(final JsonIterator ji, final Context context) {
    return switch (ji.whatIsNext()) {
      case INVALID, NULL, ARRAY -> {
        ji.skip();
        yield null;
      }
      case STRING -> new TxResult(context, ji.readString(), null);
      case NUMBER -> new TxResult(context, ji.readNumberAsString(), null);
      case BOOLEAN -> new TxResult(context, ji.readBoolean() ? "true" : "false", null);
      case OBJECT -> {
        final var parser = new Parser(context);
        ji.testObject(parser);
        yield parser.create();
      }
    };
  }

  private static final class Parser extends RootBuilder implements FieldBufferPredicate {

    private TransactionError error;

    private Parser(final Context context) {
      super(context);
    }

    private TxResult create() {
      return new TxResult(context, null, error);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("err", buf, offset, len)) {
        error = TransactionError.parseError(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
