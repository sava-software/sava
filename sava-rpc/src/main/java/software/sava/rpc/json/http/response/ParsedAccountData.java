package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

@Deprecated(forRemoval = true)
public record ParsedAccountData(PublicKey program,
                                int space,
                                boolean isNative,
                                PublicKey mint,
                                PublicKey owner,
                                PublicKey freezeAuthority,
                                PublicKey mintAuthority,
                                Boolean isInitialized,
                                String state,
                                String type,
                                TokenAmount tokenAmount,
                                List<PublicKey> addresses) {

  public static ParsedAccountData parse(final JsonIterator ji, final Context context) {
    return ji.testObject(new Builder(context), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSED_INFO_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("isNative", buf, offset, len)) {
      builder.isNative = ji.readBoolean();
    } else if (fieldEquals("mint", buf, offset, len)) {
      builder.mint = PublicKeyEncoding.parseBase58Encoded(ji);
    } else if (fieldEquals("owner", buf, offset, len)) {
      builder.owner = PublicKeyEncoding.parseBase58Encoded(ji);
    } else if (fieldEquals("freezeAuthority", buf, offset, len)) {
      builder.freezeAuthority = PublicKeyEncoding.parseBase58Encoded(ji);
    } else if (fieldEquals("mintAuthority", buf, offset, len)) {
      builder.mintAuthority = PublicKeyEncoding.parseBase58Encoded(ji);
    } else if (fieldEquals("isInitialized", buf, offset, len)) {
      builder.isInitialized = ji.readBoolean();
    } else if (fieldEquals("state", buf, offset, len)) {
      builder.state = ji.readString();
    } else if (fieldEquals("tokenAmount", buf, offset, len)) {
      builder.tokenAmount = TokenAmount.parse(ji, builder.context);
    } else if (fieldEquals("decimals", buf, offset, len)) {
      builder.decimals = ji.readInt();
    } else if (fieldEquals("supply", buf, offset, len)) {
      builder.supply = ji.readBigInteger();
    } else if (fieldEquals("addresses", buf, offset, len)) {
      final var addresses = new ArrayList<PublicKey>();
      while (ji.readArray()) {
        addresses.add(PublicKeyEncoding.parseBase58Encoded(ji));
      }
      builder.addresses = addresses;
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSED_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("type", buf, offset, len)) {
      builder.type = ji.readString();
    } else if (fieldEquals("info", buf, offset, len)) {
      ji.testObject(builder, PARSED_INFO_PARSER);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("program", buf, offset, len)) {
      builder.program = PublicKeyEncoding.parseBase58Encoded(ji);
    } else if (fieldEquals("space", buf, offset, len)) {
      builder.space = ji.readInt();
    } else if (fieldEquals("parsed", buf, offset, len)) {
      ji.testObject(builder, PARSED_PARSER);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private PublicKey program;
    private int space;
    private boolean isNative;
    private PublicKey mint;
    private PublicKey owner;
    private String state;
    private String type;
    private TokenAmount tokenAmount;
    private PublicKey freezeAuthority;
    private PublicKey mintAuthority;
    private Boolean isInitialized;
    private int decimals = Integer.MIN_VALUE;
    private BigInteger supply;
    private List<PublicKey> addresses;

    private Builder(final Context context) {
      super(context);
    }

    private ParsedAccountData create() {
      if (tokenAmount == null && decimals >= 0 && supply != null) {
        tokenAmount = new TokenAmount(context, supply, decimals);
      }
      return new ParsedAccountData(program, space, isNative, mint, owner, freezeAuthority, mintAuthority, isInitialized, state, type, tokenAmount, addresses);
    }

  }
}
