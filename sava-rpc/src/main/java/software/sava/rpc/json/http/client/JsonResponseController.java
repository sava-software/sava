package software.sava.rpc.json.http.client;

import java.net.http.HttpResponse;
import java.util.function.Function;

abstract class JsonResponseController<B, R> extends BaseJsonResponseController<R> implements Function<HttpResponse<B>, R> {

  @Override
  public final R apply(final HttpResponse<B> httpResponse) {
    return applyResponse(httpResponse);
  }
}
