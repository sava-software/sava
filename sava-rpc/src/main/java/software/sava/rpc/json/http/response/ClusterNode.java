package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;
import java.util.function.Supplier;

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
                          String clientId,
                          long featureSet,
                          int shredVersion) {

  public static List<ClusterNode> parse(final JsonIterator ji) {
    return ji.readList(j -> j.parseObject(Parser.FIELDS, new Parser()));
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<ClusterNode> {

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
    private String clientId;
    private long featureSet;
    private int shredVersion;

    private Parser() {
    }

    @Override
    public ClusterNode get() {
      return new ClusterNode(gossip, pubKey, rpc, pubsub, serveRepair, tpu, tpuForwards, tpuForwardsQuic, tpuQuic, tpuVote, tvu, version, clientId, featureSet, shredVersion);
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "gossip",
        "pubkey",
        "rpc",
        "pubsub",
        "serveRepair",
        "tpu",
        "tpuForwards",
        "tpuForwardsQuic",
        "tpuQuic",
        "tpuVote",
        "tvu",
        "version",
        "clientId",
        "featureSet",
        "shredVersion"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> gossip = ji.readString();
        case 1 -> pubKey = PublicKeyEncoding.parseBase58Encoded(ji);
        case 2 -> rpc = ji.readString();
        case 3 -> pubsub = ji.readString();
        case 4 -> serveRepair = ji.readString();
        case 5 -> tpu = ji.readString();
        case 6 -> tpuForwards = ji.readString();
        case 7 -> tpuForwardsQuic = ji.readString();
        case 8 -> tpuQuic = ji.readString();
        case 9 -> tpuVote = ji.readString();
        case 10 -> tvu = ji.readString();
        case 11 -> version = ji.readString();
        case 12 -> clientId = ji.readString();
        case 13 -> featureSet = ji.readLongOr(featureSet);
        case 14 -> shredVersion = ji.readIntOr(shredVersion);
        default -> ji.skip();
      }
      return true;
    }
  }
}
