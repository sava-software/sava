package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.OptionalLong;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

final class ParseRpcResponseTests {

  private static final ClassLoader CLASS_LOADER = ParseRpcResponseTests.class.getClassLoader();

  private static JsonIterator readJsonFile(final String fileName) {
    final var resource = CLASS_LOADER.getResource("rpc_response_data/" + fileName);
    if (resource == null) {
      fail("Test resource not found: " + fileName);
    }
    try (final var in = resource.openStream()) {
      final byte[] bytes;
      if (fileName.endsWith(".zip")) {
        try (final var zin = new ZipInputStream(in)) {
          final var entry = zin.getNextEntry();
          if (entry == null) {
            fail("Zip resource has no entries: " + fileName);
          }
          bytes = zin.readAllBytes();
        }
      } else {
        bytes = in.readAllBytes();
      }
      return JsonIterator.parse(bytes).skipUntil("result");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void getBlock() {
    final var ji = readJsonFile("getBlock.json.zip");
    final var block = Block.parse(ji);

    assertEquals(339954329L, block.blockHeight());
    assertEquals(1755872986L, block.blockTime());
    assertEquals("8qSPsbs79i4UvK7dqxHYSPBX1u1BU4pTEewnsrQnogQw", block.blockHash());
    assertEquals("7YUEp2UrHXFMzD5N3rjon624vcbQQQiw2LDgUBRmnZfb", block.previousBlockHash());
    assertEquals(361774428L, block.parentSlot());

    final var rewards = block.rewards();
    assertEquals(1, rewards.size());
    final var reward = rewards.getFirst();
    assertEquals(0, reward.commission());
    assertEquals(34813913L, reward.lamports());
    assertEquals(137825929849L, reward.postBalance());
    assertEquals(PublicKey.fromBase58Encoded("ChorusmmK7i1AxXeiTtQgQZhQNiXYU84ULeaYF1EH15n"), reward.publicKey());
    assertEquals(RewardType.FEE, reward.rewardType());

    final var txs = block.transactions();
    assertEquals(1790, txs.size());

    final var txFirst = txs.getFirst();
    final var txLast = txs.getLast();

    final var metaFirst = txFirst.meta();
    final var metaLast = txLast.meta();

    assertNull(metaFirst.error());
    assertEquals(1597, metaFirst.computeUnitsConsumed());
    assertEquals(6109L, metaFirst.fee());

    final var la = metaFirst.loadedAddresses();
    assertTrue(la.readonly().isEmpty());
    assertTrue(la.writable().isEmpty());

    assertTrue(metaFirst.preTokenBalances().isEmpty());
    assertTrue(metaFirst.postTokenBalances().isEmpty());
    assertTrue(metaFirst.rewards().isEmpty());

    final var pre = metaFirst.preBalances();
    assertEquals(5, pre.size());
    assertEquals(59065310538L, pre.getFirst());
    assertEquals(72184175L, pre.getLast());

    final var post = metaFirst.postBalances();
    assertEquals(5, post.size());
    assertEquals(59065304429L, post.getFirst());
    assertEquals(72184175L, post.getLast());

    final var logs = metaFirst.logMessages();
    assertEquals(7, logs.size());
    assertEquals("Program ComputeBudget111111111111111111111111111111 invoke [1]", logs.getFirst());
    assertEquals("Program ComputeBudget111111111111111111111111111111 success", logs.getLast());

    final var ii = metaFirst.innerInstructions();
    assertEquals(0, ii.size());

    final var expectedB64 = "AUfk7jKtAVV62pdSkWQtktQ6fVDeK3kNWU/NKVwLQp42kcnpu2N69XUcXLGlybPGLi39Y08CYjsvLYhS2a8ZzwCAAQADBddjy8++ND78u8tFUHrIlfR8617fIkPS5yvmmHS322bP0M0zWZ0dL73vhQoLp7sVDzOorogUR/HE3y9vlq4imWwDBkZv5SEXMv/srbpyw5vnvIzlu8X3EmssQ5s6QAAAAAbT7cTlc62v8+VziYtUWx7o0o0FjtlOiOC8szJXG+oKca0m5k6X6QmN+73+9ouxpcfQpxYmK3Y/XTCI/4aBC+AAFmtNm8GF9GS8UD4APvWBOy+bKwhyEXlfukUcW91KGwMCAAUCQAYAAAMDBAABmQMNM2bVBAAAAABbPZAVAAAAAAA0aYf/MgMAAAAAAAAAAAAYAYil/fEDAAAAAAAAAAAACwAAADs+DwAAAAAARDqgBgAAAAD6PQ8AAAAAAGWwISEAAAAAQD0PAAAAAADohkZCAAAAAIc8DwAAAAAA7jC4pQAAAAArPA8AAAAAAM1BeEsBAAAAuDoPAAAAAADohi+XAgAAANM3DwAAAAAAeZ+tlwIAAADvNA8AAAAAAAigVzAFAAAA/TAPAAAAAACkdjn8DAAAAKksDwAAAAAASyLu/wwAAACyDg8AAAAAAA8CxZpOAAAACwAAADw+DwAAAAAAFlD0BQAAAAD8PQ8AAAAAALARxR0AAAAAQj0PAAAAAAAyT4c7AAAAAIk8DwAAAAAA3DPLlAAAAAAsPA8AAAAAANhVjykBAAAAuToPAAAAAAAGIOZSAgAAANU3DwAAAAAAzRh1UgIAAADxNA8AAAAAAB9OCKQEAAAA/zAPAAAAAADHPBKXCwAAAKosDwAAAAAAPG/EkwsAAACn3w4AAAAAAAEjAlkLAAAAAgAJA4WTCgAAAAAAAA==";
    final byte[] expectedBytes = java.util.Base64.getDecoder().decode(expectedB64);
    assertArrayEquals(expectedBytes, txFirst.data());

    assertNull(metaLast.error());
    assertEquals(740, metaLast.computeUnitsConsumed());
    assertEquals(5802L, metaLast.fee());

    final var laLast = metaLast.loadedAddresses();
    assertTrue(laLast.readonly().isEmpty());
    assertTrue(laLast.writable().isEmpty());

    assertTrue(metaLast.preTokenBalances().isEmpty());
    assertTrue(metaLast.postTokenBalances().isEmpty());
    assertTrue(metaLast.rewards().isEmpty());

    final var preLast = metaLast.preBalances();
    assertEquals(6, preLast.size());
    assertEquals(12220068453L, preLast.getFirst());
    assertEquals(1141440L, preLast.getLast());

    final var postLast = metaLast.postBalances();
    assertEquals(6, postLast.size());
    assertEquals(12220057651L, postLast.getFirst());
    assertEquals(1141440L, postLast.getLast());

    final var logsLast = metaLast.logMessages();
    assertEquals(11, logsLast.size());
    assertEquals("Program ComputeBudget111111111111111111111111111111 invoke [1]", logsLast.getFirst());
    assertEquals("Program Minimox7jqQmMpF6Z34DTNwE9iJyNkruzvvYQRaHpAP success", logsLast.getLast());

    final var iiLast = metaLast.innerInstructions();
    assertEquals(0, iiLast.size());

    final var expectedLastB64 = "Adp4cqwBC6IXAsusstvw9QEc3nwSRsdzhbtFGYuFSnV/AldeawcF4FhQxrNCNLYFjddX7q9fSN8Au4O9lPnNLAiAAQADBrr6uIv0d1LN22py+2h6XxCI5HgrOcB5+cy8O5isUt4XICYQHsIDKJZKMqurE2xUBbkfOuOO5PZMtr3oebhoONL9bdqFSz7BvQd9lk+TgpDaFwaJrgDud0vPGVo7xJ5v7gMGRm/lIRcy/+ytunLDm+e8jOW7xfcSayxDmzpAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFTt3Ic1JMg2/jQW11PJwg0wFvK/Y5f0UJ4xfBhxnkjPL13h2LZyLlia+/tjB8ek1aoTr9a7t3CeN5UWMQEZJFBQMABQIiAwAAAwAJA0BCDwAAAAAABAIAAQwCAAAAiBMAAAAAAAADAAUEQJwAAAUCAAJwbpIT1DEAAACW4BwA236oaG6SE9QxAAAAPuEcANt+qGhu4oTjFQAAAKaGAQDbfqhoXvuhogEAAAAQJwAA236oaF77oaIBAAAAECcAANt+qGhe+6GiAQAAAKCGAQDbfqhobuKE4xUAAADdxSAB236oaAA=";
    final byte[] expectedLastBytes = java.util.Base64.getDecoder().decode(expectedLastB64);
    assertArrayEquals(expectedLastBytes, txLast.data());

    assertEquals(0, block.signatures().size());
  }

  @Test
  void getBlockCommitment() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":{"commitment":null,"totalStake":405146599541901117},"id":1755829799753}
        """
    ).skipUntil("result");
    final var bc = BlockCommitment.parse(ji);
    assertEquals(0, bc.commitment().length);
    assertEquals(405146599541901117L, bc.totalStake());
  }

  @Test
  void getBlockCommitmentWithArray() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":{"commitment":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,404893464137748549],"totalStake":405146599541901117},"id":1755830529944}
        """
    ).skipUntil("result");
    final var bc = BlockCommitment.parse(ji);
    final long[] commitment = bc.commitment();
    assertEquals(32, commitment.length);
    for (int i = 0; i < 31; i++) {
      assertEquals(0L, commitment[i]);
    }
    assertEquals(404893464137748549L, commitment[31]);
    assertEquals(405146599541901117L, bc.totalStake());
  }

