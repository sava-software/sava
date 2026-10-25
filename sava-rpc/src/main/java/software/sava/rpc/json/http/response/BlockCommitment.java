package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// TowerBFT's stake-weighted commitment for one slot, indexed as agave's `BlockCommitmentArray`: for
/// `i < 31`, `commitment[i]` is the stake whose vote on the slot has confirmation count `i + 1`, and
/// `commitment[31]` the stake that has rooted it; `totalStake` is the epoch's active stake.
///
/// @deprecated Dead on Alpenglow clusters, which every public cluster has been since October 2026:
/// an agave node running Alpenglow never fills the per-block lockout array, so `getBlockCommitment`
/// answers `commitment: null` for every slot after the Alpenglow genesis, parsed here as an empty
/// array, and `totalStake` is 0 on a node started since the switch or a stale TowerBFT total on one
/// that ran through it. No Alpenglow depth metric replaces it: a block is final once its
/// finalization certificate exists, which `getSignatureStatuses` reports as `finalized`. Only a
/// TowerBFT cluster, such as an agave test validator before 4.5 started without `--alpenglow`,
/// still fills this.
@Deprecated(forRemoval = true)
public record BlockCommitment(long[] commitment, long totalStake) {

  public static BlockCommitment parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long[] commitment;
    private long totalStake;

    private Parser() {
    }

    private BlockCommitment create() {
      return new BlockCommitment(commitment, totalStake);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("commitment", buf, offset, len)) {
        commitment = ji.readLongArray(31);
      } else if (fieldEquals("totalStake", buf, offset, len)) {
        totalStake = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
