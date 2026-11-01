package software.sava.rpc.json.http.response;

/// Where a transaction's account key was resolved from, agave's `ParsedAccountSource`, served on
/// each [TxAccountKey] of a `getBlock` response requested with
/// [software.sava.rpc.json.http.request.BlockTxDetails#accounts].
public enum AccountKeySource {

  /// A static key of the message itself.
  transaction,
  /// A key a v0 message loaded from an address lookup table; agave lists these after the static
  /// keys, writable ones first.
  lookupTable
}
