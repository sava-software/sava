package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;
import java.util.function.Supplier;

public record TxMeta(TransactionError error,
                     int computeUnitsConsumed,
                     Long costUnits,
                     long fee,
                     List<Long> preBalances,
                     List<Long> postBalances,
                     List<TokenBalance> preTokenBalances,
                     List<TokenBalance> postTokenBalances,
                     List<TxInnerInstruction> innerInstructions,
                     LoadedAddresses loadedAddresses,
                     TxReturnData returnData,
                     List<String> logMessages,
                     List<TxReward> rewards) {

  public static TxMeta parse(final JsonIterator ji) {
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  static List<Long> parseLamportBalances(final JsonIterator ji) {
    return ji.readList(JsonIterator::readLong);
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<TxMeta> {

    private TransactionError error;
    private int computeUnitsConsumed;
    private Long costUnits;
    private long fee;
    private List<Long> preBalances;
    private List<Long> postBalances;
    private List<TokenBalance> preTokenBalances;
    private List<TokenBalance> postTokenBalances;
    private List<TxInnerInstruction> innerInstructions;
    private LoadedAddresses loadedAddresses;
    private TxReturnData returnData;
    private List<String> logMessages;
    private List<TxReward> rewards;

    private Parser() {
    }

    @Override
    public TxMeta get() {
      return new TxMeta(
          error,
          computeUnitsConsumed,
          costUnits,
          fee,
          preBalances,
          postBalances,
          preTokenBalances,
          postTokenBalances,
          innerInstructions,
          loadedAddresses,
          returnData,
          logMessages,
          rewards
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "err",
        "computeUnitsConsumed",
        "costUnits",
        "fee",
        "preBalances",
        "postBalances",
        "preTokenBalances",
        "postTokenBalances",
        "innerInstructions",
        "loadedAddresses",
        "returnData",
        "logMessages",
        "rewards"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> this.error = TransactionError.parseError(ji);
        case 1 -> this.computeUnitsConsumed = ji.readInt();
        case 2 -> this.costUnits = ji.readLong();
        case 3 -> this.fee = ji.readLong();
        case 4 -> this.preBalances = parseLamportBalances(ji);
        case 5 -> this.postBalances = parseLamportBalances(ji);
        case 6 -> this.preTokenBalances = TokenBalance.parseBalances(ji);
        case 7 -> this.postTokenBalances = TokenBalance.parseBalances(ji);
        case 8 -> this.innerInstructions = TxInnerInstruction.parseInstructions(ji);
        case 9 -> this.loadedAddresses = LoadedAddresses.parse(ji);
        case 10 -> this.returnData = TxReturnData.parse(ji);
        case 11 -> this.logMessages = ji.readList(JsonIterator::readString);
        case 12 -> this.rewards = TxReward.parseRewards(ji);
        default -> ji.skip();
      }
      return true;
    }
  }
}
