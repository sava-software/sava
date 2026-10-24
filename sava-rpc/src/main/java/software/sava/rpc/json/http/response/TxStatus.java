package software.sava.rpc.json.http.response;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.*;

/// One signature's status as `getSignatureStatuses` returns it; a signature the node does not
/// know is the [#nil()] record.
///
/// @param confirmations      TowerBFT's count of blocks since the transaction's block was
///                           confirmed, empty once the block is rooted. An agave node running
///                           Alpenglow, which every public cluster has since October 2026, never
///                           fills the lockout map this reads, so it is 0 until the block is
///                           finalized and empty after: it says whether the transaction is
///                           finalized and nothing else.
/// @param confirmationStatus The cluster confirmation level, or `null` for a level this client
///                           cannot parse. Under Alpenglow `confirmed` and `finalized` both move
///                           when the node roots the block, so a status can go from `PROCESSED`
///                           to `FINALIZED` with `CONFIRMED` at most transient; a caller polling
///                           for `CONFIRMED` exactly can miss it, so accept either settled level.
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
        case 1 -> this.confirmations = ji.readIntOr(this.confirmations);
        case 2 -> this.error = TransactionError.parseError(ji);
        case 3 -> this.confirmationStatus = ji.applyChars(Commitment.PARSER);
        default -> ji.skip();
      }
      return true;
    }
  }
}
