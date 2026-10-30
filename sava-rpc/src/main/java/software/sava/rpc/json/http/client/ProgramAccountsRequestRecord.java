package software.sava.rpc.json.http.client;

import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.DataSlice;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.RpcEncoding;
import software.sava.rpc.json.http.response.ZstdDecompressor;

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
                                       DataSlice dataSlice,
                                       RpcEncoding encoding,
                                       BiFunction<PublicKey, byte[], T> factory,
                                       ZstdDecompressor zstdDecompressor)
    implements ProgramAccountsRequest<T> {

  @Deprecated(forRemoval = true)
  @SuppressWarnings("removal")
  @Override
  public int dataSliceLength() {
    return dataSlice == null ? 0 : dataSlice.length();
  }

  @Deprecated(forRemoval = true)
  @SuppressWarnings("removal")
  @Override
  public int dataSliceOffset() {
    return dataSlice == null ? 0 : dataSlice.offset();
  }

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

    BaseSolanaJsonRpcClient.appendMinContextSlot(builder, minContextSlot);
    BaseSolanaJsonRpcClient.appendDataSlice(builder, dataSlice);
    BaseSolanaJsonRpcClient.appendFilters(builder, filters);
    builder.append("}]}");

    return builder.toString();
  }
}
