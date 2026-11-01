package software.sava.rpc.json.http.request;

/// The `transactionDetails` of a `getBlock` request, agave's `TransactionDetails`, named on the
/// wire by each constant's own name.
public enum BlockTxDetails {

  /// Every transaction as serialized bytes with its full status metadata; see
  /// [software.sava.rpc.json.http.response.BlockTx#data()].
  full,
  /// Neither transactions nor signatures.
  none,
  /// Only the transaction signatures; see [software.sava.rpc.json.http.response.Block#signatures()].
  signatures,
  /// Each transaction's signatures and annotated account keys with reduced status metadata, and
  /// no instruction payloads; see [software.sava.rpc.json.http.response.BlockTx#accountKeys()].
  /// Like `full`, the request names the client's maximum supported transaction version, which the
  /// node enforces for this mode as well.
  accounts
}
