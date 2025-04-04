package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Block(long blockHeight,
                    long blockTime,
                    String blockHash,
                    String previousBlockHash,
                    long parentSlot,
                    List<TxReward> rewards,
                    List<String> signatures,
                    List<BlockTx> transactions) {

  public static Block parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long blockHeight;
    private long blockTime;
    private String blockHash;
    private String previousBlockHash;
    private long parentSlot;
    private List<TxReward> rewards;
    private List<String> signatures;
    private List<BlockTx> transactions;

    private Parser() {
    }

    private Block create() {
      return new Block(
          blockHeight,
          blockTime,
          blockHash,
          previousBlockHash,
          parentSlot,
          rewards,
          signatures == null ? List.of() : signatures,
          transactions == null ? List.of() : transactions
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("blockHeight", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.NUMBER) {
          blockHeight = ji.readLong();
        } else {
          ji.skip();
        }
      } else if (fieldEquals("blockTime", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.NUMBER) {
          blockTime = ji.readLong();
        } else {
          ji.skip();
        }
      } else if (fieldEquals("blockhash", buf, offset, len)) {
        blockHash = ji.readString();
      } else if (fieldEquals("previousBlockhash", buf, offset, len)) {
        previousBlockHash = ji.readString();
      } else if (fieldEquals("parentSlot", buf, offset, len)) {
        parentSlot = ji.readLong();
      } else if (fieldEquals("rewards", buf, offset, len)) {
        rewards = TxReward.parseRewards(ji);
      } else if (fieldEquals("signatures", buf, offset, len)) {
        final var signatures = new ArrayList<String>(2_048);
        while (ji.readArray()) {
          signatures.add(ji.readString());
        }
        this.signatures = signatures;
      } else if (fieldEquals("transactions", buf, offset, len)) {
        final var transactions = new ArrayList<BlockTx>(2_048);
        while (ji.readArray()) {
          transactions.add(BlockTx.parse(ji));
        }
        this.transactions = transactions;
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
