package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxInstructionError(int instructionIndex, String type, int code) {

  public static TxInstructionError parseError(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case NULL, INVALID, ARRAY -> {
        ji.skip();
        yield null;
      }
      case STRING -> new TxInstructionError(-1, ji.readString(), -1);
      case NUMBER -> new TxInstructionError(-1, ji.readNumberAsString(), -1);
      case BOOLEAN -> new TxInstructionError(-1, ji.readBoolean() ? "true" : "false", -1);
      case OBJECT -> ji.testObject(new Builder(), PARSER).create();
    };
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("InstructionError", buf, offset, len)) {
      builder.instructionIndex(ji.openArray().readInt());
      final var next = ji.continueArray().whatIsNext();
      if (next == ValueType.OBJECT) {
        final var type = ji.readObjField();
        builder.type(type);
        if ("Custom".equalsIgnoreCase(type)) {
          builder.code(ji.readInt());
        } else {
          throw new UnsupportedOperationException("Unhandled tx error type " + new String(buf, offset, len));
        }
        ji.skipRestOfObject();
      } else if (next == ValueType.STRING) {
        builder.type(ji.readString());
      }
      ji.skipRestOfArray();
    } else if (fieldEquals("Ok", buf, offset, len)) {
      ji.skip();
      builder.type = "Ok";
    } else {
      throw new UnsupportedOperationException("Unhandled tx error type " + new String(buf, offset, len));
    }
    return true;
  };

  private static final class Builder {

    private int instructionIndex;
    private String type;
    private int code;

    private Builder() {
    }

    private TxInstructionError create() {
      return type.equals("Ok") ? null : new TxInstructionError(instructionIndex, type, code);
    }

    private void instructionIndex(final int instructionIndex) {
      this.instructionIndex = instructionIndex;
    }

    private void type(final String type) {
      this.type = type;
    }

    private void code(final int code) {
      this.code = code;
    }
  }
}
