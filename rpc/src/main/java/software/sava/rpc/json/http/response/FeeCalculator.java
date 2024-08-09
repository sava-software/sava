package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record FeeCalculator(int lamportsPerSignature) {

  public static FeeCalculator parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<FeeCalculator.Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("lamportsPerSignature", buf, offset, len)) {
      builder.lamportsPerSignature(ji.readInt());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private int lamportsPerSignature;

    private Builder() {
    }

    private FeeCalculator create() {
      return new FeeCalculator(lamportsPerSignature);
    }

    private void lamportsPerSignature(final int lamportsPerSignature) {
      this.lamportsPerSignature = lamportsPerSignature;
    }
  }
}
