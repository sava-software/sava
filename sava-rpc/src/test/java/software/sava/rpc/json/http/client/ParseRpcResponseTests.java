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
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

final class ParseRpcResponseTests {

  private static final ClassLoader CLASS_LOADER = ParseRpcResponseTests.class.getClassLoader();

  private static JsonIterator readJsonFile(final String resourcePath) {
    final var resource = CLASS_LOADER.getResource("rpc_response_data/" + resourcePath);
    if (resource == null) {
      fail("Test resource not found: " + resourcePath);
    }
    try (final var in = resource.openStream()) {
      return JsonIterator.parse(in.readAllBytes()).skipUntil("result");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void getBlockCommitment() {
    final var ji = JsonIterator.parse("""
        {"jsonrpc":"2.0","result":{"commitment":null,"totalStake":405146599541901117},"id":1755829799753}
        """
    ).skipUntil("result");
    final var bc = BlockCommitment.parse(ji);
    assertNotNull(bc);
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
    assertNotNull(bc);
    assertEquals(32, bc.commitment().length);
    for (int i = 0; i < 31; i++) {
      assertEquals(0L, bc.commitment()[i]);
    }
    assertEquals(404893464137748549L, bc.commitment()[31]);
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
    assertNotNull(info);
    assertEquals(8, info.numSlots());
    assertEquals(8, info.blocksProduced());

    pk = PublicKey.fromBase58Encoded("2374M8ZtmrpdY3ywb7fLqokSd2mRhvdJu2PUwoJmbTUh");
    info = map.get(pk);
    assertNotNull(info);
    assertEquals(16, info.numSlots());
    assertEquals(12, info.blocksProduced());

    pk = PublicKey.fromBase58Encoded("2GUnfxZavKoPfS9s3VSEjaWDzB3vNf5RojUhprCS1rSx");
    info = map.get(pk);
    assertNotNull(info);
    assertEquals(468, info.numSlots());
    assertEquals(464, info.blocksProduced());

    pk = PublicKey.fromBase58Encoded("22rU5yUmdVThrkoPieVNphqEyAtMQKmZxjwcD8v4bJDU");
    info = map.get(pk);
    assertNotNull(info);
    assertEquals(408, info.numSlots());
    assertEquals(408, info.blocksProduced());

    pk = PublicKey.fromBase58Encoded("zeroT6PTAEjipvZuACTh1mbGCqTHgA6i1ped9DcuidX");
    info = map.get(pk);
    assertNotNull(info);
    assertEquals(44, info.numSlots());
    assertEquals(44, info.blocksProduced());
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
    final var last = rewards.getLast();
    assertEquals(555195168L, last.amount());
    assertEquals(5, last.commission());
    assertEquals(361584000L, last.effectiveSlot());
    assertEquals(836L, last.epoch());
    assertEquals(5685405695L, last.postBalance());
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
    final var rewardLast = rewardList.getLast();
    assertEquals(0, rewardLast.commission());
    assertEquals(18646889, rewardLast.lamports());
    assertEquals(51603762212L, rewardLast.postBalance());
    assertEquals(PublicKey.fromBase58Encoded("GoeW4aFK4dGoekJySgUynWDxBZiQJqm8GDAF4H53tDK9"), rewardLast.publicKey());
    assertEquals(RewardType.FEE, rewardLast.rewardType());

    final var signatures = block.signatures();
    assertEquals(3, signatures.size());
    assertEquals("5rxL9uYfPTYf74JQvLxKTNr2iAzz99Cbdo4tAmWj8m3N85JBMsF6hnA1nWi2f3KsjYJVqGVTx45rgZHFgwjz2mg9", signatures.getFirst());
    assertEquals("5SBCThjjegPpWDHcW8esGTK7UYJwpKnKrwBVhHVtNzg3XmReFBQYAw5pE1u8SDgWaW8WU7CYBmihKtet1rqHVRqc", signatures.get(1));
    assertEquals("3L8q8yNQCZP7L5VraVUPSdnVXHAGqtNqf891tc81QGrDZQQr3BJxExUVWcfu5BJKaBaPkDofdpGQ2bGsoFdW7UGT", signatures.getLast());
  }
}
