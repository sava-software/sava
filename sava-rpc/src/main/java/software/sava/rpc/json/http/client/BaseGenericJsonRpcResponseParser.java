package software.sava.rpc.json.http.client;

import java.net.http.HttpResponse;
import java.util.function.Function;

abstract class BaseGenericJsonRpcResponseParser<R> extends BaseJsonRpcResponseParser<R> implements Function<HttpResponse<?>, R> {

  @Override
  public final R apply(final HttpResponse<?> httpResponse) {
    return super.applyResponse(httpResponse);
  }
}
