package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxMeta(TxInstructionError error,
                     int computeUnitsConsumed,
                     long fee,
                     List<Long> preBalances,
                     List<Long> postBalances,
                     List<TokenBalance> preTokenBalances,
                     List<TokenBalance> postTokenBalances,
                     List<TxInnerInstruction> innerInstructions,
                     List<String> readOnly,
                     List<String> writable,
                     List<String> logMessages,
                     List<TxReward> rewards) {

  public static TxMeta parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> ADDRESSES = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("readonly", buf, offset, len)) {
      builder.readOnly = new ArrayList<>();
      while (ji.readArray()) {
        builder.readOnly.add(ji.readString());
      }
    } else if (fieldEquals("writable", buf, offset, len)) {
      builder.writable = new ArrayList<>();
      while (ji.readArray()) {
        builder.writable.add(ji.readString());
      }
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("err", buf, offset, len)) {
      builder.error(TxInstructionError.parseError(ji));
    } else if (fieldEquals("computeUnitsConsumed", buf, offset, len)) {
      builder.computeUnitsConsumed(ji.readInt());
    } else if (fieldEquals("fee", buf, offset, len)) {
      builder.fee(ji.readLong());
    } else if (fieldEquals("preBalances", buf, offset, len)) {
      final var balances = new ArrayList<Long>();
      while (ji.readArray()) {
        balances.add(ji.readLong());
      }
      builder.preBalances(balances);
    } else if (fieldEquals("postBalances", buf, offset, len)) {
      final var balances = new ArrayList<Long>();
      while (ji.readArray()) {
        balances.add(ji.readLong());
      }
      builder.postBalances(balances);
    } else if (fieldEquals("preTokenBalances", buf, offset, len)) {
      builder.preTokenBalances(TokenBalance.parseBalances(ji));
    } else if (fieldEquals("postTokenBalances", buf, offset, len)) {
      builder.postTokenBalances(TokenBalance.parseBalances(ji));
    } else if (fieldEquals("innerInstructions", buf, offset, len)) {
      builder.innerInstructions(TxInnerInstruction.parseInstructions(ji));
    } else if (fieldEquals("loadedAddresses", buf, offset, len)) {
      ji.testObject(builder, ADDRESSES);
    } else if (fieldEquals("logMessages", buf, offset, len)) {
      final var logMessages = new ArrayList<String>();
      while (ji.readArray()) {
        logMessages.add(ji.readString());
      }
      builder.logMessages(logMessages);
    } else if (fieldEquals("rewards", buf, offset, len)) {
      builder.rewards(TxReward.parseRewards(ji));
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private TxInstructionError error;
    private int computeUnitsConsumed;
    private long fee;
    private List<Long> preBalances;
    private List<Long> postBalances;
    private List<TokenBalance> preTokenBalances;
    private List<TokenBalance> postTokenBalances;
    private List<TxInnerInstruction> innerInstructions;
    private List<String> readOnly;
    private List<String> writable;
    private List<String> logMessages;
    private List<TxReward> rewards;

    private Builder() {
    }

    private TxMeta create() {
      return new TxMeta(error, computeUnitsConsumed, fee, preBalances, postBalances, preTokenBalances, postTokenBalances, innerInstructions, readOnly, writable, logMessages, rewards);
    }

    private void error(final TxInstructionError error) {
      this.error = error;
    }

    private void computeUnitsConsumed(final int computeUnitsConsumed) {
      this.computeUnitsConsumed = computeUnitsConsumed;
    }

    private void fee(final long fee) {
      this.fee = fee;
    }

    private void preBalances(final List<Long> preBalances) {
      this.preBalances = preBalances;
    }

    private void postBalances(final List<Long> postBalances) {
      this.postBalances = postBalances;
    }

    private void preTokenBalances(final List<TokenBalance> preTokenBalances) {
      this.preTokenBalances = preTokenBalances;
    }

    private void postTokenBalances(final List<TokenBalance> postTokenBalances) {
      this.postTokenBalances = postTokenBalances;
    }

    private void innerInstructions(final List<TxInnerInstruction> innerInstructions) {
      this.innerInstructions = innerInstructions;
    }

    private void logMessages(final List<String> logMessages) {
      this.logMessages = logMessages;
    }

    private void rewards(final List<TxReward> rewards) {
      this.rewards = rewards;
    }
  }
}
