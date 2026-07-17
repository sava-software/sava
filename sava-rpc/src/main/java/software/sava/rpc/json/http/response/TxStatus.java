package software.sava.rpc.json.http.response;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.*;

public record TxStatus(Context context,
                       long slot,
                       OptionalInt confirmations,
                       TransactionError error,
                       Commitment confirmationStatus) {

  public boolean nil() {
    return slot == 0
        && confirmations.isEmpty()
        && error == null
        && confirmationStatus == null;
  }

  public static Map<String, TxStatus> parse(final SequencedCollection<String> txIds,
                                            final JsonIterator ji,
                                            final Context context) {
    final var statuses = HashMap.<String, TxStatus>newHashMap(txIds.size());
    final var iterator = txIds.iterator();
    TxStatus nil = null;
    while (ji.readArray()) {
      final var signature = iterator.next();

      final TxStatus sigStatus;
      if (ji.whatIsNext() == ValueType.NULL) {
        ji.skip();
        if (nil == null) {
          nil = new TxStatus(context, 0, OptionalInt.empty(), null, null);
        }
        sigStatus = nil;
      } else {
        final var parser = new Parser();
        ji.testObject(Parser.FIELDS, parser);
        sigStatus = parser.create(context);
      }

      statuses.put(signature, sigStatus);
    }
    return statuses;
  }

  public static List<TxStatus> parseList(final JsonIterator ji, final Context context) {
    final var statuses = new ArrayList<TxStatus>(SolanaRpcClient.MAX_SIG_STATUS);
    while (ji.readArray()) {
      final var parser = new Parser();
      ji.testObject(Parser.FIELDS, parser);
      statuses.add(parser.create(context));
    }
    return statuses;
  }

  private static final class Parser implements FieldIndexPredicate {

    private long slot;
    private int confirmations = -1;
    private TransactionError error;
    private Commitment confirmationStatus;

    private TxStatus create(final Context context) {
      return new TxStatus(
          context,
          slot,
          confirmations < 0 ? OptionalInt.empty() : OptionalInt.of(confirmations),
          error,
          confirmationStatus
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "slot",
        "confirmations",
        "err",
        "confirmationStatus"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> this.slot = ji.readLong();
        case 1 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            this.confirmations = ji.readInt();
          } else {
            ji.skip();
          }
        }
        case 2 -> this.error = TransactionError.parseError(ji);
        case 3 -> this.confirmationStatus = ji.applyChars(Commitment.PARSER);
        default -> ji.skip();
      }
      return true;
    }
  }
}
