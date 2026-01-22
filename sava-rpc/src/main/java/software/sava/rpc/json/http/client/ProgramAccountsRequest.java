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

import static software.sava.rpc.json.http.response.AccountInfo.BYTES_IDENTITY;

public interface ProgramAccountsRequest<T> {

  static Builder build() {
    return new Builder();
  }

  String toJson(final long requestId, final Commitment commitment);

  Duration requestTimeout();

  PublicKey programId();

  Commitment commitment();

  BigInteger minContextSlot();

  Collection<Filter> filters();

  int dataSliceLength();

  int dataSliceOffset();

  RpcEncoding encoding();

  BiFunction<PublicKey, byte[], T> factory();

  final class Builder {

    private Duration requestTimeout;
    private PublicKey programId;
    private Commitment commitment;
    private BigInteger minContextSlot;
    private Collection<Filter> filters;
    private int dataSliceLength;
    private int dataSliceOffset;
    private RpcEncoding encoding;

    Builder() {
    }

    public ProgramAccountsRequest<byte[]> createRequest() {
      return createRequest(BYTES_IDENTITY);
    }

    public <T> ProgramAccountsRequest<T> createRequest(final BiFunction<PublicKey, byte[], T> factory) {
      return new ProgramAccountsRequestRecord<>(
          requestTimeout,
          programId,
          commitment,
          minContextSlot,
          filters,
          dataSliceLength,
          dataSliceOffset,
          Objects.requireNonNullElse(encoding, RpcEncoding.base64),
          factory
      );
    }

    public Builder requestTimeout(final Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    public Builder programId(final PublicKey programId) {
      this.programId = programId;
      return this;
    }

    public Builder commitment(final Commitment commitment) {
      this.commitment = commitment;
      return this;
    }

    public Builder minContextSlot(final BigInteger minContextSlot) {
      this.minContextSlot = minContextSlot;
      return this;
    }

    public Builder filters(final Collection<Filter> filters) {
      this.filters = filters;
      return this;
    }

    public Builder dataSliceLength(final int dataSliceOffset, final int dataSliceLength) {
      this.dataSliceOffset = dataSliceOffset;
      this.dataSliceLength = dataSliceLength;
      return this;
    }

    public Builder encoding(final RpcEncoding encoding) {
      this.encoding = encoding;
      return this;
    }
  }
}
