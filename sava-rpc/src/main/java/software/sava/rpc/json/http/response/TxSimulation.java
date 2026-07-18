package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.SequencedCollection;

import static java.util.Objects.requireNonNullElse;
import static software.sava.rpc.json.http.response.AccountInfo.*;
import static software.sava.rpc.json.http.response.JsonUtil.parseEncodedData;
import static software.sava.rpc.json.http.response.TxMeta.parseLamportBalances;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxSimulation(Context context,
                           TransactionError error,
                           OptionalLong fee,
                           int loadedAccountsDataSize,
                           List<String> logs,
                           List<Long> preBalances,
                           List<Long> postBalances,
                           List<TokenBalance> preTokenBalances,
                           List<TokenBalance> postTokenBalances,
                           List<AccountInfo<byte[]>> accounts,
                           List<InnerInstructions> innerInstructions,
                           ReplacementBlockHash replacementBlockHash,
                           OptionalInt unitsConsumed,
                           // return data
                           PublicKey programId,
                           byte[] data) {

  public static TxSimulation parse(final SequencedCollection<PublicKey> accounts,
                                   final JsonIterator ji,
                                   final Context context) {
    final var parser = new Parser(context, accounts);
    ji.testObject(Parser.FIELDS, parser);
    return parser.create();
  }

  public static TxSimulation parse(final JsonIterator ji) {
    return parse(null, ji, null);
  }

  private static final ContextFieldBufferPredicate<Parser> RETURN_DATA_PARSER = (parser, buf, offset, len, ji) -> {
    if (fieldEquals("programId", buf, offset, len)) {
      parser.programId = PublicKeyEncoding.parseBase58Encoded(ji);
    } else if (fieldEquals("data", buf, offset, len)) {
      parser.data = parseEncodedData(ji);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final OptionalInt NO_BUDGET = OptionalInt.empty();
  private static final OptionalLong NO_FEE = OptionalLong.empty();
  static final List<String> NO_LOGS = java.util.List.of();
  static final List<AccountInfo<byte[]>> NO_ACCOUNTS = List.of();
  static final List<InnerInstructions> NO_INNER_INSTRUCTIONS = List.of();

  private static final class Parser extends RootBuilder implements FieldIndexPredicate {

    private final SequencedCollection<PublicKey> accountPubKeys;
    private TransactionError error;
    private long fee = -1;
    private int loadedAccountsDataSize;
    private List<String> logs;
    private List<Long> preBalances;
    private List<Long> postBalances;
    private List<TokenBalance> preTokenBalances;
    private List<TokenBalance> postTokenBalances;
    private List<InnerInstructions> innerInstructions;
    private List<AccountInfo<byte[]>> accounts;
    private ReplacementBlockHash replacementBlockHash;
    private int unitsConsumed = -1;
    private PublicKey programId;
    private byte[] data;

    private Parser(final Context context, final SequencedCollection<PublicKey> accountPubKeys) {
      super(context);
      this.accountPubKeys = accountPubKeys;
    }

    private TxSimulation create() {
      return new TxSimulation(
          context,
          error,
          fee < 0 ? NO_FEE : OptionalLong.of(fee),
          loadedAccountsDataSize,
          logs == null || logs.isEmpty() ? NO_LOGS : logs,
          preBalances,
          postBalances,
          preTokenBalances,
          postTokenBalances,
          accounts == null || accounts.isEmpty() ? NO_ACCOUNTS : accounts,
          requireNonNullElse(innerInstructions, NO_INNER_INSTRUCTIONS),
          replacementBlockHash,
          unitsConsumed < 0 ? NO_BUDGET : OptionalInt.of(unitsConsumed),
          programId,
          data
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "err",
        "fee",
        "loadedAccountsDataSize",
        "accounts",
        "logs",
        "preBalances",
        "postBalances",
        "preTokenBalances",
        "postTokenBalances",
        "unitsConsumed",
        "returnData",
        "innerInstructions",
        "replacementBlockhash"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> error = TransactionError.parseError(ji);
        case 1 -> fee = ji.readLongOr(fee);
        case 2 -> loadedAccountsDataSize = ji.readInt();
        case 3 -> {
          if (accountPubKeys == null || accountPubKeys.isEmpty()) {
            ji.skip();
          } else {
            accounts = parseAccountsFromKeysWithNulls(accountPubKeys, ji, context, BYTES_IDENTITY);
          }
        }
        case 4 -> this.logs = ji.readList(JsonIterator::readString);
        case 5 -> this.preBalances = parseLamportBalances(ji);
        case 6 -> this.postBalances = parseLamportBalances(ji);
        case 7 -> this.preTokenBalances = TokenBalance.parseBalances(ji);
        case 8 -> this.postTokenBalances = TokenBalance.parseBalances(ji);
        case 9 -> unitsConsumed = ji.readIntOr(unitsConsumed);
        case 10 -> ji.testObject(this, RETURN_DATA_PARSER);
        case 11 -> innerInstructions = InnerInstructions.parseInstructions(ji);
        case 12 -> replacementBlockHash = ReplacementBlockHash.parse(ji);
        default -> ji.skip();
      }
      return true;
    }
  }
}
