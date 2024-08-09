package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.lang.System.Logger.Level.DEBUG;
import static software.sava.rpc.json.http.client.JsonResponseController.checkStringResponseCode;
import static software.sava.rpc.json.http.client.JsonResponseController.logStringBody;

record KeepJsonStringResponseController<R>(
    BiFunction<String, JsonIterator, R> parser) implements Function<HttpResponse<String>, R> {

  private static final System.Logger log = System.getLogger(KeepJsonStringResponseController.class.getName());

  @Override
  public R apply(final HttpResponse<String> stringHttpResponse) {
    checkStringResponseCode(stringHttpResponse);
    try {
      final var body = stringHttpResponse.body();
      log.log(DEBUG, body);
      final var ji = JsonIterator.parse(body);
      return parser.apply(body, ji);
    } catch (final RuntimeException ex) {
      logStringBody(stringHttpResponse, ex);
      throw ex;
    }
  }
}
