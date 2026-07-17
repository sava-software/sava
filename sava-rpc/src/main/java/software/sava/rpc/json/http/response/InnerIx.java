package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;
import java.util.function.Supplier;

public record InnerIx(PublicKey programId,
                      int stackHeight,
                      List<PublicKey> accounts,
                      byte[] data) {

  static InnerIx parseIX(final JsonIterator ji) {
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<InnerIx> {

    private PublicKey programId;
    private int stackHeight;
    private List<PublicKey> accounts;
    private byte[] data;

    @Override
    public InnerIx get() {
      return new InnerIx(
          programId, stackHeight, accounts, data
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "programId",
        "stackHeight",
        "accounts",
        "data"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> programId = PublicKeyEncoding.parseBase58Encoded(ji);
        case 1 -> stackHeight = ji.readInt();
        case 2 -> this.accounts = List.copyOf(ji.readList(PublicKeyEncoding::parseBase58Encoded));
        case 3 -> data = ji.applyChars(JsonUtil.DECODE_BASE58);
        default -> ji.skip();
      }
      return true;
    }
  }
}
