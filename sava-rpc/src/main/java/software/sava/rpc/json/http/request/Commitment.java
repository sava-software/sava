package software.sava.rpc.json.http.request;

import systems.comodal.jsoniter.CharBufferFunction;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// The commitment level a request reads at or a subscription notifies at, as the responding
/// node defines it.
///
/// On an agave node running Alpenglow, which every public cluster has since October 2026,
/// `CONFIRMED` and `FINALIZED` both advance when the node roots a block covered by a
/// finalization certificate, so confirmed no longer arrives ahead of finalized. The two are
/// published through separate channels, so one read may see either a moment ahead of the
/// other, and `confirmed` buys no latency. `PROCESSED` is unchanged: the block executed in a
/// bank the node has replayed, with no certificate behind it, so the cluster may still skip it.
/// The Solana Foundation's Alpenglow guide points new code at `finalized` and says `confirmed`
/// is removed in a later release, which is why the client and websocket builders default to
/// `FINALIZED`. A TowerBFT cluster, such as an agave test validator before 4.5 started without
/// `--alpenglow`, keeps the old meanings: `confirmed` is optimistic confirmation and
/// `finalized` lags it by about 32 slots. Mechanism and sources: AGAVE_SYNC.md, "Alpenglow".
public enum Commitment {

  FINALIZED("finalized"),
  CONFIRMED("confirmed"),
  PROCESSED("processed");

  private final String value;

  Commitment(final String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static final CharBufferFunction<Commitment> PARSER = (buf, offset, len) -> {
    if (fieldEquals("processed", buf, offset, len)) {
      return PROCESSED;
    } else if (fieldEquals("confirmed", buf, offset, len)) {
      return CONFIRMED;
    } else if (fieldEquals("finalized", buf, offset, len)) {
      return FINALIZED;
    } else {
      return null;
    }
  };
}