  @Test
  void getBlockProduction() {
    final var ji = readJsonFile("getBlockProduction.json");

    ji.skipUntil("context");
    final var context = Context.parse(ji);
    assertEquals(361663684L, context.slot());
    assertEquals("2.3.7", context.apiVersion());

    ji.skipUntil("value");
    final var production = BlockProduction.parse(ji, context);

    assertEquals(361584000L, production.firstSlot());
    assertEquals(361663684L, production.lastSlot());

    final var map = production.leaderInfoMap();
    assertEquals(887, map.size());

    var pk = PublicKey.fromBase58Encoded("11AMA4mnNbsrPQeuoNN7uiZVJZtqEzQHrTfa5vnbcjk");
    var info = map.get(pk);
    assertEquals(8, info.numSlots());
    assertEquals(8, info.blocksProduced());

    pk = PublicKey.fromBase58Encoded("2374M8ZtmrpdY3ywb7fLqokSd2mRhvdJu2PUwoJmbTUh");
    info = map.get(pk);
    assertEquals(16, info.numSlots());
    assertEquals(12, info.blocksProduced());

    pk = PublicKey.fromBase58Encoded("2GUnfxZavKoPfS9s3VSEjaWDzB3vNf5RojUhprCS1rSx");
    info = map.get(pk);
    assertEquals(468, info.numSlots());
    assertEquals(464, info.blocksProduced());

    pk = PublicKey.fromBase58Encoded("22rU5yUmdVThrkoPieVNphqEyAtMQKmZxjwcD8v4bJDU");
    info = map.get(pk);
    assertEquals(408, info.numSlots());
    assertEquals(408, info.blocksProduced());

    pk = PublicKey.fromBase58Encoded("zeroT6PTAEjipvZuACTh1mbGCqTHgA6i1ped9DcuidX");
    info = map.get(pk);
    assertEquals(44, info.numSlots());
    assertEquals(44, info.blocksProduced());
  }

