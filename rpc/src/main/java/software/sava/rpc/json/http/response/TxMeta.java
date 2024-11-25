package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxMeta(TransactionError error,
                     int computeUnitsConsumed,
                     long fee,
                     List<Long> preBalances,
                     List<Long> postBalances,
                     List<TokenBalance> preTokenBalances,
                     List<TokenBalance> postTokenBalances,
                     List<TxInnerInstruction> innerInstructions,
                     LoadedAddresses loadedAddresses,
                     List<String> logMessages,
                     List<TxReward> rewards) {

  public static TxMeta parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private TransactionError error;
    private int computeUnitsConsumed;
    private long fee;
    private List<Long> preBalances;
    private List<Long> postBalances;
    private List<TokenBalance> preTokenBalances;
    private List<TokenBalance> postTokenBalances;
    private List<TxInnerInstruction> innerInstructions;
    private LoadedAddresses loadedAddresses;
    private List<String> logMessages;
    private List<TxReward> rewards;

    private Parser() {
    }

    private TxMeta create() {
      return new TxMeta(
          error,
          computeUnitsConsumed,
          fee,
          preBalances,
          postBalances,
          preTokenBalances,
          postTokenBalances,
          innerInstructions,
          loadedAddresses,
          logMessages,
          rewards
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("err", buf, offset, len)) {
        this.error = TransactionError.parseError(ji);
      } else if (fieldEquals("computeUnitsConsumed", buf, offset, len)) {
        this.computeUnitsConsumed = ji.readInt();
      } else if (fieldEquals("fee", buf, offset, len)) {
        this.fee = ji.readLong();
      } else if (fieldEquals("preBalances", buf, offset, len)) {
        final var balances = new ArrayList<Long>();
        while (ji.readArray()) {
          balances.add(ji.readLong());
        }
        this.preBalances = balances;
      } else if (fieldEquals("postBalances", buf, offset, len)) {
        final var balances = new ArrayList<Long>();
        while (ji.readArray()) {
          balances.add(ji.readLong());
        }
        this.postBalances = balances;
      } else if (fieldEquals("preTokenBalances", buf, offset, len)) {
        this.preTokenBalances = TokenBalance.parseBalances(ji);
      } else if (fieldEquals("postTokenBalances", buf, offset, len)) {
        this.postTokenBalances = TokenBalance.parseBalances(ji);
      } else if (fieldEquals("innerInstructions", buf, offset, len)) {
        this.innerInstructions = TxInnerInstruction.parseInstructions(ji);
      } else if (fieldEquals("loadedAddresses", buf, offset, len)) {
        this.loadedAddresses = LoadedAddresses.parse(ji);
      } else if (fieldEquals("logMessages", buf, offset, len)) {
        final var logMessages = new ArrayList<String>();
        while (ji.readArray()) {
          logMessages.add(ji.readString());
        }
        this.logMessages = logMessages;
      } else if (fieldEquals("rewards", buf, offset, len)) {
        this.rewards = TxReward.parseRewards(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
