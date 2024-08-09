package software.sava.rpc.json.http.ws;

import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

record SubConfirmation(long subId, long msgId, JsonRpcException jsonRpcException) {

  public static SubConfirmation parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("result", buf, offset, len)) {
      if (ji.whatIsNext() == ValueType.BOOLEAN) {
        builder.subId = Long.MIN_VALUE;
        ji.skip();
      } else {
        builder.subId = ji.readLong();
      }
    } else if (fieldEquals("id", buf, offset, len)) {
      builder.msgId = ji.readLong();
    } else if (fieldEquals("error", buf, offset, len)) {
      builder.jsonRpcException = JsonRpcException.parseException(ji, OptionalLong.empty());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long subId;
    private long msgId;
    private JsonRpcException jsonRpcException;

    private Builder() {
    }

    SubConfirmation create() {
      return new SubConfirmation(subId, msgId, jsonRpcException);
    }
  }
}