  @Test
  void getClusterNodes() {
    final var ji = readJsonFile("getClusterNodes.json");
    final var nodes = ClusterNode.parse(ji);
    assertEquals(6455, nodes.size());
    final var first = nodes.getFirst();
    assertEquals("204.16.245.178:8001", first.gossip());
    assertEquals(PublicKey.fromBase58Encoded("5QyArdEMku24pjd14LVfNq9oTsPLZPC1AzNrgZDyiJ73"), first.publicKey());
    assertNull(first.rpc());
    assertNull(first.pubsub());
    assertEquals("204.16.245.178:8013", first.serveRepair());
    assertEquals("204.16.245.178:8004", first.tpu());
    assertEquals("204.16.245.178:8005", first.tpuForwards());
    assertEquals("204.16.245.178:8011", first.tpuForwardsQuic());
    assertEquals("204.16.245.178:8010", first.tpuQuic());
    assertEquals("204.16.245.178:8006", first.tpuVote());
    assertEquals("204.16.245.178:8002", first.tvu());
    assertEquals("2.3.7", first.version());
    assertEquals(3640012085L, first.featureSet());
    assertEquals(50093, first.shredVersion());
    final var last = nodes.getLast();
    assertEquals("51.81.215.9:8001", last.gossip());
    assertEquals(PublicKey.fromBase58Encoded("7rSJkAqw3TEV9nkrtxJwXs9hvNi3xDxG957Ud9RBupYH"), last.publicKey());
    assertNull(last.rpc());
    assertNull(last.pubsub());
    assertEquals("51.81.215.9:8013", last.serveRepair());
    assertEquals("51.81.215.9:8004", last.tpu());
    assertEquals("51.81.215.9:8005", last.tpuForwards());
    assertEquals("51.81.215.9:8011", last.tpuForwardsQuic());
    assertEquals("51.81.215.9:8010", last.tpuQuic());
    assertEquals("51.81.215.9:8006", last.tpuVote());
    assertEquals("51.81.215.9:8002", last.tvu());
    assertEquals("2.2.20", last.version());
    assertEquals(3073396398L, last.featureSet());
    assertEquals(50093, last.shredVersion());
  }

