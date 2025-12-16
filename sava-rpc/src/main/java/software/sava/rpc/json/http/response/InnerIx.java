package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record InnerIx(@Deprecated String program, // Part of JSON parsed response which is not supported.
                      PublicKey programId,
                      int stackHeight,
                      List<PublicKey> accounts,
                      byte[] data) {

  static InnerIx parseIX(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private String program;
    private PublicKey programId;
    private int stackHeight;
    private List<PublicKey> accounts;
    private byte[] data;

    private InnerIx create() {
      return new InnerIx(
          program, programId, stackHeight, accounts, data
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("program", buf, offset, len)) {
        program = ji.readString();
      } else if (fieldEquals("programId", buf, offset, len)) {
        programId = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("stackHeight", buf, offset, len)) {
        stackHeight = ji.readInt();
      } else if (fieldEquals("accounts", buf, offset, len)) {
        final var accounts = new ArrayList<PublicKey>();
        while (ji.readArray()) {
          accounts.add(PublicKeyEncoding.parseBase58Encoded(ji));
        }
        this.accounts = List.copyOf(accounts);
      } else if (fieldEquals("data", buf, offset, len)) {
        data = Base58.decode(ji.readString());
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
