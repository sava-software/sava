package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record Block(long blockHeight,
                    long blockTime,
                    String blockHash,
                    String previousBlockHash,
                    long parentSlot,
                    long numRewardPartitions,
                    List<TxReward> rewards,
                    List<String> signatures,
                    List<BlockTx> transactions) {

  public static Block parse(final JsonIterator ji) {
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<Block> {

    private long blockHeight;
    private long blockTime;
    private String blockHash;
    private String previousBlockHash;
    private long parentSlot;
    private long numRewardPartitions;
    private List<TxReward> rewards;
    private List<String> signatures;
    private List<BlockTx> transactions;

    private Parser() {
    }

    @Override
    public Block get() {
      return new Block(
          blockHeight,
          blockTime,
          blockHash,
          previousBlockHash,
          parentSlot,
          numRewardPartitions,
          rewards == null ? List.of() : rewards,
          signatures == null ? List.of() : signatures,
          transactions == null ? List.of() : transactions
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "blockHeight",
        "blockTime",
        "numRewardPartitions",
        "blockhash",
        "previousBlockhash",
        "parentSlot",
        "rewards",
        "signatures",
        "transactions"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            blockHeight = ji.readLong();
          } else {
            ji.skip();
          }
        }
        case 1 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            blockTime = ji.readLong();
          } else {
            ji.skip();
          }
        }
        case 2 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            numRewardPartitions = ji.readLong();
          } else {
            ji.skip();
          }
        }
        case 3 -> blockHash = ji.readString();
        case 4 -> previousBlockHash = ji.readString();
        case 5 -> parentSlot = ji.readLong();
        case 6 -> rewards = TxReward.parseRewards(ji);
        case 7 -> {
          final var signatures = new ArrayList<String>(2_048);
          while (ji.readArray()) {
            signatures.add(ji.readString());
          }
          this.signatures = signatures;
        }
        case 8 -> {
          final var transactions = new ArrayList<BlockTx>(2_048);
          while (ji.readArray()) {
            transactions.add(BlockTx.parse(ji));
          }
          this.transactions = transactions;
        }
        default -> ji.skip();
      }
      return true;
    }
  }
}