  @Test
  void getEpochInfo() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":{"absoluteSlot":361657817,"blockHeight":339837864,"epoch":837,"slotIndex":73817,"slotsInEpoch":432000,"transactionCount":439689651069},"id":1755827084546}
        """);
    ji.skipUntil("result");
    final var info = EpochInfo.parse(ji);
    assertEquals(361657817L, info.absoluteSlot());
    assertEquals(339837864L, info.blockHeight());
    assertEquals(837L, info.epoch());
    assertEquals(73817, info.slotIndex());
    assertEquals(432000, info.slotsInEpoch());
    assertEquals(439689651069L, info.transactionCount());
  }

  @Test
  void getEpochSchedule() {
    final var ji = JsonIterator.parse(
        """
            {"jsonrpc":"2.0","result":{"firstNormalEpoch":0,"firstNormalSlot":0,"leaderScheduleSlotOffset":432000,"slotsPerEpoch":432000,"warmup":false},"id":1755826897171}
            """
    );
    ji.skipUntil("result");
    final var schedule = EpochSchedule.parse(ji);
    assertEquals(0L, schedule.firstNormalEpoch());
    assertEquals(0L, schedule.firstNormalSlot());
    assertEquals(432000L, schedule.leaderScheduleSlotOffset());
    assertEquals(432000, schedule.slotsPerEpoch());
    assertFalse(schedule.warmup());
  }

  @Test
  void getHealth() {
    final var ji = JsonIterator.parse("""
        {"id":1755825148510,"jsonrpc":"2.0","result":"ok"}
        """);
    final var health = NodeHealth.parse(ji);
    assertEquals(200, health.code());
    assertEquals("ok", health.message());
    assertEquals(0, health.numSlotsBehind());
  }

  @Test
  void getHighestSnapshotSlot() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":{"full":361469600,"incremental":361482316},"id":1755826749116}
        """
    );
    ji.skipUntil("result");
    final var slot = HighestSnapshotSlot.parse(ji);
    assertEquals(361469600L, slot.full());
    assertEquals(361482316L, slot.incremental());
  }

  @Test
  void getIdentity() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":{"identity":"5gBsaKoU2AD2E19SQ6HhzpyNhHZUxRadX1BLEnFXY22c"},"id":1755826341474}
        """);
    ji.skipUntil("result");
    final var identity = Identity.parse(ji);
    assertEquals(PublicKey.fromBase58Encoded("5gBsaKoU2AD2E19SQ6HhzpyNhHZUxRadX1BLEnFXY22c"), identity.identityKey());
  }

  @Test
  void getInflationGovernor() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":{"foundation":0.0,"foundationTerm":0.0,"initial":0.08,"taper":0.15,"terminal":0.015},"id":1755826181312}
        """);
    ji.skipUntil("result");
    final var gov = InflationGovernor.parse(ji);
    assertEquals(0.0, gov.foundation());
    assertEquals(0.0, gov.foundationTerm());
    assertEquals(0.08, gov.initial(), 1e-12);
    assertEquals(0.15, gov.taper(), 1e-12);
    assertEquals(0.015, gov.terminal(), 1e-12);
  }

  @Test
  void getInflationRate() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":{"epoch":837,"foundation":0.0,"total":0.04336960322071869,"validator":0.04336960322071869},"id":1755825914791}
        """);
    ji.skipUntil("result");
    final var rate = InflationRate.parse(ji);
    assertEquals(837L, rate.epoch());
    assertEquals(0.0, rate.foundation());
    assertEquals(0.04336960322071869, rate.total(), 1e-18);
    assertEquals(0.04336960322071869, rate.validator(), 1e-18);
  }

  @Test
  void getInflationReward() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":[{"amount":555195168,"commission":5,"effectiveSlot":361584000,"epoch":836,"postBalance":5685405695}],"id":1755825605295}
        """);
    ji.skipUntil("result");
    final var rewards = InflationReward.parse(ji);
    assertEquals(1, rewards.size());
    final var first = rewards.getFirst();
    assertEquals(555195168L, first.amount());
    assertEquals(5, first.commission());
    assertEquals(361584000L, first.effectiveSlot());
    assertEquals(836L, first.epoch());
    assertEquals(5685405695L, first.postBalance());
  }

  @Test
  void getLatestBlockHash() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"2.3.7","slot":361653479},"value":{"blockhash":"H3asooJct4u6fQmtgTGEKrBhhuSnaHJoYJuB4N1eNG4z","lastValidBlockHeight":339833681}},"id":1755825379162}
        """);
    ji.skipUntil("result");
    ji.skipUntil("context");
    final var context = Context.parse(ji);
    assertEquals(361653479L, context.slot());
    assertEquals("2.3.7", context.apiVersion());
    ji.skipUntil("value");
    final var latest = LatestBlockHash.parse(ji, context);
    assertEquals("H3asooJct4u6fQmtgTGEKrBhhuSnaHJoYJuB4N1eNG4z", latest.blockHash());
    assertEquals(339833681L, latest.lastValidBlockHeight());
  }

  @Test
  void getLeaderSchedule() {
    final var ji = readJsonFile("getLeaderSchedule.json");

    final var schedule = SolanaJsonRpcClient.KEY_LONG_ARRAY_MAP_PARSER.apply(ji);

    assertEquals(3, schedule.size());

    final var firstKey = PublicKey.fromBase58Encoded("11AMA4mnNbsrPQeuoNN7uiZVJZtqEzQHrTfa5vnbcjk");
    final var firstSlots = schedule.get(firstKey);
    assertEquals(28, firstSlots.length);
    assertEquals(32572L, firstSlots[0]);
    assertEquals(342655L, firstSlots[firstSlots.length - 1]);

    final var middleKey = PublicKey.fromBase58Encoded("12i8gndWWWMTRzJBFhnYkobNgZB3XMUUJq75HeUrshrk");
    final var middleSlots = schedule.get(middleKey);
    assertEquals(48, middleSlots.length);
    assertEquals(1176L, middleSlots[0]);
    assertEquals(406047L, middleSlots[middleSlots.length - 1]);

    final var lastKey = PublicKey.fromBase58Encoded("zeroT6PTAEjipvZuACTh1mbGCqTHgA6i1ped9DcuidX");
    final var lastSlots = schedule.get(lastKey);
    assertTrue(lastSlots.length > 0);
    assertEquals(2868L, lastSlots[0]);
    assertEquals(417731L, lastSlots[lastSlots.length - 1]);
  }

  @Test
  void getMinimumBalanceForRentExemption() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":1392000,"id":1756000000000}
        """);
    ji.skipUntil("result");
    assertEquals(1392000L, ji.readLong());
  }

  @Test
  void getRecentPerformanceSamples() {
    final var ji = readJsonFile("getRecentPerformanceSamples.json");
    final var samples = PerfSample.parse(ji);
    final var first = samples.getFirst();
    assertEquals(361652365L, first.slot());
    assertEquals(153L, first.numSlots());
    assertEquals(225012L, first.numTransactions());
    assertEquals(65191L, first.numNonVoteTransaction());
    assertEquals(60, first.samplePeriodSecs());
    final var last = samples.getLast();
    assertEquals(361585915L, last.slot());
    assertEquals(151L, last.numSlots());
    assertEquals(224291L, last.numTransactions());
    assertEquals(67423L, last.numNonVoteTransaction());
    assertEquals(60, last.samplePeriodSecs());
  }

  @Test
  void getRecentPrioritizationFees() {
    final var ji = readJsonFile("getRecentPrioritizationFees.json");
    final var fees = PrioritizationFee.parse(ji);
    assertEquals(150, fees.size());
    final var first = fees.getFirst();
    assertEquals(361651376L, first.slot());
    assertEquals(0L, first.prioritizationFee());
    final var last = fees.getLast();
    assertEquals(361651525L, last.slot());
    assertEquals(0L, last.prioritizationFee());
  }

  @Test
  void getSignaturesForAddress() {
    final var response = """
        {
          "jsonrpc": "2.0",
          "result": [
            {
              "blockTime": null,
              "confirmationStatus": "finalized",
              "err": null,
              "memo": null,
              "signature": "576BepPoQS74PwoLiBzTUBoSqjhZe72S7KXgWPwokjm7TKxatp8jAerHsq6rnZ7dZQXUJ7WoLkuJZ2qAHoFTQL9U",
              "slot": 325301549
            },
            {
              "blockTime": 1740856237,
              "confirmationStatus": "finalized",
              "err": null,
              "memo": null,
              "signature": "4TgPCZVejaHc8bVHcNc4qeQPbBeyFtBGufymhnopwwU9ELNvM8mRa12D4yQ4SctParYLnDXP8htqQTu5yFPpkszv",
              "slot": 323931131
            },
            {
              "blockTime": 1737853408,
              "confirmationStatus": "finalized",
              "err": null,
              "memo": null,
              "signature": "4eQyc8UQsCGcxiFbdSVx9oxAdLYyyuyqzgbsg77MPgA4PDPKxzKN4tyyttjNp8GfVVjYrHYna54uddJ2ygbAonNu",
              "slot": 316386176
            }
          ],
          "id": 1743859978434
        }
        """;

    final var ji = JsonIterator.parse(response).skipUntil("result");
    final var signatures = TxSig.parseSignatures(ji);
    assertEquals(3, signatures.size());

    var signature = signatures.getFirst();
    assertEquals(OptionalLong.empty(), signature.blockTime());
    assertEquals(Commitment.FINALIZED, signature.confirmationStatus());
    assertNull(signature.transactionError());
    assertNull(signature.memo());
    assertEquals("576BepPoQS74PwoLiBzTUBoSqjhZe72S7KXgWPwokjm7TKxatp8jAerHsq6rnZ7dZQXUJ7WoLkuJZ2qAHoFTQL9U", signature.signature());
    assertEquals(325301549, signature.slot());

    signature = signatures.getLast();
    assertEquals(OptionalLong.of(1737853408), signature.blockTime());
    assertEquals(Commitment.FINALIZED, signature.confirmationStatus());
    assertNull(signature.transactionError());
    assertNull(signature.memo());
    assertEquals("4eQyc8UQsCGcxiFbdSVx9oxAdLYyyuyqzgbsg77MPgA4PDPKxzKN4tyyttjNp8GfVVjYrHYna54uddJ2ygbAonNu", signature.signature());
    assertEquals(316386176, signature.slot());
  }

  @Test
  void getSignatureStatuses() {
    final var sig = "3rVoGmyPCZ5o7z1HtkXHJ1LxbRSa2ZeX2kpTogkGLczjC8JK4vEUKMX2xUuWp7S3sRaLkkeVUgGszP7MNdeQzUwA";
    final var json = """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"2.3.7","slot":361800123},"value":[{"confirmationStatus":"finalized","confirmations":null,"err":null,"slot":361781236,"status":{"Ok":null}}]},"id":1756001000000}
        """;

    final var ji = JsonIterator.parse(json);
    ji.skipUntil("result");
    ji.skipUntil("context");
    final var context = Context.parse(ji);

    ji.skipUntil("value");
    final var statusMap = TxStatus.parse(List.of(sig), ji, context);

    assertEquals(1, statusMap.size());
    final var status = statusMap.get(sig);
    assertEquals(Commitment.FINALIZED, status.confirmationStatus());
    assertTrue(status.confirmations().isEmpty());
    assertEquals(361781236L, status.slot());
    assertNull(status.error());
  }

  @Test
  void getSignatureStatuses_twoSignatures() {
    final var sig1 = "3rVoGmyPCZ5o7z1HtkXHJ1LxbRSa2ZeX2kpTogkGLczjC8JK4vEUKMX2xUuWp7S3sRaLkkeVUgGszP7MNdeQzUwA";
    final var sig2 = "3kzJMsdGmV2YRDx9NqdKeGerdQJJpjEmc6RAXGTQNv81rH2QRkp5yP5Vdap2Aq1tkHoGyLcJchvgKLQsWN4wXHvF";
    final var json = """
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"2.3.7","slot":361800123},"value":[
          {"confirmationStatus":"finalized","confirmations":null,"err":null,"slot":361781236,"status":{"Ok":null}},
          {"confirmationStatus":"finalized","confirmations":null,"err":null,"slot":361790000,"status":{"Ok":null}}
        ]},"id":1756001000001}
        """;

    final var ji = JsonIterator.parse(json);
    ji.skipUntil("result");
    ji.skipUntil("context");
    final var context = Context.parse(ji);

    ji.skipUntil("value");
    final var statusMap = TxStatus.parse(List.of(sig1, sig2), ji, context);

    assertEquals(2, statusMap.size());

    var status = statusMap.get(sig1);
    assertEquals(Commitment.FINALIZED, status.confirmationStatus());
    assertTrue(status.confirmations().isEmpty());
    assertEquals(361781236L, status.slot());
    assertNull(status.error());

    status = statusMap.get(sig2);
    assertEquals(Commitment.FINALIZED, status.confirmationStatus());
    assertTrue(status.confirmations().isEmpty());
    assertEquals(361790000L, status.slot());
    assertNull(status.error());
  }

  @Test
  void getSupply() {
    final var ji = readJsonFile("getSupply.json");
    ji.skipUntil("context");
    final var context = Context.parse(ji);
    assertEquals(361649188L, context.slot());
    assertEquals("2.3.0", context.apiVersion());
    ji.skipUntil("value");
    final var supply = Supply.parse(ji, context);
    assertEquals(608046112670045793L, supply.total());
    assertEquals(540312041171344561L, supply.circulating());
    assertEquals(67734071498701232L, supply.nonCirculating());
    final var accounts = supply.nonCirculatingAccountKeys();
    assertEquals(4765, accounts.size());
    assertEquals(PublicKey.fromBase58Encoded("DKjoc3yU6rDxcgBbVGJdbBEDbm3jsk6uLXjhGfPBmtfP"), accounts.getFirst());
    assertEquals(PublicKey.fromBase58Encoded("GXbxDdLVd7U9pEpHVGyMKB9QmJkMc8AbVKWv3rnKB48z"), accounts.getLast());
  }

  @Test
  void getTokenLargestAccounts() {
    final var ji = readJsonFile("getTokenLargestAccounts.json");

    ji.skipUntil("context");
    final var context = Context.parse(ji);
    assertEquals(361665664L, context.slot());
    assertEquals("2.2.7", context.apiVersion());

    ji.skipUntil("value");
    final var accounts = AccountTokenAmount.parse(ji, context);
    assertEquals(20, accounts.size());

    final var first = accounts.getFirst();
    assertEquals(PublicKey.fromBase58Encoded("BGLx2hbcLHk5ajMeLB2SP2zw5ZHqYTCazNJEB9uGcz3q"), first.addressKey());
    assertEquals(new java.math.BigInteger("854699973338"), first.amount());
    assertEquals(8, first.decimals());

    final var last = accounts.getLast();
    assertEquals(PublicKey.fromBase58Encoded("2uLJHiUCxdWh8oNk26ZzowDNn52YL51zoW1AJwwS8qHU"), last.addressKey());
    assertEquals(new java.math.BigInteger("4917359247"), last.amount());
    assertEquals(8, last.decimals());
  }

  @Test
  void getTokenSupply() {
    final var ji = JsonIterator.parse("""
        {
          "amount": "2099933720285",
          "decimals": 8,
          "uiAmount": 20999.33720285,
          "uiAmountString": "20999.33720285"
        }""");
    final var tokenAmount = TokenAmount.parse(ji, new Context(0L, null));
    assertEquals(new BigInteger("2099933720285"), tokenAmount.amount());
    assertEquals(8, tokenAmount.decimals());
    assertEquals(new BigDecimal("20999.33720285"), tokenAmount.toDecimal());
  }

  @Test
  void getTransaction() {
    final var ji = readJsonFile("getTransaction.json");
    final var tx = Tx.parse(ji);

    assertEquals(361781236, tx.slot());
    assertTrue(tx.blockTime().isPresent());
    assertEquals(1755875675L, tx.blockTime().getAsLong());
    assertEquals(0, tx.version());

    final var expectedB64 = "AZ0BPuuFRxp6dwr9yc0psv2P6ckCUySVSuOtbUOPOMNxVAfg9p/bxslKikhRm9toZAAywjNkkcGKsjkRzq0J1giAAQAHC7k0Voda6oDB32Boc+6g34XxgAvuo9lQYkZjigs40IBBTHOBvAVthDV/IZaAOFCq44xGH9v9GR6e/81LEpCFDqphFns0SmYTx0xfxWqdMZf9CpT/IGesOcIc5xaF8viuYtMNyBFeF9ES7riTYnSYZiOAXpMd2/R/mTu7u8EMGESPAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADBkZv5SEXMv/srbpyw5vnvIzlu8X3EmssQ5s6QAAAAAR51VvyMcBu7nTFbs5oFQf9sbLeo/SOUQKxzaJWvBOPBt324ddloZPZy+FGzut5rBy0he1fWzeROoz1hX7/AKmMlyWPTiSJ8bs9ECkUjg2DC1oTmdr/EIQEjnvY2+n4WbQ/+if11/ZKdMCbHylYed5LCas238ndUUsyGqezjOXoxvp6877brTo9ZfNqq8l0MbG75MLS9uDkfKYCA0UvXWHWp7Nb6+I8MNZoFR5Wu3BCfcNaA/jVDDraXqHy7yCerwcFAAUCXhoCAAUACQO2OgAAAAAAAAgGAAEAEwQHAQEEAgABDAIAAAAEAgAAAAAAAAcBAQERBhgHAAEDBgoCCQYVABQRAQMLEBIHDw4MDQYj5RfLl3rjrSoBAAAAGmQAAQQCAAAAAAAAZAAAAAAAAAAPAFUHAwEAAAEJASG8bfvETPF+/B2T/ZwftAIdsXzl/YqOFB9AHZC4D09NCHaAeHd5en58AwJ1dA==";
    final byte[] expectedBytes = java.util.Base64.getDecoder().decode(expectedB64);
    assertArrayEquals(expectedBytes, tx.data());

    final var meta = tx.meta();
    assertNull(meta.error());
    assertEquals(111048, meta.computeUnitsConsumed());
    assertEquals(7072L, meta.fee());

    final var ii = meta.innerInstructions();
    assertEquals(2, ii.size());
    assertEquals(2, ii.getFirst().index());
    assertEquals(5, ii.getLast().index());
    final var firstIns = ii.getFirst().instructions();
    assertEquals(4, firstIns.size());
    assertEquals(7, firstIns.getFirst().programIdIndex());
    assertEquals(2, firstIns.getFirst().stackHeight());
    final var firstAccs = firstIns.getFirst().accountIndices();
    assertEquals(1, firstAccs.length);
    assertEquals(19, firstAccs[0]);
    final var lastIns = ii.getLast().instructions();
    assertEquals(6, lastIns.size());
    assertEquals(6, lastIns.get(3).programIdIndex());

    final var la = meta.loadedAddresses();
    assertEquals(3, la.readonly().size());
    assertEquals(PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112"), la.readonly().getFirst());
    assertEquals(PublicKey.fromBase58Encoded("HpNfyc2Saw7RKkQd8nEL4khUcuPhQ7WwY1B2qjx8jxFq"), la.readonly().getLast());
    assertEquals(8, la.writable().size());
    assertEquals(PublicKey.fromBase58Encoded("bHHnvxhkzBebvqxpnzVaXSdQ1GdFeZFg8yi9YKgL7zE"), la.writable().getFirst());
    assertEquals(PublicKey.fromBase58Encoded("EaEKZFeuKvws16cfgkaCqZBwpvEdCC6Gsw6282A9WsCD"), la.writable().getLast());

    final var logs = meta.logMessages();
    assertEquals(63, logs.size());
    assertEquals("Program ComputeBudget111111111111111111111111111111 invoke [1]", logs.getFirst());
    assertEquals("Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success", logs.getLast());

    final var pre = meta.preBalances();
    assertEquals(22, pre.size());
    assertEquals(31503024L, pre.getFirst());
    assertEquals(1141440L, pre.getLast());

    final var post = meta.postBalances();
    assertEquals(22, post.size());
    assertEquals(31495436L, post.getFirst());
    assertEquals(1141440L, post.getLast());

    final var preTB = meta.preTokenBalances();
    assertEquals(4, preTB.size());
    assertEquals(2, preTB.getFirst().accountIndex());
    assertEquals(PublicKey.fromBase58Encoded("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"), preTB.getFirst().mint());
    assertEquals(6, preTB.getFirst().decimals());
    assertEquals(new java.math.BigInteger("36509291904"), preTB.getFirst().amount());
    assertEquals(16, preTB.getLast().accountIndex());
    assertEquals(new java.math.BigInteger("74495551"), preTB.getLast().amount());

    final var postTB = meta.postTokenBalances();
    assertEquals(4, postTB.size());
    assertEquals(2, postTB.getFirst().accountIndex());
    assertEquals(new java.math.BigInteger("36509291904"), postTB.getFirst().amount());
    assertEquals(16, postTB.getLast().accountIndex());
    assertEquals(new java.math.BigInteger("74495451"), postTB.getLast().amount());

    assertTrue(meta.rewards().isEmpty());
  }

  @Test
  void getVersion() {
    final var ji = JsonIterator.parse("""
        {
          "feature-set": 3640012085,
          "solana-core": "2.3.7"
        }""");
    final var version = Version.parse(ji);
    assertEquals(3640012085L, version.featureSet());
    assertEquals("2.3.7", version.version());
  }

  @Test
  void getVoteAccounts() {
    final var ji = readJsonFile("getVoteAccounts.json");
    final var voteAccounts = VoteAccounts.parse(ji);

    final var current = voteAccounts.current();
    assertEquals(1040, current.size());
    final var cFirst = current.getFirst();
    assertEquals(11465289069670L, cFirst.activatedStake());
    assertEquals(100, cFirst.commission());
    assertTrue(cFirst.epochVoteAccount());
    assertEquals(361641764L, cFirst.lastVote());
    assertEquals(361641733L, cFirst.rootSlot());
    assertEquals(PublicKey.fromBase58Encoded("HLv4d6uhQ7ViicNQ1ff6RHNNntNzmq1bATLne2kCW5VV"), cFirst.nodeKey());
    assertEquals(PublicKey.fromBase58Encoded("ELvd1ayPGicuX9yBNr6tn3V3BUCa12Cme8FDdghcmskf"), cFirst.voteKey());

    final var cFirstCredits = cFirst.epochCredits();
    assertEquals(5, cFirstCredits.size());
    final var cFirstCreditFirst = cFirstCredits.getFirst();
    final var cFirstCreditLast = cFirstCredits.getLast();
    assertEquals(833L, cFirstCreditFirst.epoch());
    assertEquals(980500237L, cFirstCreditFirst.credits());
    assertEquals(973736985L, cFirstCreditFirst.previousCredits());
    assertEquals(837L, cFirstCreditLast.epoch());
    assertEquals(1002086866L, cFirstCreditLast.credits());
    assertEquals(1001166824L, cFirstCreditLast.previousCredits());

    final var cLast = current.getLast();
    assertEquals(78492164902659L, cLast.activatedStake());
    assertEquals(0, cLast.commission());
    assertTrue(cLast.epochVoteAccount());
    assertEquals(361641764L, cLast.lastVote());
    assertEquals(361641733L, cLast.rootSlot());
    assertEquals(PublicKey.fromBase58Encoded("GWJyUxzcVwRRtpLuLiu1mpiUQsZ4onYFAYfCjQnuLmz5"), cLast.nodeKey());
    assertEquals(PublicKey.fromBase58Encoded("9KgZYnDzHhQANoJ43Z8czkgXYdTjtWLRrH9nDf42gqa"), cLast.voteKey());

    final var cLastCredits = cLast.epochCredits();
    assertEquals(5, cLastCredits.size());
    final var cLastCreditFirst = cLastCredits.getFirst();
    final var cLastCreditLast = cLastCredits.getLast();
    assertEquals(833L, cLastCreditFirst.epoch());
    assertEquals(41409487L, cLastCreditFirst.credits());
    assertEquals(34513212L, cLastCreditFirst.previousCredits());
    assertEquals(837L, cLastCreditLast.epoch());
    assertEquals(63020666L, cLastCreditLast.credits());
    assertEquals(62099211L, cLastCreditLast.previousCredits());

    final var delinquent = voteAccounts.delinquent();
    assertEquals(48, delinquent.size());
    final var dFirst = delinquent.getFirst();
    assertEquals(19961570333052L, dFirst.activatedStake());
    assertEquals(5, dFirst.commission());
    assertTrue(dFirst.epochVoteAccount());
    assertEquals(360961674L, dFirst.lastVote());
    assertEquals(360961643L, dFirst.rootSlot());
    assertEquals(PublicKey.fromBase58Encoded("A31PGH4i5xGn7SHWpsQRhpBYUwanRuqNrHBp8bSeCSEr"), dFirst.nodeKey());
    assertEquals(PublicKey.fromBase58Encoded("EkLA4nA5jtM2t2FkNWo6XWAyvQyaJJUZoX5p7LMawoaz"), dFirst.voteKey());

    final var dFirstCredits = dFirst.epochCredits();
    assertEquals(5, dFirstCredits.size());
    final var dFirstCreditFirst = dFirstCredits.getFirst();
    final var dFirstCreditLast = dFirstCredits.getLast();
    assertEquals(831L, dFirstCreditFirst.epoch());
    assertEquals(1132881659L, dFirstCreditFirst.credits());
    assertEquals(1125982031L, dFirstCreditFirst.previousCredits());
    assertEquals(835L, dFirstCreditLast.epoch());
    assertEquals(1157386379L, dFirstCreditLast.credits());
    assertEquals(1153531359L, dFirstCreditLast.previousCredits());

    final var dLast = delinquent.getLast();
    assertEquals(1386090708803L, dLast.activatedStake());
    assertEquals(100, dLast.commission());
    assertTrue(dLast.epochVoteAccount());
    assertEquals(361624214L, dLast.lastVote());
    assertEquals(361624183L, dLast.rootSlot());
    assertEquals(PublicKey.fromBase58Encoded("AAHSdsnRREfdQNzDGRxai8CLXh9EPCoRdwULPqBYd9fb"), dLast.nodeKey());
    assertEquals(PublicKey.fromBase58Encoded("91ciyr81FJnZaoWcDT4PHwwdzgNp21cgH354JbCuxnwR"), dLast.voteKey());

    final var dLastCredits = dLast.epochCredits();
    assertEquals(5, dLastCredits.size());
    final var dLastCreditFirst = dLastCredits.getFirst();
    final var dLastCreditLast = dLastCredits.getLast();
    assertEquals(829L, dLastCreditFirst.epoch());
    assertEquals(987904709L, dLastCreditFirst.credits());
    assertEquals(987903119L, dLastCreditFirst.previousCredits());
    assertEquals(837L, dLastCreditLast.epoch());
    assertEquals(988506203L, dLastCreditLast.credits());
    assertEquals(988504142L, dLastCreditLast.previousCredits());
  }

  @Test
  void testParseOldBlock() {
    final var response = """
        {
          "jsonrpc": "2.0",
          "result": {
            "blockHeight": null,
            "blockTime": null,
            "blockhash": "4w2QK5udZJKwXhNcssAEc8mATt8o1ZnFQrzGJg9NGZpz",
            "parentSlot": 16383,
            "previousBlockhash": "PNRrabCwVfJmYdrxtKV4euA5daxsbFtYZEvcZWWxf2e",
            "rewards": []
          },
          "id": 1742586921998
        }
        """;

    final var ji = JsonIterator.parse(response).skipUntil("result");
    final var block = Block.parse(ji);
    assertEquals(0, block.blockHeight());
    assertEquals(0, block.blockTime());
    assertEquals("4w2QK5udZJKwXhNcssAEc8mATt8o1ZnFQrzGJg9NGZpz", block.blockHash());
    assertEquals(16383, block.parentSlot());
    assertEquals("PNRrabCwVfJmYdrxtKV4euA5daxsbFtYZEvcZWWxf2e", block.previousBlockHash());
    assertEquals(0, block.rewards().size());
    assertEquals(0, block.signatures().size());
  }

  @Test
  void testBlockWithSignatures() {
    final var response = """
        {
          "jsonrpc": "2.0",
          "result": {
            "blockHeight": 306538416,
            "blockTime": 1742587108,
            "blockhash": "4gmejZCH4Hokk3YWmZfKyV1Y1Yj2rkChnejHmEeLSJ3e",
            "parentSlot": 328284370,
            "previousBlockhash": "7q6NkbrNTXnYBA9iS7kHcSDPARbwN5tbG48ZrjWk1czR",
            "rewards": [
              {
                "commission": null,
                "lamports": 18646889,
                "postBalance": 51603762212,
                "pubkey": "GoeW4aFK4dGoekJySgUynWDxBZiQJqm8GDAF4H53tDK9",
                "rewardType": "Fee"
              }
            ],
            "signatures": [
              "5rxL9uYfPTYf74JQvLxKTNr2iAzz99Cbdo4tAmWj8m3N85JBMsF6hnA1nWi2f3KsjYJVqGVTx45rgZHFgwjz2mg9",
              "5SBCThjjegPpWDHcW8esGTK7UYJwpKnKrwBVhHVtNzg3XmReFBQYAw5pE1u8SDgWaW8WU7CYBmihKtet1rqHVRqc",
              "3L8q8yNQCZP7L5VraVUPSdnVXHAGqtNqf891tc81QGrDZQQr3BJxExUVWcfu5BJKaBaPkDofdpGQ2bGsoFdW7UGT"
            ]
          },
          "id": 1742588327228
        }""";

    final var ji = JsonIterator.parse(response).skipUntil("result");
    final var block = Block.parse(ji);
    assertEquals(306538416, block.blockHeight());
    assertEquals(1742587108, block.blockTime());
    assertEquals("4gmejZCH4Hokk3YWmZfKyV1Y1Yj2rkChnejHmEeLSJ3e", block.blockHash());
    assertEquals(328284370, block.parentSlot());
    assertEquals("7q6NkbrNTXnYBA9iS7kHcSDPARbwN5tbG48ZrjWk1czR", block.previousBlockHash());

    final var rewardList = block.rewards();
    assertEquals(1, rewardList.size());
    final var reward = rewardList.getFirst();
    assertEquals(0, reward.commission());
    assertEquals(18646889, reward.lamports());
    assertEquals(51603762212L, reward.postBalance());
    assertEquals(PublicKey.fromBase58Encoded("GoeW4aFK4dGoekJySgUynWDxBZiQJqm8GDAF4H53tDK9"), reward.publicKey());
    assertEquals(RewardType.FEE, reward.rewardType());

    final var signatures = block.signatures();
    assertEquals(3, signatures.size());
    assertEquals("5rxL9uYfPTYf74JQvLxKTNr2iAzz99Cbdo4tAmWj8m3N85JBMsF6hnA1nWi2f3KsjYJVqGVTx45rgZHFgwjz2mg9", signatures.getFirst());
    assertEquals("5SBCThjjegPpWDHcW8esGTK7UYJwpKnKrwBVhHVtNzg3XmReFBQYAw5pE1u8SDgWaW8WU7CYBmihKtet1rqHVRqc", signatures.get(1));
    assertEquals("3L8q8yNQCZP7L5VraVUPSdnVXHAGqtNqf891tc81QGrDZQQr3BJxExUVWcfu5BJKaBaPkDofdpGQ2bGsoFdW7UGT", signatures.getLast());
  }
}
