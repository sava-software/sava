package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record LoadedAddresses(List<PublicKey> readonly, List<PublicKey> writable) {

  public static LoadedAddresses parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private static final List<PublicKey> NO_KEYS = List.of();

    private List<PublicKey> readonly;
    private List<PublicKey> writable;

    private Parser() {
      super();
    }

    private LoadedAddresses create() {
      return new LoadedAddresses(
          Objects.requireNonNullElse(readonly, NO_KEYS),
          Objects.requireNonNullElse(writable, NO_KEYS)
      );
    }

    private static List<PublicKey> parseKeys(final JsonIterator ji) {
      final var keys = new ArrayList<PublicKey>();
      while (ji.readArray()) {
        keys.add(PublicKeyEncoding.parseBase58Encoded(ji));
      }
      return keys.isEmpty() ? NO_KEYS : keys;
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("readonly", buf, offset, len)) {
        this.readonly = parseKeys(ji);
      } else if (fieldEquals("writable", buf, offset, len)) {
        this.writable = parseKeys(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
