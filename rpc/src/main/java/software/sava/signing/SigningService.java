package software.sava.signing;

import software.sava.core.accounts.PublicKey;

import java.util.concurrent.CompletableFuture;

public interface SigningService extends AutoCloseable {

  CompletableFuture<PublicKey> publicKey();

  CompletableFuture<byte[]> sign(final byte[] msg, final int offset, final int length);

  CompletableFuture<byte[]> sign(final byte[] msg);
}
