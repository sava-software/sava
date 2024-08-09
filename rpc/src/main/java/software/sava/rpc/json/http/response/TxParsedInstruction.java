package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxParsedInstruction(BigInteger amount,
                                  String authority,
                                  String destination,
                                  String source,
                                  String type,
                                  String program,
                                  String programId) {

  public static TxParsedInstruction parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  public static List<TxParsedInstruction> parsedInstructions(final JsonIterator ji) {
    final var instructions = new ArrayList<TxParsedInstruction>();
    while (ji.readArray()) {
      instructions.add(parse(ji));
    }
    return instructions;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSED_INFO_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("amount", buf, offset, len)) {
      builder.amount(ji.readBigInteger());
    } else if (fieldEquals("authority", buf, offset, len)) {
      builder.authority(ji.readString());
    } else if (fieldEquals("destination", buf, offset, len)) {
      builder.destination(ji.readString());
    } else if (fieldEquals("source", buf, offset, len)) {
      builder.source(ji.readString());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSED_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("info", buf, offset, len)) {
      ji.testObject(builder, PARSED_INFO_PARSER);
    } else if (fieldEquals("type", buf, offset, len)) {
      builder.type(ji.readString());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("parsed", buf, offset, len)) {
      ji.testObject(builder, PARSED_PARSER);
    } else if (fieldEquals("program", buf, offset, len)) {
      builder.program(ji.readString());
    } else if (fieldEquals("programId", buf, offset, len)) {
      builder.programId(ji.readString());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private BigInteger amount;
    private String authority;
    private String destination;
    private String source;
    private String type;
    private String program;
    private String programId;

    private Builder() {
    }

    private TxParsedInstruction create() {
      return new TxParsedInstruction(amount, authority, destination, source, type, program, programId);
    }

    private void amount(final BigInteger amount) {
      this.amount = amount;
    }

    private void authority(final String authority) {
      this.authority = authority;
    }

    private void destination(final String destination) {
      this.destination = destination;
    }

    private void source(final String source) {
      this.source = source;
    }

    private void type(final String type) {
      this.type = type;
    }

    private void program(final String program) {
      this.program = program;
    }

    private void programId(final String programId) {
      this.programId = programId;
    }
  }
}
