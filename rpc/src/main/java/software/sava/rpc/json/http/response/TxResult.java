package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxResult(Context context, String value, TxInstructionError error) {

  public static TxResult parseResult(final JsonIterator ji, final Context context) {
    return switch (ji.whatIsNext()) {
      case INVALID, NULL, ARRAY -> {
        ji.skip();
        yield null;
      }
      case STRING -> new TxResult(context, ji.readString(), null);
      case NUMBER -> new TxResult(context, ji.readNumberAsString(), null);
      case BOOLEAN -> new TxResult(context, ji.readBoolean() ? "true" : "false", null);
      case OBJECT -> ji.testObject(new Builder(context), PARSER).create();
    };
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("err", buf, offset, len)) {
      builder.error(TxInstructionError.parseError(ji));
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private TxInstructionError error;

    private Builder(final Context context) {
      super(context);
    }

    private TxResult create() {
      return new TxResult(context, null, error);
    }

    private void error(final TxInstructionError error) {
      this.error = error;
    }
  }
}
