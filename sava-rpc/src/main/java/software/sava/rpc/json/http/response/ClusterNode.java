package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record ClusterNode(String gossip,
                          PublicKey publicKey,
                          String rpc,
                          String pubsub,
                          String serveRepair,
                          String tpu,
                          String tpuForwards,
                          String tpuForwardsQuic,
                          String tpuQuic,
                          String tpuVote,
                          String tvu,
                          String version,
                          long featureSet,
                          int shredVersion) {

  @Deprecated
  public String pubKey() {
    return publicKey.toString();
  }

  public static List<ClusterNode> parse(final JsonIterator ji) {
    final var nodes = new ArrayList<ClusterNode>();
    while (ji.readArray()) {
      final var parser = new Parser();
      ji.testObject(parser);
      nodes.add(parser.create());
    }
    return nodes;
  }

  private static final class Parser implements FieldBufferPredicate {

    private String gossip;
    private PublicKey pubKey;
    private String rpc;
    private String pubsub;
    private String serveRepair;
    private String tpu;
    private String tpuForwards;
    private String tpuForwardsQuic;
    private String tpuQuic;
    private String tpuVote;
    private String tvu;
    private String version;
    private long featureSet;
    private int shredVersion;

    private Parser() {
    }

    private ClusterNode create() {
      return new ClusterNode(gossip, pubKey, rpc, pubsub, serveRepair, tpu, tpuForwards, tpuForwardsQuic, tpuQuic, tpuVote, tvu, version, featureSet, shredVersion);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("gossip", buf, offset, len)) {
        gossip = ji.readString();
      } else if (fieldEquals("pubkey", buf, offset, len)) {
        pubKey = PublicKeyEncoding.parseBase58Encoded(ji);
      } else if (fieldEquals("rpc", buf, offset, len)) {
        rpc = ji.readString();
      } else if (fieldEquals("pubsub", buf, offset, len)) {
        pubsub = ji.readString();
      } else if (fieldEquals("serveRepair", buf, offset, len)) {
        serveRepair = ji.readString();
      } else if (fieldEquals("tpu", buf, offset, len)) {
        tpu = ji.readString();
      } else if (fieldEquals("tpuForwards", buf, offset, len)) {
        tpuForwards = ji.readString();
      } else if (fieldEquals("tpuForwardsQuic", buf, offset, len)) {
        tpuForwardsQuic = ji.readString();
      } else if (fieldEquals("tpuQuic", buf, offset, len)) {
        tpuQuic = ji.readString();
      } else if (fieldEquals("tpuVote", buf, offset, len)) {
        tpuVote = ji.readString();
      } else if (fieldEquals("tvu", buf, offset, len)) {
        tvu = ji.readString();
      } else if (fieldEquals("version", buf, offset, len)) {
        version = ji.readString();
      } else if (fieldEquals("featureSet", buf, offset, len)) {
        featureSet = ji.readLong();
      } else if (fieldEquals("shredVersion", buf, offset, len)) {
        shredVersion = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
