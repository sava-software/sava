package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxLogs(Context context, String signature, TransactionError error, List<String> logs) {

  public static TxLogs parse(final JsonIterator ji, final Context context) {
    return ji.testObject(new Builder(context), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("signature", buf, offset, len)) {
      builder.signature(ji.readString());
    } else if (fieldEquals("err", buf, offset, len)) {
      builder.error(TransactionError.parseError(ji));
    } else if (fieldEquals("logs", buf, offset, len)) {
      final var logs = new ArrayList<String>();
      while (ji.readArray()) {
        logs.add(ji.readString());
      }
      builder.logs(logs);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private String signature;
    private TransactionError error;
    private List<String> logs;

    private Builder(final Context context) {
      super(context);
    }

    private TxLogs create() {
      return new TxLogs(context, signature, error, logs);
    }

    private void signature(final String signature) {
      this.signature = signature;
    }

    private void error(final TransactionError error) {
      this.error = error;
    }

    private void logs(final List<String> logs) {
      this.logs = logs;
    }
  }
}
