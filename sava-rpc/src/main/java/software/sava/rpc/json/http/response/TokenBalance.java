package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.core.util.DecimalIntegerAmount;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Supplier;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TokenBalance(int accountIndex,
                           PublicKey mint,
                           PublicKey owner,
                           PublicKey programId,
                           BigInteger amount,
                           int decimals) implements DecimalIntegerAmount {

  public static TokenBalance parse(final JsonIterator ji) {
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  public static List<TokenBalance> parseBalances(final JsonIterator ji) {
    return ji.readList(TokenBalance::parse);
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<TokenBalance> {

    private int accountIndex;
    private PublicKey mint;
    private PublicKey owner;
    private PublicKey programId;
    private BigInteger amount;
    private int decimals;

    private Parser() {
    }

    @Override
    public TokenBalance get() {
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

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "accountIndex",
        "mint",
        "owner",
        "programId",
        "uiTokenAmount"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> this.accountIndex = ji.readInt();
        case 1 -> this.mint = PublicKeyEncoding.parseBase58Encoded(ji);
        case 2 -> this.owner = PublicKeyEncoding.parseBase58Encoded(ji);
        case 3 -> this.programId = PublicKeyEncoding.parseBase58Encoded(ji);
        case 4 -> ji.testObject(this, TOKEN_AMOUNT_PARSER);
        default -> ji.skip();
      }
      return true;
    }
  }
}
