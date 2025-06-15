package software.sava.rpc.json.http.response;

import software.sava.core.util.DecimalIntegerAmount;
import software.sava.core.util.LamportDecimal;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigDecimal;
import java.math.BigInteger;

public record Lamports(Context context, long lamports) implements DecimalIntegerAmount {

  public static Lamports parse(final JsonIterator ji, final Context context) {
    return new Lamports(context, ji.readLong());
  }

  @Override
  public int decimals() {
    return LamportDecimal.LAMPORT_DIGITS;
  }

  @Override
  public BigInteger amount() {
    return lamports < 0 ? new BigInteger(Long.toUnsignedString(lamports)) : BigInteger.valueOf(lamports);
  }

  @Override
  public long asLong() {
    return lamports;
  }

  @Override
  public BigDecimal toDecimal() {
    return toDecimal(lamports);
  }
}
