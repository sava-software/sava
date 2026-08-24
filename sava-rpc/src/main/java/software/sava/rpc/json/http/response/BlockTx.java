package software.sava.rpc.json.http.response;

import software.sava.core.tx.TransactionSkeleton;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// One entry of a `getBlock` response's `transactions` array, requested with `transactionDetails`
/// `full` and `encoding` `base64`.
///
/// The node's `version` member is deliberately not surfaced here: the wire bytes already carry it,
/// so [#skeleton()] reports it through [TransactionSkeleton#version()] without widening this record.
/// Nodes only serve versioned entries when the request's `maxSupportedTransactionVersion` admits
/// them; a `getBlock` for a block holding a transaction above that ceiling fails with
/// [RpcCustomError.UnsupportedTransactionVersion] instead.
///
/// @param meta the transaction's status metadata, null when the node served `"meta":null`.
/// @param data the serialized transaction, null when `transaction` was not a base64 array.
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
