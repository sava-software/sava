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

  /// The window of account data to return, or `null` for the whole account. The default derives
  /// it from the deprecated [#dataSliceLength()] and [#dataSliceOffset()] so that implementations
  /// written against earlier releases keep compiling; new implementations override this method.
  @SuppressWarnings("removal")
  default DataSlice dataSlice() {
    final int length = dataSliceLength();
    return length == 0 ? null : new DataSlice(dataSliceOffset(), length);
  }

  /// @deprecated read [#dataSlice()]: this reads `0` both for no slice and for an empty one such as
  /// [DataSlice#EMPTY], so it cannot tell the whole account from metadata only.
  @Deprecated(forRemoval = true)
  int dataSliceLength();

  /// @deprecated read [#dataSlice()]: this reads `0` for no slice, for an empty slice and for any
  /// slice that starts at byte zero. A legacy zero length set with a non-zero offset reads `0` too:
  /// earlier releases echoed that offset, which never reached the node, and it is no longer stored.
  @Deprecated(forRemoval = true)
  int dataSliceOffset();

  RpcEncoding encoding();

  BiFunction<PublicKey, byte[], T> factory();

  /// The decoder for [RpcEncoding#base64_zstd] account data, or `null` when none was supplied.
  /// An implementation whose [#encoding()] is [RpcEncoding#base64_zstd] must override it.
  default ZstdDecompressor zstdDecompressor() {
    return null;
  }

  final class Builder {

    private Duration requestTimeout;
    private PublicKey programId;
    private Commitment commitment;
    private BigInteger minContextSlot;
    private Collection<Filter> filters;
    private DataSlice dataSlice;
    private RpcEncoding encoding;
    private ZstdDecompressor zstdDecompressor;

    Builder() {
    }

    /// @throws IllegalStateException if the encoding is [RpcEncoding#base64_zstd] and no
    ///                               [#zstdDecompressor(ZstdDecompressor)] was set.
    public ProgramAccountsRequest<byte[]> createRequest() {
      return createRequest(BYTES_IDENTITY);
    }

    /// @throws IllegalStateException if the encoding is [RpcEncoding#base64_zstd] and no
    ///                               [#zstdDecompressor(ZstdDecompressor)] was set.
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
          dataSlice,
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

    /// The window of account data to return; `null`, the default, returns the whole account.
    public Builder dataSlice(final DataSlice dataSlice) {
      this.dataSlice = dataSlice;
      return this;
    }

    /// Takes the **offset first**, despite the method name.
    ///
    /// @param dataSliceOffset byte offset into the account data.
    /// @param dataSliceLength bytes to return, `0` for the whole account.
    /// @deprecated use [#dataSlice(DataSlice)]: `new DataSlice(offset, length)`, or `null` for the
    /// whole account. A `0` length still means the whole account and discards the offset, so
    /// [ProgramAccountsRequest#dataSliceOffset()] reads `0` where earlier releases echoed it;
    /// otherwise a negative value now throws [IllegalArgumentException] instead of reaching the node.
    @Deprecated(forRemoval = true)
    public Builder dataSliceLength(final int dataSliceOffset, final int dataSliceLength) {
      return dataSlice(dataSliceLength == 0 ? null : new DataSlice(dataSliceOffset, dataSliceLength));
    }

    /// Account-data encoding, [RpcEncoding#base64] when unset. [RpcEncoding#base64_zstd] also
    /// requires [#zstdDecompressor(ZstdDecompressor)].
    public Builder encoding(final RpcEncoding encoding) {
      this.encoding = encoding;
      return this;
    }

    /// Sets the decoder that [RpcEncoding#base64_zstd] requires; sava bundles no zstd library.
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
