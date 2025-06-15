package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

public record EpochCredits(long epoch, long credits, long previousCredits) {

  static List<EpochCredits> parse(final JsonIterator ji) {
    final var creditHistory = new ArrayList<EpochCredits>();
    while (ji.readArray()) {
      final long epoch = ji.openArray().readLong();
      final long credits = ji.continueArray().readLong();
      creditHistory.add(new EpochCredits(epoch, credits, ji.continueArray().readLong()));
      ji.closeArray();
    }
    return creditHistory;
  }
}
