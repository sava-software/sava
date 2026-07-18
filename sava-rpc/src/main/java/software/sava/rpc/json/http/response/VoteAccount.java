package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.Supplier;

/// @param commission                    Before SIMD-0291 activation, the native commission percentage. After
///                                      activation, derived from [#inflationRewardsCommissionBps()] with
///                                      `min(ceilDiv(bps, 100), 255)`.
/// @param inflationRewardsCommissionBps Commission in basis points, empty when the responding node pre-dates
///                                      this field.
public record VoteAccount(PublicKey voteKey,
                          PublicKey nodeKey,
                          long activatedStake,
                          boolean epochVoteAccount,
                          int commission,
                          OptionalInt inflationRewardsCommissionBps,
                          long lastVote,
                          List<EpochCredits> epochCredits,
                          long rootSlot) {

  static List<VoteAccount> parse(final JsonIterator ji) {
    return ji.readList(j -> j.parseObject(Parser.FIELDS, new Parser()));
  }


  private static final class Parser implements FieldIndexPredicate, Supplier<VoteAccount> {

    private PublicKey votePubKey;
    private PublicKey nodePubKey;
    private long activatedStake;
    private boolean epochVoteAccount;
    private int commission;
    private int inflationRewardsCommissionBps = -1;
    private long lastVote;
    private List<EpochCredits> epochCredits;
    private long rootSlot;

    private Parser() {
    }

    @Override
    public VoteAccount get() {
      return new VoteAccount(
          votePubKey,
          nodePubKey,
          activatedStake,
          epochVoteAccount,
          commission,
          inflationRewardsCommissionBps < 0 ? OptionalInt.empty() : OptionalInt.of(inflationRewardsCommissionBps),
          lastVote,
          epochCredits,
          rootSlot
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "votePubkey",
        "nodePubkey",
        "activatedStake",
        "epochVoteAccount",
        "commission",
        "inflationRewardsCommissionBps",
        "lastVote",
        "epochCredits",
        "rootSlot"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> votePubKey = PublicKeyEncoding.parseBase58Encoded(ji);
        case 1 -> nodePubKey = PublicKeyEncoding.parseBase58Encoded(ji);
        case 2 -> activatedStake = ji.readLong();
        case 3 -> epochVoteAccount = ji.readBoolean();
        case 4 -> commission = ji.readInt();
        case 5 -> inflationRewardsCommissionBps = ji.readInt();
        case 6 -> lastVote = ji.readLong();
        case 7 -> epochCredits = EpochCredits.parse(ji);
        case 8 -> rootSlot = ji.readLong();
        default -> ji.skip();
      }
      return true;
    }
  }
}
