package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxInstruction(int programIdIndex, int[] accountIndices, String b58Data) {

  public static TxInstruction parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  public static List<TxInstruction> parseInstructions(final JsonIterator ji) {
    final var instructions = new ArrayList<TxInstruction>();
    while (ji.readArray()) {
      instructions.add(TxInstruction.parse(ji));
    }
    return instructions;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("programIdIndex", buf, offset, len)) {
      builder.programIdIndex(ji.readInt());
    } else if (fieldEquals("accounts", buf, offset, len)) {
      final var indices = new ArrayList<Integer>();
      while (ji.readArray()) {
        indices.add(ji.readInt());
      }
      builder.accountIndices(indices.stream().mapToInt(Integer::intValue).toArray());
    } else if (fieldEquals("data", buf, offset, len)) {
      builder.b58Data(ji.readString());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private int programIdIndex;
    private int[] accountIndices;
    private String b58Data;

    private Builder() {
      super();
    }

    private TxInstruction create() {
      return new TxInstruction(programIdIndex, accountIndices, b58Data);
    }

    private void programIdIndex(final int programIdIndex) {
      this.programIdIndex = programIdIndex;
    }

    private void accountIndices(final int[] accountIndices) {
      this.accountIndices = accountIndices;
    }

    private void b58Data(final String b58Data) {
      this.b58Data = b58Data;
    }
  }
}
