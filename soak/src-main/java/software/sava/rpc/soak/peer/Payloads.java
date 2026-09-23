package software.sava.rpc.soak.peer;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Every byte either peer puts on the wire.
///
/// The shapes are taken from sava-rpc's own fixtures — `src/test/resources/fuzz/ws/*` for the
/// notifications and `src/test/resources/rpc_response_data/*` plus `fuzz/responses/*` for the HTTP
/// bodies — so the parsers under test meet real structure (including `rentEpoch`
/// 18446744073709551615, which is `u64::MAX` on the wire and a `BigInteger` in the parser) rather
/// than a shape invented to be easy.
///
/// The one rule that governs the whole class: **a response is a pure function of its inputs**. No
/// clock, no `ThreadLocalRandom`, no `HashMap` iteration order. That is what lets `PeerMain`'s
/// start-up self-check build the first 64 of everything twice and compare bytes, and what lets the
/// client assert association and gaplessness instead of merely asserting nothing threw.
final class Payloads {

  /// The `MESSAGE_OVERFLOW` fault's size. Above the `OVERFLOW` engine's `maxMessageLength`
  /// (`1 << 16` = 65,536 chars, `DESIGN.md` §13) and far below the peer's own 16 MiB frame cap, so
  /// the abort under test is the engine's and not the transport's.
  static final int OVERFLOW_CHARS = 70_000;

  /// The payload size of a `MESSAGE_OVERFLOW` notification: base64 expands 4/3, so this many
  /// bytes make a message of at least [#OVERFLOW_CHARS] chars once the envelope is added. The
  /// message is otherwise an ordinary, checksum-valid notification for a live subscription.
  static final int OVERFLOW_PAYLOAD_BYTES = OVERFLOW_CHARS * 3 / 4;

  /// The subscription id `UNKNOWN_SUB_NOTIFICATION` uses: never granted by any port, in any run.
  static final long UNKNOWN_SUB_ID = 999_999L;

  /// How far the `gzip-wrong-twin` control (`controls.tsv`) moves the account it serves under
  /// `Content-Encoding: gzip`: `space`, by one, and nothing else.
  ///
  /// `space` is the one member of W3-C's projection (`RpcOracle.keyProjection` = key hash, owner,
  /// space) that no other oracle reads. The stamp still carries `fnv64` of the key that was asked
  /// for and the owner is still [Stamp#OWNER], so W3-A stays clean and the only property that can
  /// see this control is the plain-vs-gzip differential it is named for. A control that instead
  /// broke the key hash would fail W3-A too, and a sheet cannot then tell a decoding defect from a
  /// misrouted response — which is the distinction W3-C exists to draw.
  static final int WRONG_TWIN_SPACE_DELTA = 1;

  static final String API_VERSION = "2.3.7";

  /// `u64::MAX`, the value a real node reports for a rent-exempt account.
  private static final String RENT_EPOCH = "18446744073709551615";

  private static final long SLOTS_IN_EPOCH = 432_000L;

