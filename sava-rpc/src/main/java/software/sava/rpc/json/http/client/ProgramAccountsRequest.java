package software.sava.rpc.json.http.client;

import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.RpcEncoding;
import software.sava.rpc.json.http.response.ZstdDecompressor;

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

  /**
   * Returns the decoder used for {@link RpcEncoding#base64_zstd}, or {@code null} when none was
   * supplied. This default preserves compatibility with third-party request implementations.
   */
  default ZstdDecompressor zstdDecompressor() {
    return null;
  }

  final class Builder {

    private Duration requestTimeout;
    private PublicKey programId;
    private Commitment commitment;
    private BigInteger minContextSlot;
    private Collection<Filter> filters;
    private int dataSliceLength;
    private int dataSliceOffset;
    private RpcEncoding encoding;
    private ZstdDecompressor zstdDecompressor;

    Builder() {
    }

    public ProgramAccountsRequest<byte[]> createRequest() {
      return createRequest(BYTES_IDENTITY);
    }

    public <T> ProgramAccountsRequest<T> createRequest(final BiFunction<PublicKey, byte[], T> factory) {
      final var encoding = Objects.requireNonNullElse(this.encoding, RpcEncoding.base64);
      if (encoding == RpcEncoding.base64_zstd && zstdDecompressor == null) {
        throw missingZstdDecompressor();
      }
      return new ProgramAccountsRequestRecord<>(
          requestTimeout,
          programId,
          commitment,
          minContextSlot,
          filters,
          dataSliceLength,
          dataSliceOffset,
          encoding,
          factory,
          zstdDecompressor
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

    /// @param dataSliceOffset byte offset into the account data to start at.
    /// @param dataSliceLength bytes to return, 0 for the whole account.
    public Builder dataSliceLength(final int dataSliceOffset, final int dataSliceLength) {
      this.dataSliceOffset = dataSliceOffset;
      this.dataSliceLength = dataSliceLength;
      return this;
    }

    /// Selects the account-data encoding sent to `getProgramAccounts`. In particular,
    /// [RpcEncoding#base64_zstd] opts into Solana's `base64+zstd` wire representation and also
    /// requires [#zstdDecompressor(ZstdDecompressor)] before the request can be executed.
    public Builder encoding(final RpcEncoding encoding) {
      this.encoding = encoding;
      return this;
    }

    /**
     * Supplies the optional zstd implementation used to decode {@code base64+zstd} account data.
     * Sava deliberately has no runtime dependency on a particular zstd library.
     */
    public Builder zstdDecompressor(final ZstdDecompressor zstdDecompressor) {
      this.zstdDecompressor = zstdDecompressor;
      return this;
    }

    static IllegalStateException missingZstdDecompressor() {
      return new IllegalStateException(
          "base64+zstd requires ProgramAccountsRequest.Builder.zstdDecompressor(...)"
      );
    }
  }
}
