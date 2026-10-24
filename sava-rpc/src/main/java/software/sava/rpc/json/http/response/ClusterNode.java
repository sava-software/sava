package software.sava.rpc.json.http.response;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.PublicKeyEncoding;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;
import java.util.function.Supplier;

/// One gossip peer as `getClusterNodes` lists it. The three UDP TPU accessors are deprecated:
/// agave RPC servers since 4.0 report `null` for [#tpu()] and [#tpuForwards()], and
/// [#tpuVote()] names a port only TowerBFT nodes use.
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

  /// @deprecated The UDP TPU port. agave RPC servers since 4.0 report `null` here (`tpu: None` in
  /// `agave:rpc/src/rpc.rs`), so only older servers fill it; use [#tpuQuic()].
  @Deprecated(forRemoval = true)
  @Override
  public String tpu() {
    return tpu;
  }

  /// @deprecated The UDP TPU forwards port. agave RPC servers since 4.0 report `null` here
  /// (`tpu_forwards: None` in `agave:rpc/src/rpc.rs`), so only older servers fill it; use
  /// [#tpuForwardsQuic()].
  @Deprecated(forRemoval = true)
  @Override
  public String tpuForwards() {
    return tpuForwards;
  }

  /// @deprecated The UDP port on which a TowerBFT node takes in vote transactions. Alpenglow
  /// votes are not transactions and travel over their own QUIC socket, the Alpenglow address each
  /// node advertises in gossip and `getClusterNodes` does not report, so nothing on an Alpenglow
  /// cluster, which every public cluster has been since October 2026, uses it; agave's
  /// master branch drops the key from `getClusterNodes` and stops advertising the socket, while
  /// 4.3 and 4.4 servers still send it. Reads `null` when the server omits the key or the peer
  /// advertises no UDP vote socket. There is no replacement; a TowerBFT cluster, such as an agave
  /// test validator before 4.5 started without `--alpenglow`, still reports it.
  @Deprecated(forRemoval = true)
  @Override
  public String tpuVote() {
    return tpuVote;
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