  /// One `getBlock` `transactions` entry, copied verbatim from the first entry of
  /// `rpc_response_data/getBlock.json.zip` (mainnet, blockHeight 339954329). Kept small and
  /// repeated `SOAK_BLOCK_TXS` times rather than generated: the point is that `BlockTx.parse` and
  /// `TransactionSkeleton.deserializeSkeleton` meet a real serialized transaction, and one real one
  /// repeated does that as well as four hundred different ones while keeping the response a pure
  /// function of the requested slot.
  private static final String BLOCK_TX_TEMPLATE =
      "{\"meta\":{\"computeUnitsConsumed\":1597,\"costUnits\":3030,\"err\":null,\"fee\":6109,\"innerInstructions\":[]"
      + ",\"loadedAddresses\":{\"readonly\":[],\"writable\":[]},\"logMessages\":[\"Program ComputeBudget111111111111111"
      + "111111111111111 invoke [1]\",\"Program ComputeBudget111111111111111111111111111111 success\",\"Program TessVdM"
      + "L9pBGgG9yGks7o4HewRaXVAMuoVj4x83GLQH invoke [1]\",\"Program TessVdML9pBGgG9yGks7o4HewRaXVAMuoVj4x83GLQH consum"
      + "ed 1297 of 1450 compute units\",\"Program TessVdML9pBGgG9yGks7o4HewRaXVAMuoVj4x83GLQH success\",\"Program Comp"
      + "uteBudget111111111111111111111111111111 invoke [1]\",\"Program ComputeBudget111111111111111111111111111111 suc"
      + "cess\"],\"postBalances\":[59065304429,9688320,1,1141443,72184175],\"postTokenBalances\":[],\"preBalances\":[59"
      + "065310538,9688320,1,1141443,72184175],\"preTokenBalances\":[],\"rewards\":[],\"status\":{\"Ok\":null}},\"trans"
      + "action\":[\"AUfk7jKtAVV62pdSkWQtktQ6fVDeK3kNWU/NKVwLQp42kcnpu2N69XUcXLGlybPGLi39Y08CYjsvLYhS2a8ZzwCAAQADBddjy8"
      + "++ND78u8tFUHrIlfR8617fIkPS5yvmmHS322bP0M0zWZ0dL73vhQoLp7sVDzOorogUR/HE3y9vlq4imWwDBkZv5SEXMv/srbpyw5vnvIzlu8X3"
      + "EmssQ5s6QAAAAAbT7cTlc62v8+VziYtUWx7o0o0FjtlOiOC8szJXG+oKca0m5k6X6QmN+73+9ouxpcfQpxYmK3Y/XTCI/4aBC+AAFmtNm8GF9G"
      + "S8UD4APvWBOy+bKwhyEXlfukUcW91KGwMCAAUCQAYAAAMDBAABmQMNM2bVBAAAAABbPZAVAAAAAAA0aYf/MgMAAAAAAAAAAAAYAYil/fEDAAAA"
      + "AAAAAAAACwAAADs+DwAAAAAARDqgBgAAAAD6PQ8AAAAAAGWwISEAAAAAQD0PAAAAAADohkZCAAAAAIc8DwAAAAAA7jC4pQAAAAArPA8AAAAAAM"
      + "1BeEsBAAAAuDoPAAAAAADohi+XAgAAANM3DwAAAAAAeZ+tlwIAAADvNA8AAAAAAAigVzAFAAAA/TAPAAAAAACkdjn8DAAAAKksDwAAAAAASyLu"
      + "/wwAAACyDg8AAAAAAA8CxZpOAAAACwAAADw+DwAAAAAAFlD0BQAAAAD8PQ8AAAAAALARxR0AAAAAQj0PAAAAAAAyT4c7AAAAAIk8DwAAAAAA3D"
      + "PLlAAAAAAsPA8AAAAAANhVjykBAAAAuToPAAAAAAAGIOZSAgAAANU3DwAAAAAAzRh1UgIAAADxNA8AAAAAAB9OCKQEAAAA/zAPAAAAAADHPBKX"
      + "CwAAAKosDwAAAAAAPG/EkwsAAACn3w4AAAAAAAEjAlkLAAAAAgAJA4WTCgAAAAAAAA==\",\"base64\"],\"version\":0}";

  private final long seed;
  private final int blockTxs;
  private final int pgaAccounts;
  private final int largeBytes;
  private final Base64.Encoder base64;
  /// Built once per transaction count: the array text is the same string repeated, so rebuilding it
  /// per request would be the peer's own bottleneck rather than the client's.
  private final Map<Integer, String> blockTransactions;

  Payloads(final long seed, final int blockTxs, final int pgaAccounts, final int largeBytes) {
    this.seed = seed;
    this.blockTxs = blockTxs;
    this.pgaAccounts = pgaAccounts;
    this.largeBytes = largeBytes;
    this.base64 = Base64.getEncoder();
    this.blockTransactions = new ConcurrentHashMap<>();
  }

  int blockTxs() {
    return blockTxs;
  }

  int pgaAccounts() {
    return pgaAccounts;
  }

  int largeBytes() {
    return largeBytes;
  }

  // --- websocket notifications ------------------------------------------------------------------

  /// The channels a subscription can belong to, derived from the subscribe method name.
  enum Channel {
    ACCOUNT, PROGRAM, LOGS, SLOT, ROOT, SIGNATURE, TRANSACTION;

    static Channel forMethod(final String method) {
      return switch (method) {
        case "accountSubscribe" -> ACCOUNT;
        case "programSubscribe" -> PROGRAM;
        case "logsSubscribe" -> LOGS;
        case "slotSubscribe", "slotsUpdatesSubscribe" -> SLOT;
        case "rootSubscribe" -> ROOT;
        case "signatureSubscribe" -> SIGNATURE;
        default -> TRANSACTION;
      };
    }
  }

