package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static software.sava.rpc.json.http.response.JsonUtil.parseEncodedData;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxReturnData(PublicKey programId, byte[] data) {

  static TxReturnData parse(final JsonIterator ji) {
    return ji.readOrNull(j -> j.parseObject(new Parser(), Parser::create));
  }

  private static final class Parser implements FieldBufferPredicate {

    private PublicKey programId;
    private byte[] data;

    private TxReturnData create() {
      return new TxReturnData(programId, data);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("programId", buf, offset, len)) {
        programId = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("data", buf, offset, len)) {
        data = parseEncodedData(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
