package software.sava.rpc.json.http.response;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TxReward and VoteAccount previously had no direct parse coverage; their
// FieldMatcher dispatch couples field handling to declaration order, so pin
// every field — including the guards (null commission, absent
// inflationRewardsCommissionBps) and the case-insensitive reward type.
final class ParseRewardsAndVoteAccountsTests {

  @Test
  void parseRewards() {
    final var json = """
        [
          {
            "commission":5,
            "lamports":-125000,
            "postBalance":499998932500,
            "pubkey":"9wYbdLDCVSXCVPqvXsgkDkyTTCFC7HgHm6QrfPWBrRzn",
            "rewardType":"Staking",
            "unknownField":{"skipped":[1,2]}
          },
          {
            "commission":null,
            "lamports":10,
            "postBalance":42,
            "pubkey":"3nvAV4PVG2w1F9GDh3YMnhYNvEEzV3LRMJ5e6bMYcULk",
            "rewardType":"rent"
          }
        ]""";
    final var rewards = TxReward.parseRewards(JsonIterator.parse(json));
    assertEquals(2, rewards.size());

    var reward = rewards.getFirst();
    assertEquals(PublicKey.fromBase58Encoded("9wYbdLDCVSXCVPqvXsgkDkyTTCFC7HgHm6QrfPWBrRzn"), reward.publicKey());
    assertEquals(-125000, reward.lamports());
    assertEquals(499998932500L, reward.postBalance());
    assertEquals(RewardType.STAKING, reward.rewardType());
    assertEquals(5, reward.commission());

    reward = rewards.getLast();
    assertEquals(PublicKey.fromBase58Encoded("3nvAV4PVG2w1F9GDh3YMnhYNvEEzV3LRMJ5e6bMYcULk"), reward.publicKey());
    assertEquals(10, reward.lamports());
    assertEquals(42, reward.postBalance());
    assertEquals(RewardType.RENT, reward.rewardType());
    assertEquals(0, reward.commission());

    assertTrue(TxReward.parseRewards(JsonIterator.parse("null")).isEmpty());
  }

  @Test
  void parseVoteAccounts() {
    final var json = """
        {
          "current":[
            {
              "commission":0,
              "epochVoteAccount":true,
              "epochCredits":[[1,64,0],[2,192,64]],
              "nodePubkey":"B97CCUW3AEZFGy6uUg6zUdnNYvnVq5VG8PUtb2HayTDD",
              "lastVote":147,
              "activatedStake":42,
              "votePubkey":"3ZT31jkAGhUaw8jsy4bTknwBMP8i4Eueh52By4zXcsVw",
              "rootSlot":104570885,
              "inflationRewardsCommissionBps":250
            }
          ],
          "delinquent":[
            {
              "commission":127,
              "epochVoteAccount":false,
              "epochCredits":[],
              "nodePubkey":"6ZPxeQaDo4bkZLRsdNrCzchNQr5LN9QMc9sipXv9Kw8f",
              "lastVote":0,
              "activatedStake":0,
              "votePubkey":"CmgCk4aMS7KW1SHX3s9K5tBJ6Yng2LBaC8MFov4wx9sm",
              "rootSlot":0
            }
          ]
        }""";
    final var voteAccounts = VoteAccounts.parse(JsonIterator.parse(json));

    assertEquals(1, voteAccounts.current().size());
    final var current = voteAccounts.current().getFirst();
    assertEquals(PublicKey.fromBase58Encoded("3ZT31jkAGhUaw8jsy4bTknwBMP8i4Eueh52By4zXcsVw"), current.voteKey());
    assertEquals(PublicKey.fromBase58Encoded("B97CCUW3AEZFGy6uUg6zUdnNYvnVq5VG8PUtb2HayTDD"), current.nodeKey());
    assertEquals(42, current.activatedStake());
    assertTrue(current.epochVoteAccount());
    assertEquals(0, current.commission());
    assertEquals(OptionalInt.of(250), current.inflationRewardsCommissionBps());
    assertEquals(147, current.lastVote());
    assertEquals(104570885, current.rootSlot());
    assertEquals(List.of(new EpochCredits(1, 64, 0), new EpochCredits(2, 192, 64)), current.epochCredits());

    assertEquals(1, voteAccounts.delinquent().size());
    final var delinquent = voteAccounts.delinquent().getFirst();
    assertEquals(PublicKey.fromBase58Encoded("CmgCk4aMS7KW1SHX3s9K5tBJ6Yng2LBaC8MFov4wx9sm"), delinquent.voteKey());
    assertEquals(127, delinquent.commission());
    assertEquals(OptionalInt.empty(), delinquent.inflationRewardsCommissionBps());
    assertTrue(delinquent.epochCredits().isEmpty());
  }

  @Test
  void unknownRewardTypeIsNull() {
    final var json = """
        [{"pubkey":"9wYbdLDCVSXCVPqvXsgkDkyTTCFC7HgHm6QrfPWBrRzn","rewardType":"burn","lamports":1,"postBalance":2,"commission":0}]""";
    final var rewards = TxReward.parseRewards(JsonIterator.parse(json));
    assertNull(rewards.getFirst().rewardType());
  }
}