  /// The notification for one `(channel, subId, seq)`, with the sequence in the field `DESIGN.md`
  /// §7 names for that channel. `key` is the base58 key the subscription was granted for, used by
  /// `programNotification`'s `value.pubkey`.
  String notification(final Channel channel,
                      final long subId,
                      final long seq,
                      final String key,
                      final int payloadBytes,
                      final boolean terminalSignature) {
    return switch (channel) {
      case ACCOUNT -> accountNotification(subId, seq, payloadBytes);
      case PROGRAM -> programNotification(subId, seq, key, payloadBytes);
      case LOGS -> logsNotification(subId, seq);
      case SLOT -> slotNotification(subId, seq);
      case ROOT -> rootNotification(subId, seq);
      case SIGNATURE -> signatureNotification(subId, seq, terminalSignature);
      case TRANSACTION -> transactionNotification(subId, seq);
    };
  }

  /// `value.lamports = seq`, `value.data[0]` = base64 of `Stamp.payload`, `context.slot = BASE + seq`.
  String accountNotification(final long subId, final long seq, final int payloadBytes) {
    final long slot = Stamp.BASE_SLOT + seq;
    final byte[] payload = Stamp.payload(seed, subId, seq, payloadBytes);
    return "{\"jsonrpc\":\"2.0\",\"method\":\"accountNotification\",\"params\":{\"result\":{\"context\":{\"apiVersion\":\""
        + API_VERSION + "\",\"slot\":" + slot + "},\"value\":" + accountValue(payload, seq) + "},\"subscription\":" + subId + "}}";
  }

  /// As `accountNotification`, plus `value.pubkey` = the key the subscription was granted for, which
  /// is what makes the `WRONG_KEY_ECHO` control (D4) detectable from the client side.
  String programNotification(final long subId, final long seq, final String pubKey, final int payloadBytes) {
    final long slot = Stamp.BASE_SLOT + seq;
    final byte[] payload = Stamp.payload(seed, subId, seq, payloadBytes);
    return "{\"jsonrpc\":\"2.0\",\"method\":\"programNotification\",\"params\":{\"result\":{\"context\":{\"slot\":"
        + slot + "},\"value\":{\"pubkey\":\"" + pubKey + "\",\"account\":" + accountValue(payload, seq)
        + "}},\"subscription\":" + subId + "}}";
  }

  private String accountValue(final byte[] payload, final long seq) {
    return "{\"data\":[\"" + base64.encodeToString(payload) + "\",\"base64\"],\"executable\":false,\"lamports\":"
        + seq + ",\"owner\":\"" + Stamp.OWNER + "\",\"rentEpoch\":" + RENT_EPOCH + ",\"space\":" + payload.length + "}";
  }

  /// `result.slot = BASE + seq`, `parent = slot - 1`, `root = slot - 32`.
  String slotNotification(final long subId, final long seq) {
    final long slot = Stamp.BASE_SLOT + seq;
    return "{\"jsonrpc\":\"2.0\",\"method\":\"slotNotification\",\"params\":{\"result\":{\"parent\":" + (slot - 1)
        + ",\"root\":" + (slot - 32) + ",\"slot\":" + slot + "},\"subscription\":" + subId + "}}";
  }

  /// `result = BASE + seq`: a bare number, which is the one notification shape with no envelope
  /// around the sequence at all.
  String rootNotification(final long subId, final long seq) {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"rootNotification\",\"params\":{\"result\":" + (Stamp.BASE_SLOT + seq)
        + ",\"subscription\":" + subId + "}}";
  }

  /// `context.slot = BASE + seq` and `value.logs[0] = "soak seq=<seq>"`, so the sequence survives in
  /// a channel whose payload is free-form text.
  String logsNotification(final long subId, final long seq) {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"logsNotification\",\"params\":{\"result\":{\"context\":{\"slot\":"
        + (Stamp.BASE_SLOT + seq) + "},\"value\":{\"signature\":\"" + Stamp.signature(seq)
        + "\",\"err\":null,\"logs\":[\"soak seq=" + seq + "\"]}},\"subscription\":" + subId + "}}";
  }

  /// `"receivedSignature"` first, then the terminal `{"err":null}`. No transaction is ever built or
  /// submitted anywhere in this harness; the channel is exercised entirely synthetically.
  String signatureNotification(final long subId, final long seq, final boolean terminal) {
    final var value = terminal ? "{\"err\":null}" : "\"receivedSignature\"";
    return "{\"jsonrpc\":\"2.0\",\"method\":\"signatureNotification\",\"params\":{\"result\":{\"context\":{\"slot\":"
        + (Stamp.BASE_SLOT + seq) + "},\"value\":" + value + "},\"subscription\":" + subId + "}}";
  }

