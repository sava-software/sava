package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

import static software.sava.rpc.json.http.response.TxSimulation.NO_LOGS;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class JsonRpcException extends RuntimeException {

  private final OptionalLong retryAfterSeconds;
  private final long code;
  private final RpcCustomError customError;

  private JsonRpcException(final long code,
                           final String message,
                           final OptionalLong retryAfterSeconds,
                           final RpcCustomError customError) {
    super(message);
    this.code = code;
    this.retryAfterSeconds = Objects.requireNonNullElse(retryAfterSeconds, OptionalLong.empty());
    this.customError = customError;
  }

  public static JsonRpcException parseException(final JsonIterator ji, final OptionalLong retryAfterSeconds) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create(retryAfterSeconds);
  }

  public long code() {
    return code;
  }

  public OptionalLong retryAfterSeconds() {
    return retryAfterSeconds;
  }

  public RpcCustomError customError() {
    return customError;
  }

  @Deprecated
  public List<String> logs() {
    return customError instanceof RpcCustomError.SendTransactionPreflightFailure(final TxSimulation simulation)
        ? simulation.logs()
        : NO_LOGS;
  }

  @Deprecated
  public long numSlotsBehind() {
    return customError instanceof RpcCustomError.NodeUnhealthy(final OptionalLong numSlotsBehind)
        ? numSlotsBehind.orElse(Integer.MIN_VALUE)
        : Integer.MIN_VALUE;
  }

  private static final class Parser implements FieldBufferPredicate {

    private long code;
    private String message;
    private RpcCustomError customError;

    private Parser() {
    }

    private JsonRpcException create(final OptionalLong retryAfterSeconds) {
      return new JsonRpcException(
          code,
          message,
          retryAfterSeconds,
          customError == null ? RpcCustomError.parseError(code) : customError
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("code", buf, offset, len)) {
        code = ji.readLong();
      } else if (fieldEquals("message", buf, offset, len)) {
        message = ji.readString();
      } else if (fieldEquals("data", buf, offset, len)) {
        customError = RpcCustomError.parseError(code, ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
