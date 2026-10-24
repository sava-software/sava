package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.Supplier;

/// @param commission                    Commission percentage; after SIMD-0291 activation the node derives it
///                                      from [#inflationRewardsCommissionBps()] as
///                                      `min(ceilDiv(bps, 100), 255)`.
/// @param inflationRewardsCommissionBps Commission in basis points, empty when the responding node pre-dates
///                                      this field.
/// @param lastVote                      The latest slot this account's vote is known to have landed in: under
///                                      TowerBFT the last on-chain vote, under Alpenglow the greatest slot of
///                                      a reward or finalization certificate carrying its vote that a block
///                                      included (agave updates the vote account from the certificates in
///                                      blocks), so for a live validator it stays within slots of the head
///                                      and drives the `current`/`delinquent` split.
/// @param epochCredits                  The last five rows of the credit history; see [EpochCredits] for the
///                                      Alpenglow migration marker and the switch to reward lamports after
///                                      the migration epoch.
/// @param rootSlot                      The account's root slot, under Alpenglow advanced only by finalization
///                                      certificates it signed that a block carried.
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
