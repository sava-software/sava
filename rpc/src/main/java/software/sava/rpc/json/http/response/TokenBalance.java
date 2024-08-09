package software.sava.rpc.json.http.response;

import software.sava.core.util.DecimalIntegerAmount;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TokenBalance(int accountIndex,
                           String mint,
                           String owner,
                           String programId,
                           BigInteger amount,
                           int decimals) implements DecimalIntegerAmount {

  public static TokenBalance parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  public static List<TokenBalance> parseBalances(final JsonIterator ji) {
    final var balances = new ArrayList<TokenBalance>();
    while (ji.readArray()) {
      balances.add(parse(ji));
    }
    return balances;
  }

  private static final ContextFieldBufferPredicate<Builder> TOKEN_AMOUNT_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("amount", buf, offset, len)) {
      builder.amount(ji.readBigInteger());
    } else if (fieldEquals("decimals", buf, offset, len)) {
      builder.decimals(ji.readInt());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("accountIndex", buf, offset, len)) {
      builder.accountIndex(ji.readInt());
    } else if (fieldEquals("mint", buf, offset, len)) {
      builder.mint(ji.readString());
    } else if (fieldEquals("owner", buf, offset, len)) {
      builder.owner(ji.readString());
    } else if (fieldEquals("programId", buf, offset, len)) {
      builder.programId(ji.readString());
    } else if (fieldEquals("uiTokenAmount", buf, offset, len)) {
      ji.testObject(builder, TOKEN_AMOUNT_PARSER);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private int accountIndex;
    private String mint;
    private String owner;
    private String programId;
    private BigInteger amount;
    private int decimals;

    private Builder() {
    }

    private TokenBalance create() {
      return new TokenBalance(accountIndex, mint, owner, programId, amount, decimals);
    }

    private void accountIndex(final int accountIndex) {
      this.accountIndex = accountIndex;
    }

    private void mint(final String mint) {
      this.mint = mint;
    }

    private void owner(final String owner) {
      this.owner = owner;
    }

    private void programId(final String programId) {
      this.programId = programId;
    }

    private void amount(final BigInteger amount) {
      this.amount = amount;
    }

    private void decimals(final int decimals) {
      this.decimals = decimals;
    }
  }
}
