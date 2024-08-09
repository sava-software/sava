package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxInnerInstruction(int index, List<TxInstruction> instructions) {

  public static TxInnerInstruction parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  public static List<TxInnerInstruction> parseInstructions(final JsonIterator ji) {
    final var instructions = new ArrayList<TxInnerInstruction>();
    while (ji.readArray()) {
      instructions.add(parse(ji));
    }
    return instructions;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("index", buf, offset, len)) {
      builder.index(ji.readInt());
    } else if (fieldEquals("instructions", buf, offset, len)) {
      builder.instructions(TxInstruction.parseInstructions(ji));
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private int index;
    private List<TxInstruction> instructions;

    private Builder() {
      super();
    }

    private TxInnerInstruction create() {
      return new TxInnerInstruction(index, instructions);
    }

    private void index(final int index) {
      this.index = index;
    }

    private void instructions(final List<TxInstruction> instructions) {
      this.instructions = instructions;
    }
  }
}
