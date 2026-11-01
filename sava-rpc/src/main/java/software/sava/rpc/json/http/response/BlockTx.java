package software.sava.rpc.json.http.response;

import software.sava.core.tx.TransactionSkeleton;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// One entry of a `getBlock` response's `transactions` array, requested with `encoding` `base64`
/// and `transactionDetails` [software.sava.rpc.json.http.request.BlockTxDetails#full] or
/// [software.sava.rpc.json.http.request.BlockTxDetails#accounts].
///
/// With `full` the node serves the serialized transaction, so [#data()] and [#skeleton()] are
/// set and [#signatures()] and [#accountKeys()] are `null`. With `accounts` it serves the
/// signatures and the annotated account keys instead of the payload, so those two lists are set,
/// [#data()] is `null`, and [#meta()] is agave's reduced status metadata: the error, fee,
/// pre/post lamport balances, pre/post token balances and, when requested, rewards. The members
/// that mode omits read as the parser's absent values: a `null` [TxMeta#innerInstructions()],
/// [TxMeta#logMessages()], [TxMeta#loadedAddresses()], [TxMeta#returnData()] and
/// [TxMeta#costUnits()], and a zero [TxMeta#computeUnitsConsumed()].
///
/// A block holding a transaction above the request's `maxSupportedTransactionVersion`, which
/// both modes send, fails with [RpcCustomError.UnsupportedTransactionVersion].
///
/// @param meta        the transaction's status metadata, null when absent or `null`.
/// @param data        the serialized transaction, null when `transaction` is absent or not an array.
/// @param version     the served transaction version, negative for `legacy` or when absent; see
///                    [#isLegacy()]. The bytes of a `full` entry carry it too, through
///                    [TransactionSkeleton#version()].
/// @param signatures  the transaction's signatures, null unless the node served the `accounts` shape.
/// @param accountKeys the annotated account keys, static keys first and then any loaded from
///                    lookup tables; null unless the node served the `accounts` shape.
public record BlockTx(TxMeta meta,
                      byte[] data,
                      int version,
                      List<String> signatures,
                      List<TxAccountKey> accountKeys) {

  /// A `full` entry with no served version, as released before the `accounts` mode; its bytes
  /// carry the version through [#skeleton()].
  public BlockTx(final TxMeta meta, final byte[] data) {
    this(meta, data, Integer.MIN_VALUE, null, null);
  }

  /// True for `legacy` or when no version was served, which includes entries from the
  /// two-argument constructor; read [#skeleton()]'s version for those.
  public boolean isLegacy() {
    return version < 0;
  }

  /// Deserializes the transaction data on each call, null if no data is present.
  public TransactionSkeleton skeleton() {
    return data == null || data.length == 0 ? null : TransactionSkeleton.deserializeSkeleton(data);
  }

  public static BlockTx parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private TxMeta meta;
    private byte[] data;
    private int version = Integer.MIN_VALUE;
    private List<String> signatures;
    private List<TxAccountKey> accountKeys;

    private Parser() {
    }

    private BlockTx create() {
      return new BlockTx(meta, data, version, signatures, accountKeys);
    }

    private static final ContextFieldBufferPredicate<Parser> ACCOUNTS_PARSER = (parser, buf, offset, len, ji) -> {
      if (fieldEquals("signatures", buf, offset, len)) {
        parser.signatures = ji.readList(JsonIterator::readString);
      } else if (fieldEquals("accountKeys", buf, offset, len)) {
        parser.accountKeys = TxAccountKey.parseKeys(ji);
      } else {
        ji.skip();
      }
      return true;
    };

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("meta", buf, offset, len)) {
        if (ji.notNull()) {
          this.meta = TxMeta.parse(ji);
        }
      } else if (fieldEquals("transaction", buf, offset, len)) {
        switch (ji.whatIsNext()) {
          case ARRAY -> {
            ji.openArray();
            this.data = ji.decodeBase64String();
            ji.skipRestOfArray();
          }
          case OBJECT -> ji.testObject(this, ACCOUNTS_PARSER);
          default -> ji.skip();
        }
      } else if (fieldEquals("version", buf, offset, len)) {
        this.version = ji.readIntOr(this.version);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
