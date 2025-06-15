package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.JsonIterator;

public record BlockHeight(long height) {

  public static BlockHeight parse(final JsonIterator ji) {
    return new BlockHeight(ji.readLong());
  }
}
