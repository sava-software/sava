package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static software.sava.rpc.json.http.response.AccountInfo.BYTES_IDENTITY;
import static software.sava.rpc.json.http.response.AccountInfo.parseAccountsFromKeys;
import static software.sava.rpc.json.http.response.JsonUtil.parseEncodedData;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxSimulation(Context context,
                           TxInstructionError error,
                           List<String> logs,
                           List<AccountInfo<byte[]>> accounts,
                           OptionalInt unitsConsumed,
                           PublicKey programId,
                           byte[] data) {

  public static TxSimulation parse(final List<PublicKey> accounts, final JsonIterator ji, final Context context) {
    return ji.testObject(new Builder(context, accounts), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> RETURN_DATA_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("programId", buf, offset, len)) {
      builder.programId(PublicKeyEncoding.parseBase58Encoded(ji));
    } else if (fieldEquals("data", buf, offset, len)) {
      builder.data(parseEncodedData(ji));
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("err", buf, offset, len)) {
      builder.error(TxInstructionError.parseError(ji));
    } else if (fieldEquals("logs", buf, offset, len)) {
      final var logs = new ArrayList<String>();
      while (ji.readArray()) {
        logs.add(ji.readString());
      }
      builder.logs(logs);
    } else if (fieldEquals("accounts", buf, offset, len)) {
      if (builder.accountPubKeys == null || builder.accountPubKeys.isEmpty()) {
        ji.skip();
      } else {
        builder.accounts = parseAccountsFromKeys(builder.accountPubKeys, ji, builder.context, BYTES_IDENTITY);
      }
    } else if (fieldEquals("unitsConsumed", buf, offset, len)) {
      builder.unitsConsumed(ji.readInt());
    } else if (fieldEquals("returnData", buf, offset, len)) {
      ji.testObject(builder, RETURN_DATA_PARSER);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private final List<PublicKey> accountPubKeys;
    private TxInstructionError error;
    private List<String> logs;
    private List<AccountInfo<byte[]>> accounts;
    private int unitsConsumed = -1;
    private PublicKey programId;
    private byte[] data;

    private Builder(final Context context, final List<PublicKey> accountPubKeys) {
      super(context);
      this.accountPubKeys = accountPubKeys;
    }

    private TxSimulation create() {
      return new TxSimulation(context, error, logs, accounts, unitsConsumed < 0 ? OptionalInt.empty() : OptionalInt.of(unitsConsumed), programId, data);
    }

    private void error(final TxInstructionError error) {
      this.error = error;
    }

    private void logs(final List<String> logs) {
      this.logs = logs;
    }

    private void programId(final PublicKey programId) {
      this.programId = programId;
    }

    private void unitsConsumed(final int unitsConsumed) {
      this.unitsConsumed = unitsConsumed;
    }

    private void data(final byte[] data) {
      this.data = data;
    }
  }
}
