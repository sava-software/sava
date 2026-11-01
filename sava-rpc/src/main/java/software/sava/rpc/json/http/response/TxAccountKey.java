package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// One annotated account key of a transaction served by `getBlock` with
/// [software.sava.rpc.json.http.request.BlockTxDetails#accounts], agave's `ParsedAccount`: the
/// key with the privileges agave resolves for it, through address lookup tables for v0 messages:
/// `signer` from the header, and `writable` as the runtime's effective write lock, which is false
/// for reserved accounts, and for invoked program ids unless the BPF upgradeable loader is among
/// the message's keys, even when the header marks them writable.
///
/// @param source where the key came from, `null` when the node omits or nulls the member, or
///               serves a value this release does not recognize.
public record TxAccountKey(PublicKey pubKey,
                           boolean writable,
                           boolean signer,
                           AccountKeySource source) {

  public static TxAccountKey parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  public static List<TxAccountKey> parseKeys(final JsonIterator ji) {
    return ji.readList(TxAccountKey::parse);
  }

  private static final CharBufferFunction<AccountKeySource> SOURCE_PARSER = (buf, offset, len) -> {
    if (fieldEquals("transaction", buf, offset, len)) {
      return AccountKeySource.transaction;
    } else if (fieldEquals("lookupTable", buf, offset, len)) {
      return AccountKeySource.lookupTable;
    } else {
      return null;
    }
  };

  private static final class Parser implements FieldBufferPredicate {

    private PublicKey pubKey;
    private boolean writable;
    private boolean signer;
    private AccountKeySource source;

    private Parser() {
    }

    private TxAccountKey create() {
      return new TxAccountKey(pubKey, writable, signer, source);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("pubkey", buf, offset, len)) {
        pubKey = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("writable", buf, offset, len)) {
        writable = ji.readBoolean();
      } else if (fieldEquals("signer", buf, offset, len)) {
        signer = ji.readBoolean();
      } else if (fieldEquals("source", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.STRING) {
          source = ji.applyChars(SOURCE_PARSER);
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
