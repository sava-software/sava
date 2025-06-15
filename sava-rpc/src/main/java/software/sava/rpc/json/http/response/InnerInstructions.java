package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record InnerInstructions(int index, List<InnerIx> instructions) {

  static InnerInstructions parseInstruction(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  static List<InnerInstructions> parseInstructions(final JsonIterator ji) {
    final var instructions = new ArrayList<InnerInstructions>();
    while (ji.readArray()) {
      final var innerInstructions = parseInstruction(ji);
      instructions.add(innerInstructions);
    }
    return instructions;
  }

  private static final class Parser implements FieldBufferPredicate {

    private int index;
    private List<InnerIx> instructions;


    private InnerInstructions create() {
      return new InnerInstructions(index, instructions);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("index", buf, offset, len)) {
        index = ji.readInt();
      } else if (fieldEquals("instructions", buf, offset, len)) {
        instructions = new ArrayList<>();
        while (ji.readArray()) {
          final var innerIx = InnerIx.parseIX(ji);
          instructions.add(innerIx);
        }
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
