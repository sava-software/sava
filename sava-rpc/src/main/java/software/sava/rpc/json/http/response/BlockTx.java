package software.sava.rpc.json.http.response;

import software.sava.core.tx.TransactionSkeleton;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// One entry of a `getBlock` response's `transactions` array, requested with `transactionDetails`
/// `full` and `encoding` `base64`.
///
/// The node's `version` member is not parsed; read it from [#skeleton()] with
/// [TransactionSkeleton#version()]. A block holding a transaction above the request's
/// `maxSupportedTransactionVersion` fails with [RpcCustomError.UnsupportedTransactionVersion].
///
/// @param meta the transaction's status metadata, null when absent or `null`.
/// @param data the serialized transaction, null when `transaction` is absent or not an array.
public record BlockTx(TxMeta meta, byte[] data) {

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

    private Parser() {
    }

    private BlockTx create() {
      return new BlockTx(meta, data);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("meta", buf, offset, len)) {
        if (ji.notNull()) {
          this.meta = TxMeta.parse(ji);
        }
      } else if (fieldEquals("transaction", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.ARRAY) {
          ji.openArray();
          this.data = ji.decodeBase64String();
          ji.skipRestOfArray();
        } else {
          ji.skip();
        }
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
