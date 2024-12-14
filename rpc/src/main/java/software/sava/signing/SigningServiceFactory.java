package software.sava.signing;

import systems.comodal.jsoniter.JsonIterator;

import java.util.concurrent.ExecutorService;

public interface SigningServiceFactory {

  SigningService createService(final ExecutorService executorService, final JsonIterator ji);
}
