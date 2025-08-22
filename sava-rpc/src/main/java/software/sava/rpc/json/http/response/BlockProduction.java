package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.HashMap;
import java.util.Map;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record BlockProduction(Context context,
                              Map<PublicKey, ValidatorLeaderInfo> leaderInfoMap,
                              long firstSlot,
                              long lastSlot) {

  @Deprecated
  public Map<String, ValidatorLeaderInfo> leaderInfo() {
    final var leaderInfo = HashMap.<String, ValidatorLeaderInfo>newHashMap(leaderInfoMap.size());
    for (final var entry : leaderInfoMap.entrySet()) {
      leaderInfo.put(entry.getKey().toBase58(), entry.getValue());
    }
    return leaderInfo;
  }

  public static BlockProduction parse(final JsonIterator ji, final Context context) {
    final var parser = new Parser(context);
    ji.testObject(parser);
    return parser.create();
  }

  private static final ContextFieldBufferPredicate<Parser> RANGE_PARSER = (parser, buf, offset, len, ji) -> {
    if (fieldEquals("firstSlot", buf, offset, len)) {
      parser.firstSlot = ji.readLong();
    } else if (fieldEquals("lastSlot", buf, offset, len)) {
      parser.lastSlot = ji.readLong();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Parser extends RootBuilder implements FieldBufferPredicate {

    private Map<PublicKey, ValidatorLeaderInfo> leaderInfo;
    private long firstSlot;
    private long lastSlot;

    private Parser(final Context context) {
      super(context);
    }

    private BlockProduction create() {
      return new BlockProduction(context, leaderInfo, firstSlot, lastSlot);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("byIdentity", buf, offset, len)) {
        final var leaderInfo = new HashMap<PublicKey, ValidatorLeaderInfo>();
        for (PublicKey validator; (validator = PublicKeyEncoding.parseObjectFieldBase58Encoded(ji)) != null; ji.closeArray()) {
          final int numSlots = ji.openArray().readInt();
          final int blocksProduced = ji.continueArray().readInt();
          leaderInfo.put(validator, new ValidatorLeaderInfo(numSlots, blocksProduced));
        }
        this.leaderInfo = leaderInfo;
      } else if (fieldEquals("range", buf, offset, len)) {
        ji.testObject(this, RANGE_PARSER);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
