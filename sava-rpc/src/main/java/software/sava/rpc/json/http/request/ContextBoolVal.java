package software.sava.rpc.json.http.request;

import software.sava.rpc.json.http.response.Context;
import systems.comodal.jsoniter.JsonIterator;

public record ContextBoolVal(Context context, boolean bool) {

  public static ContextBoolVal parse(final JsonIterator ji, Context context) {
    return new ContextBoolVal(context, ji.readBoolean());
  }
}
