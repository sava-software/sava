package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.lang.System.Logger.Level.DEBUG;
import static software.sava.rpc.json.http.client.JsonResponseController.checkResponseCode;
import static software.sava.rpc.json.http.client.JsonResponseController.logBody;

public record KeepJsonResponseController<R>(
    BiFunction<byte[], JsonIterator, R> parser) implements Function<HttpResponse<byte[]>, R> {

  private static final System.Logger log = System.getLogger(KeepJsonResponseController.class.getName());

  @Override
  public R apply(final HttpResponse<byte[]> response) {
    checkResponseCode(response);
    try {
      final var body = response.body();
      log.log(DEBUG, body);
      final var ji = JsonIterator.parse(body);
      return parser.apply(body, ji);
    } catch (final RuntimeException ex) {
      logBody(response, ex);
      throw ex;
    }
  }
}