  /// The Helius-shaped generic `transactionNotification`, parsed by a caller-supplied parser on the
  /// client side (`SolanaRpcWebsocket.subscribe(...)`), so its shape is the harness's own contract.
  String transactionNotification(final long subId, final long seq) {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"transactionNotification\",\"params\":{\"result\":{\"slot\":"
        + (Stamp.BASE_SLOT + seq) + ",\"signature\":\"" + Stamp.signature(seq) + "\"},\"subscription\":" + subId + "}}";
  }

  /// A notification for a subscription id no port ever granted: the client must tolerate it and the
  /// connection must survive (`W1-F`).
  String unknownSubNotification(final long seq) {
    return accountNotification(UNKNOWN_SUB_ID, seq, 64);
  }

  /// One message above the `OVERFLOW` engine's `maxMessageLength`: an ordinary account
  /// notification whose payload is [#OVERFLOW_PAYLOAD_BYTES] of checksum-valid bytes. The abort
  /// under test is the length check, and on an engine whose cap is larger the message is simply
  /// delivered and verified like any other large notification.
  String overflowMessage(final long subId, final long seq) {
    return accountNotification(subId, seq, OVERFLOW_PAYLOAD_BYTES);
  }

  // --- websocket JSON-RPC replies ---------------------------------------------------------------

  static String subConfirmation(final long msgId, final long subId) {
    return "{\"jsonrpc\":\"2.0\",\"result\":" + subId + ",\"id\":" + msgId + "}";
  }

  static String boolResult(final long msgId, final boolean result) {
    return "{\"jsonrpc\":\"2.0\",\"result\":" + result + ",\"id\":" + msgId + "}";
  }

  static String error(final long msgId, final int code, final String message) {
    return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"},\"id\":" + msgId + "}";
  }

  // --- HTTP responses ---------------------------------------------------------------------------

  /// `data[0]` decodes to the 32-byte stamp whose `[8..16)` is `fnv64(pubkey)`; the client asserts
  /// that against the key **it** asked for, which is the whole of W3-A.
  String getAccountInfo(final long requestId, final long seq, final String pubKey) {
    return getAccountInfo(requestId, seq, pubKey, 0);
  }

  /// As [#getAccountInfo(long,long,String)], with `spaceDelta` added to the reported `space`.
  ///
  /// Only the `gzip-wrong-twin` control passes a non-zero delta, and only on the representation it
  /// serves gzipped ([#WRONG_TWIN_SPACE_DELTA] says why `space` and not the stamp). The response is
  /// still a pure function of its arguments, so the start-up determinism self-check holds either
  /// way: the delta is a run-level constant chosen before the peer accepts anything.
  String getAccountInfo(final long requestId, final long seq, final String pubKey, final int spaceDelta) {
    return "{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"apiVersion\":\"" + API_VERSION + "\",\"slot\":"
        + (Stamp.BASE_SLOT + seq) + "},\"value\":" + stampedAccountBody(requestId, seq, pubKey, spaceDelta)
        + "},\"id\":" + requestId + "}";
  }

