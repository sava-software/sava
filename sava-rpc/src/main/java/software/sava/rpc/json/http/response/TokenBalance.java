package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.core.util.DecimalIntegerAmount;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TokenBalance(int accountIndex,
                           PublicKey mint,
                           PublicKey owner,
                           PublicKey programId,
                           BigInteger amount,
                           int decimals) implements DecimalIntegerAmount {

  public static TokenBalance parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  public static List<TokenBalance> parseBalances(final JsonIterator ji) {
    final var balances = new ArrayList<TokenBalance>();
    while (ji.readArray()) {
      balances.add(parse(ji));
    }
    return balances;
  }

  private static final class Parser implements FieldBufferPredicate {

    private int accountIndex;
    private PublicKey mint;
    private PublicKey owner;
    private PublicKey programId;
    private BigInteger amount;
    private int decimals;

    private Parser() {
    }

    private TokenBalance create() {
      return new TokenBalance(accountIndex, mint, owner, programId, amount, decimals);
    }

    private static final ContextFieldBufferPredicate<Parser> TOKEN_AMOUNT_PARSER = (parser, buf, offset, len, ji) -> {
      if (fieldEquals("amount", buf, offset, len)) {
        parser.amount = ji.readBigInteger();
      } else if (fieldEquals("decimals", buf, offset, len)) {
        parser.decimals = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    };

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("accountIndex", buf, offset, len)) {
        this.accountIndex = ji.readInt();
      } else if (fieldEquals("mint", buf, offset, len)) {
        this.mint = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("owner", buf, offset, len)) {
        this.owner = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("programId", buf, offset, len)) {
        this.programId = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("uiTokenAmount", buf, offset, len)) {
        ji.testObject(this, TOKEN_AMOUNT_PARSER);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
