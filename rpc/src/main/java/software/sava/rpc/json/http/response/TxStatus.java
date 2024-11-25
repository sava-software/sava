package software.sava.rpc.json.http.response;

import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.*;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxStatus(Context context,
                       long slot,
                       OptionalInt confirmations,
                       TransactionError error,
                       Commitment confirmationStatus,
                       @Deprecated
                       Boolean deprecatedOkayStatus,
                       @Deprecated
                       String deprecatedError,
                       @Deprecated
                       Map<String, String> unhandledFields) {

  public static Map<String, TxStatus> parse(final List<String> txIds, final JsonIterator ji, final Context context) {
    final var statuses = HashMap.<String, TxStatus>newHashMap(txIds.size());
    for (int i = 0; ji.readArray(); ++i) {
      final var status = ji.testObject(new Builder(context), PARSER).create();
      statuses.put(txIds.get(i), status);
    }
    return statuses;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("slot", buf, offset, len)) {
      builder.slot(ji.readLong());
    } else if (fieldEquals("confirmations", buf, offset, len)) {
      if (ji.whatIsNext() == ValueType.NUMBER) {
        builder.confirmations(ji.readInt());
      } else {
        ji.skip();
      }
    } else if (fieldEquals("err", buf, offset, len)) {
      builder.error(TransactionError.parseError(ji));
    } else if (fieldEquals("confirmationStatus", buf, offset, len)) {
      builder.confirmationStatus(ji.readString());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final Map<String, String> ALL_FIELDS_HANDLED = Map.of();

  private static final class Builder extends RootBuilder {

    private long slot;
    private int confirmations = -1;
    private TransactionError error;
    private Commitment confirmationStatus;
    private Boolean deprecatedOkayStatus;
    private String deprecatedError;
    private Map<String, String> unhandledFields;

    private Builder(final Context context) {
      super(context);
    }

    private TxStatus create() {
      return new TxStatus(
          context,
          slot,
          confirmations < 0 ? OptionalInt.empty() : OptionalInt.of(confirmations),
          error,
          confirmationStatus,
          deprecatedOkayStatus,
          deprecatedError,
          Objects.requireNonNullElse(unhandledFields, ALL_FIELDS_HANDLED));
    }

    private void slot(final long slot) {
      this.slot = slot;
    }

    private void confirmations(final int confirmations) {
      this.confirmations = confirmations;
    }

    private void error(final TransactionError error) {
      this.error = error;
    }

    private void confirmationStatus(final String confirmationStatus) {
      this.confirmationStatus = confirmationStatus == null || confirmationStatus.isBlank()
          ? null
          : Commitment.valueOf(confirmationStatus.toUpperCase(Locale.ENGLISH));
    }
  }
}