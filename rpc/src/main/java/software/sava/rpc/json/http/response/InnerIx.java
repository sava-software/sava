package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record InnerIx(String program, PublicKey programId, int stackHeight) {

  static InnerIx parseIX(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private String program;
    private PublicKey programId;
    private int stackHeight;


    private InnerIx create() {
      return new InnerIx(program, programId, stackHeight);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("program", buf, offset, len)) {
        program = ji.readString();
      } else if (fieldEquals("programId", buf, offset, len)) {
        programId = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("stackHeight", buf, offset, len)) {
        stackHeight = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
