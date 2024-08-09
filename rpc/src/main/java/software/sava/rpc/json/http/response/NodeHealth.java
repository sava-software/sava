package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record NodeHealth(int code, String message, int numSlotsBehind) {

  public static NodeHealth parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> DATA_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("numSlotsBehind", buf, offset, len)) {
      if (ji.whatIsNext() == ValueType.NUMBER) {
        builder.numSlotsBehind(ji.readInt());
      } else {
        ji.skip();
      }
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> ERROR_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("code", buf, offset, len)) {
      builder.code(ji.readInt());
    } else if (fieldEquals("message", buf, offset, len)) {
      builder.message(ji.readString());
    } else if (fieldEquals("data", buf, offset, len)) {
      ji.testObject(builder, DATA_PARSER);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("result", buf, offset, len)) {
      builder.code(200);
      builder.message(ji.readString());
    } else if (fieldEquals("error", buf, offset, len)) {
      ji.testObject(builder, ERROR_PARSER);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private int code;
    private String message;
    private int numSlotsBehind;

    private Builder() {
    }

    private NodeHealth create() {
      return new NodeHealth(code, message, numSlotsBehind);
    }

    private void code(final int code) {
      this.code = code;
    }

    private void message(final String message) {
      this.message = message;
    }

    private void numSlotsBehind(final int numSlotsBehind) {
      this.numSlotsBehind = numSlotsBehind;
    }
  }
}
