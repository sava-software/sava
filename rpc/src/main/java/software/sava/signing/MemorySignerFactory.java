package software.sava.signing;

import software.sava.rpc.json.PrivateKeyEncoding;
import systems.comodal.jsoniter.JsonIterator;

import java.util.concurrent.ExecutorService;

public final class MemorySignerFactory implements SigningServiceFactory {

  @Override
  public SigningService createService(final ExecutorService executorService, final JsonIterator ji) {
    final var signer = PrivateKeyEncoding.fromJsonPrivateKey(ji);
    return new MemorySigner(signer);
  }
}
