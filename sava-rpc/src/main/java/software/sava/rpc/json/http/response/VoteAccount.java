package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record VoteAccount(String votePubKey,
                          String nodePubKey,
                          long activatedStake,
                          boolean epochVoteAccount,
                          int commission,
                          long lastVote,
                          List<EpochCredits> epochCredits,
                          long rootSlot) {

  static List<VoteAccount> parse(final JsonIterator ji) {
    final var voteAccounts = new ArrayList<VoteAccount>();
    while (ji.readArray()) {
      final var voteAccount = ji.testObject(new Builder(), PARSER).create();
      voteAccounts.add(voteAccount);
    }
    return voteAccounts;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("votePubkey", buf, offset, len)) {
      builder.votePubKey = ji.readString();
    } else if (fieldEquals("nodePubkey", buf, offset, len)) {
      builder.nodePubKey = ji.readString();
    } else if (fieldEquals("activatedStake", buf, offset, len)) {
      builder.activatedStake = ji.readLong();
    } else if (fieldEquals("epochVoteAccount", buf, offset, len)) {
      builder.epochVoteAccount = ji.readBoolean();
    } else if (fieldEquals("commission", buf, offset, len)) {
      builder.commission = ji.readInt();
    } else if (fieldEquals("lastVote", buf, offset, len)) {
      builder.lastVote = ji.readLong();
    } else if (fieldEquals("epochCredits", buf, offset, len)) {
      builder.epochCredits = EpochCredits.parse(ji);
    } else if (fieldEquals("rootSlot", buf, offset, len)) {
      builder.rootSlot = ji.readLong();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private String votePubKey;
    private String nodePubKey;
    private long activatedStake;
    private boolean epochVoteAccount;
    private int commission;
    private long lastVote;
    private List<EpochCredits> epochCredits;
    private long rootSlot;

    private Builder() {

    }

    private VoteAccount create() {
      return new VoteAccount(votePubKey, nodePubKey, activatedStake, epochVoteAccount, commission, lastVote, epochCredits, rootSlot);
    }
  }
}
