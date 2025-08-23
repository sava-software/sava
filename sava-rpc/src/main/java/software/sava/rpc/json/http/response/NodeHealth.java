package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record NodeHealth(int code, String message, int numSlotsBehind) {

  public static NodeHealth parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final ContextFieldBufferPredicate<Parser> DATA_PARSER = (parser, buf, offset, len, ji) -> {
    if (fieldEquals("numSlotsBehind", buf, offset, len)) {
      if (ji.whatIsNext() == ValueType.NUMBER) {
        parser.numSlotsBehind = ji.readInt();
      } else {
        ji.skip();
      }
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Parser> ERROR_PARSER = (parser, buf, offset, len, ji) -> {
    if (fieldEquals("code", buf, offset, len)) {
      parser.code = ji.readInt();
    } else if (fieldEquals("message", buf, offset, len)) {
      parser.message = ji.readString();
    } else if (fieldEquals("data", buf, offset, len)) {
      ji.testObject(parser, DATA_PARSER);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Parser implements FieldBufferPredicate {

    private int code;
    private String message;
    private int numSlotsBehind;

    private Parser() {
    }

    private NodeHealth create() {
      return new NodeHealth(code, message, numSlotsBehind);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("result", buf, offset, len)) {
        code = 200;
        message = ji.readString();
      } else if (fieldEquals("error", buf, offset, len)) {
        ji.testObject(this, ERROR_PARSER);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
