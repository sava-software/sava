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
                       TxInstructionError error,
                       Commitment confirmationStatus,
                       Boolean deprecatedOkayStatus,
                       String deprecatedError,
                       Map<String, String> unhandledFields) {

  public static Map<String, TxStatus> parse(final List<String> txIds, final JsonIterator ji, final Context context) {
    final var statuses = HashMap.<String, TxStatus>newHashMap(txIds.size());
    for (int i = 0; ji.readArray(); ++i) {
      final var status = ji.testObject(new Builder(context), PARSER).create();
      statuses.put(txIds.get(i), status);
    }
    return statuses;
  }

  private static final ContextFieldBufferPredicate<Builder> STATUS_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("Ok", buf, offset, len)) {
      builder.deprecatedOkayStatus = Boolean.TRUE;
      ji.skip();
    } else if (fieldEquals("Err", buf, offset, len)) {
      switch (ji.whatIsNext()) {
        case STRING -> builder.deprecatedError = ji.readString();
        case NUMBER -> builder.deprecatedError = ji.readNumberAsString();
        case OBJECT -> {
          if (builder.error == null) {
            builder.error(TxInstructionError.parseError(ji));
          } else {
            ji.skip();
          }
        }
        default -> {
          builder.deprecatedError = ji.currentBuffer();
          ji.skip();
        }
      }
    } else {
      final var field = "status." + new String(buf, offset, len);
      builder.recordUnhandledField(field, ji);
    }
    return true;
  };

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
      builder.error(TxInstructionError.parseError(ji));
    } else if (fieldEquals("confirmationStatus", buf, offset, len)) {
      builder.confirmationStatus(ji.readString());
    } else if (fieldEquals("status", buf, offset, len)) {
      ji.testObject(builder, STATUS_PARSER);
    } else {
      final var field = new String(buf, offset, len);
      builder.recordUnhandledField(field, ji);
    }
    return true;
  };

  private static final Map<String, String> ALL_FIELDS_HANDLED = Map.of();

  private static final class Builder extends RootBuilder {

    private long slot;
    private int confirmations = -1;
    private TxInstructionError error;
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

    private void recordUnhandledField(final String field, final JsonIterator ji) {
      final var value = switch (ji.whatIsNext()) {
        case STRING -> ji.readString();
        case NUMBER -> ji.readNumberAsString();
        case BOOLEAN -> Boolean.toString(ji.readBoolean());
        case INVALID -> {
          ji.skip();
          yield "?";
        }
        case NULL -> {
          ji.skip();
          yield "null";
        }
        case ARRAY -> {
          ji.skip();
          yield "[<?>]";
        }
        case OBJECT -> {
          ji.skip();
          yield "{<?>}";
        }
      };
      if (this.unhandledFields == null) {
        this.unhandledFields = new HashMap<>();
      }
      this.unhandledFields.put(field, value);
      System.err.format("%nUnhandled TxStatus field [%s]=[%s]%n", field, value);
    }

    private void slot(final long slot) {
      this.slot = slot;
    }

    private void confirmations(final int confirmations) {
      this.confirmations = confirmations;
    }

    private void error(final TxInstructionError error) {
      this.error = error;
    }

    private void confirmationStatus(final String confirmationStatus) {
      this.confirmationStatus = confirmationStatus == null || confirmationStatus.isBlank()
          ? null
          : Commitment.valueOf(confirmationStatus.toUpperCase(Locale.ENGLISH));
    }
  }
}