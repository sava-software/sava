package software.sava.rpc.json.http.response;

import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxSig(long slot,
                    OptionalLong blockTime,
                    Commitment confirmationStatus,
                    String signature,
                    String memo,
                    TxInstructionError instructionError) {

  public static List<TxSig> parse(final JsonIterator ji) {
    final var signatures = new ArrayList<TxSig>(1_000);
    while (ji.readArray()) {
      final var signature = ji.testObject(new Builder(), PARSER).create();
      signatures.add(signature);
    }
    return signatures;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("slot", buf, offset, len)) {
      builder.slot(ji.readLong());
    } else if (fieldEquals("blockTime", buf, offset, len)) {
      builder.blockTime(ji.readLong());
    } else if (fieldEquals("confirmationStatus", buf, offset, len)) {
      builder.confirmationStatus(ji.readString());
    } else if (fieldEquals("memo", buf, offset, len)) {
      builder.memo(ji.readString());
    } else if (fieldEquals("signature", buf, offset, len)) {
      builder.signature(ji.readString());
    } else if (fieldEquals("err", buf, offset, len)) {
      builder.error(TxInstructionError.parseError(ji));
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long slot;
    private long blockTime;
    private Commitment confirmationStatus;
    private String signature;
    private String memo;
    private TxInstructionError error;

    private Builder() {
    }

    private TxSig create() {
      return new TxSig(slot,
          blockTime <= 0 ? OptionalLong.empty() : OptionalLong.of(blockTime),
          confirmationStatus,
          signature,
          memo,
          error);
    }

    private void slot(final long slot) {
      this.slot = slot;
    }

    private void blockTime(final long blockTime) {
      this.blockTime = blockTime;
    }

    private void confirmationStatus(final String confirmationStatus) {
      this.confirmationStatus = confirmationStatus == null || confirmationStatus.isBlank() ? null : Commitment.valueOf(confirmationStatus.toUpperCase(Locale.ENGLISH));
    }

    private void signature(final String signature) {
      this.signature = signature;
    }

    private void memo(final String memo) {
      this.memo = memo;
    }

    private void error(final TxInstructionError error) {
      this.error = error;
    }
  }
}