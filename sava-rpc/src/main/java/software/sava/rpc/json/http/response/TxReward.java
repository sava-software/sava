package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;
import static systems.comodal.jsoniter.JsonIterator.fieldEqualsIgnoreCase;

public record TxReward(PublicKey publicKey,
                       long lamports,
                       long postBalance,
                       RewardType rewardType,
                       int commission) {

  @Deprecated
  public String pubKey() {
    return publicKey.toString();
  }

  public static TxReward parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  public static List<TxReward> parseRewards(final JsonIterator ji) {
    final var rewards = new ArrayList<TxReward>();
    final var parser = new Parser();
    while (ji.readArray()) {
      ji.testObject(parser);
      rewards.add(parser.create());
      parser.reset();
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

  private static final class Parser extends RootBuilder implements FieldBufferPredicate {

    private PublicKey pubKey;
    private long lamports;
    private long postBalance;
    private RewardType rewardType;
    private int commission;

    private Parser() {
      super(null);
    }

    private TxReward create() {
      return new TxReward(pubKey, lamports, postBalance, rewardType, commission);
    }

    private void reset() {
      pubKey = null;
      lamports = 0L;
      postBalance = 0L;
      rewardType = null;
      commission = 0;
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("commission", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.NUMBER) {
          commission = ji.readInt();
        } else {
          ji.skip();
        }
      } else if (fieldEquals("pubkey", buf, offset, len)) {
        pubKey = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("rewardType", buf, offset, len)) {
        rewardType = ji.applyChars(REWARD_TYPE_PARSER);
      } else if (fieldEquals("lamports", buf, offset, len)) {
        lamports = ji.readLong();
      } else if (fieldEquals("postBalance", buf, offset, len)) {
        postBalance = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
