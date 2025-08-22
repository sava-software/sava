package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Identity(PublicKey identityKey) {

  @Deprecated
  public String identity() {
    return identityKey.toString();
  }

  public static Identity parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private PublicKey identity;

    private Parser() {
    }

    private Identity create() {
      return new Identity(identity);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("identity", buf, offset, len)) {
        identity = PublicKeyEncoding.parseBase58Encoded(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
