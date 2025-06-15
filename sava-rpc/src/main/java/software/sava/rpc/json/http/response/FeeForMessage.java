package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

public record FeeForMessage(Context context, long fee) {

  public static FeeForMessage parse(final JsonIterator ji, final Context context) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      return new FeeForMessage(context, ji.readLong());
    }
  }
}