  String getMultipleAccounts(final long requestId, final long seq, final List<String> pubKeys) {
    final var sb = new StringBuilder(128 + pubKeys.size() * 256);
    sb.append("{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"apiVersion\":\"").append(API_VERSION)
        .append("\",\"slot\":").append(Stamp.BASE_SLOT + seq).append("},\"value\":[");
    for (int i = 0; i < pubKeys.size(); ++i) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(stampedAccountBody(requestId, seq, pubKeys.get(i)));
    }
    return sb.append("]},\"id\":").append(requestId).append('}').toString();
  }

  /// `N = SOAK_PGA_ACCOUNTS` accounts whose keys are `Stamp.derivedKey(program, i)` and whose stamp
  /// carries `fnv64(program)`: the client can check both the count and every key without holding a
  /// copy of the response.
  String getProgramAccounts(final long requestId, final long seq, final String program, final int accounts) {
    final var account = stampedAccountBody(requestId, seq, program);
    final var sb = new StringBuilder(128 + accounts * (account.length() + 64));
    sb.append("{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"apiVersion\":\"").append(API_VERSION)
        .append("\",\"slot\":").append(Stamp.BASE_SLOT + seq).append("},\"value\":[");
    for (int i = 0; i < accounts; ++i) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"pubkey\":\"").append(Stamp.derivedKey(program, i)).append("\",\"account\":").append(account).append('}');
    }
    return sb.append("]},\"id\":").append(requestId).append('}').toString();
  }

  private String stampedAccountBody(final long requestId, final long seq, final String pubKey) {
    return stampedAccountBody(requestId, seq, pubKey, 0);
  }

  /// `space` is normally the stamp's own length; `spaceDelta` is the `gzip-wrong-twin` control's
  /// single disagreement between the identity and gzip representations of one account.
  private String stampedAccountBody(final long requestId, final long seq, final String pubKey, final int spaceDelta) {
    final byte[] stamp = Stamp.accountStamp(requestId, pubKey, seq);
    return "{\"data\":[\"" + base64.encodeToString(stamp) + "\",\"base64\"],\"executable\":false,\"lamports\":" + seq
        + ",\"owner\":\"" + Stamp.OWNER + "\",\"rentEpoch\":" + RENT_EPOCH + ",\"space\":"
        + (stamp.length + spaceDelta) + "}";
  }

  /// `blockHeight == slot` and `parentSlot == slot - 1`, so a block response that answered another
  /// request is visible without a second round trip.
  String getBlock(final long requestId, final long slot, final int transactions) {
    return "{\"jsonrpc\":\"2.0\",\"result\":{\"blockHeight\":" + slot
        + ",\"blockTime\":" + (1_700_000_000L + slot)
        + ",\"blockhash\":\"" + Stamp.blockHash("block", slot)
        + "\",\"parentSlot\":" + (slot - 1)
        + ",\"previousBlockhash\":\"" + Stamp.blockHash("block", slot - 1)
        + "\",\"numRewardPartitions\":0,\"rewards\":[],\"transactions\":["
        + blockTransactions(transactions) + "]},\"id\":" + requestId + "}";
  }

  private String blockTransactions(final int count) {
    return blockTransactions.computeIfAbsent(count, n -> {
      final var sb = new StringBuilder(n * (BLOCK_TX_TEMPLATE.length() + 1));
      for (int i = 0; i < n; ++i) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(BLOCK_TX_TEMPLATE);
      }
      return sb.toString();
    });
  }

  /// `slot_i = BASE_SLOT + (fnv64(sig_i) & 0xFFFFF)`: derived from the signature the client sent,
  /// so a status that landed on the wrong future names another signature's slot.
  String getSignatureStatuses(final long requestId, final long seq, final List<String> signatures) {
    final var sb = new StringBuilder(128 + signatures.size() * 128);
    sb.append("{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"slot\":").append(Stamp.BASE_SLOT + seq).append("},\"value\":[");
    for (int i = 0; i < signatures.size(); ++i) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"slot\":").append(Stamp.BASE_SLOT + (Stamp.fnv64(signatures.get(i)) & 0xFFFFFL))
          .append(",\"confirmations\":null,\"err\":null,\"status\":{\"Ok\":null},\"confirmationStatus\":\"finalized\"}");
    }
    return sb.append("]},\"id\":").append(requestId).append('}').toString();
  }

  String getLatestBlockHash(final long requestId, final long seq) {
    return "{\"jsonrpc\":\"2.0\",\"result\":{\"context\":{\"apiVersion\":\"" + API_VERSION + "\",\"slot\":"
        + (Stamp.BASE_SLOT + seq) + "},\"value\":{\"blockhash\":\"" + Stamp.blockHash("blockhash", requestId)
        + "\",\"lastValidBlockHeight\":" + (Stamp.BASE_SLOT + seq + 300) + "}},\"id\":" + requestId + "}";
  }

  String getSlot(final long requestId, final long seq) {
    return "{\"jsonrpc\":\"2.0\",\"result\":" + (Stamp.BASE_SLOT + seq) + ",\"id\":" + requestId + "}";
  }

  String getBlockHeight(final long requestId, final long seq) {
    return "{\"jsonrpc\":\"2.0\",\"result\":" + (Stamp.BASE_SLOT + seq) + ",\"id\":" + requestId + "}";
  }

  String getEpochInfo(final long requestId, final long seq) {
    final long slot = Stamp.BASE_SLOT + seq;
    return "{\"jsonrpc\":\"2.0\",\"result\":{\"absoluteSlot\":" + slot + ",\"blockHeight\":" + slot
        + ",\"epoch\":" + (slot / SLOTS_IN_EPOCH) + ",\"slotIndex\":" + (slot % SLOTS_IN_EPOCH)
        + ",\"slotsInEpoch\":" + SLOTS_IN_EPOCH + ",\"transactionCount\":" + (slot * 7) + "},\"id\":" + requestId + "}";
  }

  String getVersion(final long requestId) {
    return "{\"jsonrpc\":\"2.0\",\"result\":{\"solana-core\":\"" + API_VERSION
        + "\",\"feature-set\":3294202280},\"id\":" + requestId + "}";
  }

  String getHealth(final long requestId) {
    return "{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":" + requestId + "}";
  }

  static String rpcError(final long requestId, final int code, final String message) {
    return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"},\"id\":" + requestId + "}";
  }
}
