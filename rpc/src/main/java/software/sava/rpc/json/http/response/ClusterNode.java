package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record ClusterNode(String gossip,
                          String pubKey,
                          String rpc,
                          String tpu,
                          String version) {

  public static List<ClusterNode> parse(final JsonIterator ji) {
    final var nodes = new ArrayList<ClusterNode>();
    while (ji.readArray()) {
      final var node = ji.testObject(new Builder(), PARSER).create();
      nodes.add(node);
    }
    return nodes;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("gossip", buf, offset, len)) {
      builder.gossip = ji.readString();
    } else if (fieldEquals("pubkey", buf, offset, len)) {
      builder.pubKey = ji.readString();
    } else if (fieldEquals("rpc", buf, offset, len)) {
      builder.rpc = ji.readString();
    } else if (fieldEquals("tpu", buf, offset, len)) {
      builder.tpu = ji.readString();
    } else if (fieldEquals("version", buf, offset, len)) {
      builder.version = ji.readString();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private String gossip;
    private String pubKey;
    private String rpc;
    private String tpu;
    private String version;

    private Builder() {

    }

    private ClusterNode create() {
      return new ClusterNode(gossip, pubKey, rpc, tpu, version);
    }
  }
}
