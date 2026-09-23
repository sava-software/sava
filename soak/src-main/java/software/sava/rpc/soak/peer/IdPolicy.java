package software.sava.rpc.soak.peer;

/// How a peer mints subscription ids, which is the half of the dialect the client's reference
/// counting actually sees.
///
/// The two real servers differ (measured 2026-08-09, recorded in the scouting summary) and sava has
/// to tolerate both, so the harness runs half its ports under each rather than picking a favourite.
enum IdPolicy {

  /// Agave: byte-identical `method + params` on one connection gets the **same** id back, and one
  /// unsubscribe cancels the single server-side stream that id names. This is where the interesting
  /// reference-counting questions live.
  DEDUPE,

  /// Helius: a fresh id for every subscribe, even for identical params.
  DISTINCT
}
