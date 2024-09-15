package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record LatestBlockHash(Context context, String blockHash, long lastValidBlockHeight) {

  public static LatestBlockHash parse(final JsonIterator ji, final Context context) {
    return ji.testObject(new Builder(context), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("blockhash", buf, offset, len)) {
      builder.blockHash = ji.readString();
    } else if (fieldEquals("lastValidBlockHeight", buf, offset, len)) {
      builder.lastValidBlockHeight = ji.readLong();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private String blockHash;
    private long lastValidBlockHeight;

    private Builder(final Context context) {
      super(context);
    }

    private LatestBlockHash create() {
      return new LatestBlockHash(context, blockHash, lastValidBlockHeight);
    }
  }
}
