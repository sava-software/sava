package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;
import static systems.comodal.jsoniter.JsonIterator.fieldEqualsIgnoreCase;

public record TxReward(String pubKey,
                       long lamports,
                       long postBalance,
                       RewardType rewardType,
                       int commission) {

  public static TxReward parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  public static List<TxReward> parseRewards(final JsonIterator ji) {
    final var rewards = new ArrayList<TxReward>();
    while (ji.readArray()) {
      rewards.add(parse(ji));
    }
    return rewards;
  }

  private static final CharBufferFunction<RewardType> REWARD_TYPE_PARSER = (buf, offset, len) -> {
    if (fieldEqualsIgnoreCase("fee", buf, offset, len)) {
      return RewardType.FEE;
    } else if (fieldEqualsIgnoreCase("rent", buf, offset, len)) {
      return RewardType.RENT;
    } else if (fieldEqualsIgnoreCase("voting", buf, offset, len)) {
      return RewardType.VOTING;
    } else if (fieldEqualsIgnoreCase("staking", buf, offset, len)) {
      return RewardType.STAKING;
    } else {
      return null;
    }
  };

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("commission", buf, offset, len)) {
      if (ji.whatIsNext() == ValueType.NUMBER) {
        builder.commission(ji.readInt());
      } else {
        ji.skip();
      }
    } else if (fieldEquals("pubkey", buf, offset, len)) {
      builder.pubKey(ji.readString());
    } else if (fieldEquals("rewardType", buf, offset, len)) {
      builder.rewardType(ji.applyChars(REWARD_TYPE_PARSER));
    } else if (fieldEquals("lamports", buf, offset, len)) {
      builder.lamports(ji.readLong());
    } else if (fieldEquals("postBalance", buf, offset, len)) {
      builder.postBalance(ji.readLong());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private String pubKey;
    private long lamports;
    private long postBalance;
    private RewardType rewardType;
    private int commission;

    private Builder() {
    }

    private TxReward create() {
      return new TxReward(pubKey, lamports, postBalance, rewardType, commission);
    }

    private void commission(final int commission) {
      this.commission = commission;
    }

    private void pubKey(final String pubKey) {
      this.pubKey = pubKey;
    }

    private void rewardType(final RewardType rewardType) {
      this.rewardType = rewardType;
    }

    private void lamports(final long lamports) {
      this.lamports = lamports;
    }

    private void postBalance(final long postBalance) {
      this.postBalance = postBalance;
    }
  }
}
