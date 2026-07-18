package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.JsonIterator;

public record FeeForMessage(Context context, long fee) {

  public static FeeForMessage parse(final JsonIterator ji, final Context context) {
    return ji.readOrNull(j -> new FeeForMessage(context, j.readLong()));
  }
}
