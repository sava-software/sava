package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxInnerInstruction(int index, List<TxInstruction> instructions) {

  public static TxInnerInstruction parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  public static List<TxInnerInstruction> parseInstructions(final JsonIterator ji) {
    final var instructions = new ArrayList<TxInnerInstruction>();
    while (ji.readArray()) {
      instructions.add(parse(ji));
    }
    return instructions;
  }

  private static final class Parser implements FieldBufferPredicate {

    private int index;
    private List<TxInstruction> instructions;

    private Parser() {
      super();
    }

    private TxInnerInstruction create() {
      return new TxInnerInstruction(index, instructions);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("index", buf, offset, len)) {
        index = ji.readInt();
      } else if (fieldEquals("instructions", buf, offset, len)) {
        instructions = TxInstruction.parseInstructions(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
