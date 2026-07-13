package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.Context;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.BiFunction;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

final class JsonRpcValueResponseParser<R> extends BaseGenericJsonRpcResponseParser<R> {

  private final BiFunction<JsonIterator, Context, R> parser;

  JsonRpcValueResponseParser(final BiFunction<JsonIterator, Context, R> parser) {
    this.parser = parser;
  }

  @Override
  protected R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji) {
    final var resultParser = new Parser<>(parser);
    ji.testObject(resultParser);
    return resultParser.parse(ji);
  }

  private static final class Parser<R> implements FieldBufferPredicate {

    private final BiFunction<JsonIterator, Context, R> parser;

    private Context context;
    private R result;
    private boolean parsedValue;
    private int valueMark = -1;

    private Parser(final BiFunction<JsonIterator, Context, R> parser) {
      this.parser = parser;
    }

    R parse(final JsonIterator ji) {
      if (parsedValue) {
        return result;
      } else {
        // If the value preceded the context, it is parsed from its mark, otherwise it is absent, and
        // the parser is applied to the exhausted result, leaving it to return null or a default.
        return parser.apply(valueMark < 0 ? ji : ji.reset(valueMark), context);
      }
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("value", buf, offset, len)) {
        if (context == null) {
          this.valueMark = ji.mark();
          ji.skip();
        } else {
          this.result = parser.apply(ji, context);
          this.parsedValue = true;
        }
      } else if (fieldEquals("context", buf, offset, len)) {
        this.context = Context.parse(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
