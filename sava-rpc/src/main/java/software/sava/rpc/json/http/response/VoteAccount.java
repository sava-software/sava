package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

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

  @Deprecated(forRemoval = true)
  public String votePubKey() {
    return voteKey.toBase58();
  }

  @Deprecated(forRemoval = true)
  public String nodePubKey() {
    return nodeKey.toBase58();
  }

  static List<VoteAccount> parse(final JsonIterator ji) {
    final var voteAccounts = new ArrayList<VoteAccount>();
    while (ji.readArray()) {
      final var parser = new Parser();
      ji.testObject(parser);
      voteAccounts.add(parser.create());
    }
    return voteAccounts;
  }


  private static final class Parser implements FieldBufferPredicate {

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

    private VoteAccount create() {
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

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("votePubkey", buf, offset, len)) {
        votePubKey = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("nodePubkey", buf, offset, len)) {
        nodePubKey = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("activatedStake", buf, offset, len)) {
        activatedStake = ji.readLong();
      } else if (fieldEquals("epochVoteAccount", buf, offset, len)) {
        epochVoteAccount = ji.readBoolean();
      } else if (fieldEquals("commission", buf, offset, len)) {
        commission = ji.readInt();
      } else if (fieldEquals("inflationRewardsCommissionBps", buf, offset, len)) {
        inflationRewardsCommissionBps = ji.readInt();
      } else if (fieldEquals("lastVote", buf, offset, len)) {
        lastVote = ji.readLong();
      } else if (fieldEquals("epochCredits", buf, offset, len)) {
        epochCredits = EpochCredits.parse(ji);
      } else if (fieldEquals("rootSlot", buf, offset, len)) {
        rootSlot = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
