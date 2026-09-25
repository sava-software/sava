package software.sava.rpc.json.http.response;

import java.io.IOException;
import java.io.InputStream;

/// Decompresses `base64+zstd` account data, so sava imposes no zstd implementation on
/// applications that do not request that encoding.
@FunctionalInterface
public interface ZstdDecompressor {

  /// Wraps one compressed stream with a zstd-decoding stream. Sava reads and closes the returned
  /// stream while enforcing Solana's maximum account-data length.
  ///
  /// @param compressedData compressed frame stream; closing the returned stream should close it
  /// @return a stream of decompressed bytes, never `null`
  /// @throws IOException if the decoding stream cannot be created
  InputStream decompress(InputStream compressedData) throws IOException;
}
