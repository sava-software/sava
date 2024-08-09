package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static software.sava.rpc.json.http.client.JsonResponseController.log;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;
import static systems.comodal.jsoniter.ValueType.NULL;

public final class JsonRpcException extends RuntimeException {

  private final int code;
  private final OptionalLong retryAfterSeconds;
  private final long numSlotsBehind;
  private final List<String> logs;

  private JsonRpcException(final int code,
                           final String message,
                           final OptionalLong retryAfterSeconds,
                           final long numSlotsBehind,
                           final List<String> logs) {
    super(message);
    this.code = code;
    this.retryAfterSeconds = retryAfterSeconds;
    this.numSlotsBehind = numSlotsBehind;
    this.logs = logs;
  }

  public static JsonRpcException parseException(final JsonIterator ji,
                                                final OptionalLong retryAfterSeconds) {
    return ji.testObject(new Builder(), PARSER).create(retryAfterSeconds);
  }

  public int code() {
    return code;
  }

  public OptionalLong retryAfterSeconds() {
    return retryAfterSeconds;
  }

  public List<String> logs() {
    return logs;
  }

  public long numSlotsBehind() {
    return numSlotsBehind;
  }

  private static final ContextFieldBufferPredicate<Builder> DATA_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("logs", buf, offset, len)) {
      if (ji.whatIsNext() == ValueType.ARRAY) {
        builder.logs = new ArrayList<>();
        while (ji.readArray()) {
          builder.logs.add(ji.readString());
        }
      } else {
        ji.skip();
      }
    } else if (fieldEquals("numSlotsBehind", buf, offset, len)) {
      if (ji.whatIsNext() == NULL) {
        ji.skip();
      } else {
        final var numSlotsBehind = ji.readNumberOrNumberString();
        if (numSlotsBehind != null && !numSlotsBehind.isBlank()) {
          try {
            builder.numSlotsBehind = Long.parseLong(numSlotsBehind);
          } catch (final RuntimeException ex) {
            log.log(System.Logger.Level.WARNING, "Failed to parse numSlotsBehind field: " + numSlotsBehind);
          }
        }
      }
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
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

  private static final class Builder {

    private static final List<String> NO_LOGS = List.of();

    private int code;
    private String message;
    private List<String> logs;
    private long numSlotsBehind = Integer.MIN_VALUE;

    private Builder() {
    }

    private JsonRpcException create(final OptionalLong retryAfterSeconds) {
      return new JsonRpcException(
          code,
          message,
          retryAfterSeconds,
          numSlotsBehind,
          logs == null || logs.isEmpty() ? NO_LOGS : logs
      );
    }

    private void code(final int code) {
      this.code = code;
    }

    private void message(final String message) {
      this.message = message;
    }
  }
}
