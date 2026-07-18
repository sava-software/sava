package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record TxInstruction(int programIdIndex,
                            int[] accountIndices,
                            String b58Data,
                            int stackHeight) {

  public static TxInstruction parse(final JsonIterator ji) {
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  public static List<TxInstruction> parseInstructions(final JsonIterator ji) {
    return ji.readList(TxInstruction::parse);
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<TxInstruction> {

    private int programIdIndex;
    private int[] accountIndices;
    private String b58Data;
    private int stackHeight;

    private Parser() {
      super();
    }

    @Override
    public TxInstruction get() {
      return new TxInstruction(
          programIdIndex,
          accountIndices,
          b58Data,
          stackHeight
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "programIdIndex",
        "accounts",
        "data",
        "stackHeight"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> programIdIndex = ji.readInt();
        case 1 -> {
          final var indices = new ArrayList<Integer>();
          while (ji.readArray()) {
            indices.add(ji.readInt());
          }
          accountIndices = indices.stream().mapToInt(Integer::intValue).toArray();
        }
        case 2 -> b58Data = ji.readString();
        case 3 -> stackHeight = ji.readIntOr(stackHeight);
        default -> ji.skip();
      }
      return true;
    }
  }
}
