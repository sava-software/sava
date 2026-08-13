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

/// @param commission    Vote account commission when the reward was credited, in basis points if
///                      [#commissionBps()], otherwise a percentage.
/// @param commissionBps True if the commission is in basis points (SIMD-0291). Nodes which serve it only
///                      serve the percentage as null.
public record TxReward(PublicKey publicKey,
                       long lamports,
                       long postBalance,
                       RewardType rewardType,
                       int commission,
                       boolean commissionBps) {

  /// Compatibility constructor for callers compiled against the response shape before
  /// `commissionBps` was added by the Solana JSON representation.
  public TxReward(final PublicKey publicKey,
                  final long lamports,
                  final long postBalance,
                  final RewardType rewardType,
                  final int commission) {
    this(publicKey, lamports, postBalance, rewardType, commission, false);
  }

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
    } else if (fieldEqualsIgnoreCase("DeactivatedStake", buf, offset, len)) {
      return RewardType.DEACTIVATED_STAKE;
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
    private boolean commissionBps;

    private Parser() {
      super(null);
    }

    @Override
    public TxReward get() {
      return new TxReward(
          pubKey,
          lamports,
          postBalance,
          rewardType,
          commission,
          commissionBps
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "commission",
        "pubkey",
        "rewardType",
        "lamports",
        "postBalance",
        "commissionBps"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> {
          // Nodes serve either the percentage or the basis points, which take precedence regardless
          // of the order in which they are served.
          if (commissionBps || ji.whatIsNext() != ValueType.NUMBER) {
            ji.skip();
          } else {
            commission = ji.readInt();
          }
        }
        case 1 -> pubKey = PublicKeyEncoding.parseBase58Encoded(ji);
        case 2 -> rewardType = ji.applyChars(REWARD_TYPE_PARSER);
        case 3 -> lamports = ji.readLong();
        case 4 -> postBalance = ji.readLong();
        case 5 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            commission = ji.readInt();
            commissionBps = true;
          } else {
            ji.skip();
          }
        }
        default -> ji.skip();
      }
      return true;
    }
  }
}
