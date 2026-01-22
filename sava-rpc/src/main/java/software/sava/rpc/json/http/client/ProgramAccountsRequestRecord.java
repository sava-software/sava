package software.sava.rpc.json.http.client;

import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.RpcEncoding;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.BiFunction;

import static software.sava.core.rpc.Filter.MAX_MEM_COMP_LENGTH;

record ProgramAccountsRequestRecord<T>(Duration requestTimeout,
                                       PublicKey programId,
                                       Commitment commitment,
                                       BigInteger minContextSlot,
                                       Collection<Filter> filters,
                                       int dataSliceLength,
                                       int dataSliceOffset,
                                       RpcEncoding encoding,
                                       BiFunction<PublicKey, byte[], T> factory)
    implements ProgramAccountsRequest<T> {

  @Override
  public String toJson(final long requestId, final Commitment defaultCommitment) {
    final int numFilters = filters == null ? 0 : filters.size();
    final var builder = new StringBuilder(256 + (numFilters * MAX_MEM_COMP_LENGTH));

    builder.append("""
        {"jsonrpc":"2.0","id":""");
    builder.append(requestId);

    builder.append("""
        ,"method":"getProgramAccounts","params":[\"""");
    builder.append(programId.toBase58());

    builder.append("""
        ",{"withContext":true,"encoding":\"""");
    builder.append(encoding.value());

    builder.append("""
        ","commitment":\"""");
    builder.append(Objects.requireNonNullElse(this.commitment, defaultCommitment).getValue());
    builder.append('"');

    if (minContextSlot != null) {
      builder.append("""
          ,"minContextSlot":""");
      builder.append(minContextSlot);
    }

    if (dataSliceLength != 0) {
      builder.append("""
          ,"dataSlice":{"length":""");
      builder.append(dataSliceLength);
      builder.append("""
          ,"offset":""");
      builder.append(dataSliceOffset);
      builder.append('}');
    }

    if (numFilters == 0) {
      builder.append("}]}");
    } else {
      builder.append("""
          ,"filters":[""");
      final var iterator = filters.iterator();
      for (Filter filter; ; ) {
        filter = iterator.next();
        builder.append(filter.toJson());
        if (iterator.hasNext()) {
          builder.append(',');
        } else {
          break;
        }
      }
      builder.append("]}]}");
    }

    return builder.toString();
  }
}
