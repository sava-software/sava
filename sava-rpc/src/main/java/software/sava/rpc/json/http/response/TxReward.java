package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.List;
import java.util.function.Supplier;

import static systems.comodal.jsoniter.JsonIterator.fieldEqualsIgnoreCase;

public record TxReward(PublicKey publicKey,
                       long lamports,
                       long postBalance,
                       RewardType rewardType,
                       int commission) {

  public static TxReward parse(final JsonIterator ji) {
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  public static List<TxReward> parseRewards(final JsonIterator ji) {
    return ji.readList(TxReward::parse);
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

  private static final class Parser extends RootBuilder implements FieldIndexPredicate, Supplier<TxReward> {

    private PublicKey pubKey;
    private long lamports;
    private long postBalance;
    private RewardType rewardType;
    private int commission;

    private Parser() {
      super(null);
    }

    @Override
    public TxReward get() {
      return new TxReward(pubKey, lamports, postBalance, rewardType, commission);
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "commission",
        "pubkey",
        "rewardType",
        "lamports",
        "postBalance"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            commission = ji.readInt();
          } else {
            ji.skip();
          }
        }
        case 1 -> pubKey = PublicKeyEncoding.parseBase58Encoded(ji);
        case 2 -> rewardType = ji.applyChars(REWARD_TYPE_PARSER);
        case 3 -> lamports = ji.readLong();
        case 4 -> postBalance = ji.readLong();
        default -> ji.skip();
      }
      return true;
    }
  }
}
