package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxInstruction(int programIdIndex,
                            int[] accountIndices,
                            String b58Data,
                            int stackHeight) {

  public static TxInstruction parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  public static List<TxInstruction> parseInstructions(final JsonIterator ji) {
    final var instructions = new ArrayList<TxInstruction>();
    while (ji.readArray()) {
      instructions.add(TxInstruction.parse(ji));
    }
    return instructions;
  }

  private static final class Parser implements FieldBufferPredicate {

    private int programIdIndex;
    private int[] accountIndices;
    private String b58Data;
    private int stackHeight;

    private Parser() {
      super();
    }

    private TxInstruction create() {
      return new TxInstruction(
          programIdIndex,
          accountIndices,
          b58Data,
          stackHeight
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("programIdIndex", buf, offset, len)) {
        programIdIndex = ji.readInt();
      } else if (fieldEquals("accounts", buf, offset, len)) {
        final var indices = new ArrayList<Integer>();
        while (ji.readArray()) {
          indices.add(ji.readInt());
        }
        accountIndices = indices.stream().mapToInt(Integer::intValue).toArray();
      } else if (fieldEquals("data", buf, offset, len)) {
        b58Data = ji.readString();
      } else if (fieldEquals("stackHeight", buf, offset, len)) {
        stackHeight = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
