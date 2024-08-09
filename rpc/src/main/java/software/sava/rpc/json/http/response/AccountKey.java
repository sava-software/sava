package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record AccountKey(String pubKey, boolean signer, boolean writable) {

  public static AccountKey parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  public static List<AccountKey> parseAccounts(final JsonIterator ji) {
    final var accountKeys = new ArrayList<AccountKey>();
    while (ji.readArray()) {
      accountKeys.add(parse(ji));
    }
    return accountKeys;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("pubkey", buf, offset, len)) {
      builder.pubKey(ji.readString());
    } else if (fieldEquals("signer", buf, offset, len)) {
      builder.signer(ji.readBoolean());
    } else if (fieldEquals("writable", buf, offset, len)) {
      builder.writable(ji.readBoolean());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private String pubKey;
    private boolean signer;
    private boolean writable;

    private Builder() {
    }

    private AccountKey create() {
      return new AccountKey(pubKey, signer, writable);
    }

    private void pubKey(final String pubKey) {
      this.pubKey = pubKey;
    }

    private void signer(final boolean signer) {
      this.signer = signer;
    }

    private void writable(final boolean writable) {
      this.writable = writable;
    }
  }
}
