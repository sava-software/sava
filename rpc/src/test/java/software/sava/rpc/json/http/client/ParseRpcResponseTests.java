package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.Block;
import software.sava.rpc.json.http.response.RewardType;
import software.sava.rpc.json.http.response.TxSig;
import systems.comodal.jsoniter.JsonIterator;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ParseRpcResponseTests {

  @Test
  void testSignaturesForAddress() {
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
    assertEquals("GoeW4aFK4dGoekJySgUynWDxBZiQJqm8GDAF4H53tDK9", reward.pubKey());
    assertEquals(RewardType.FEE, reward.rewardType());

    final var signatures = block.signatures();
    assertEquals(3, signatures.size());
    assertEquals("5rxL9uYfPTYf74JQvLxKTNr2iAzz99Cbdo4tAmWj8m3N85JBMsF6hnA1nWi2f3KsjYJVqGVTx45rgZHFgwjz2mg9", signatures.getFirst());
    assertEquals("5SBCThjjegPpWDHcW8esGTK7UYJwpKnKrwBVhHVtNzg3XmReFBQYAw5pE1u8SDgWaW8WU7CYBmihKtet1rqHVRqc", signatures.get(1));
    assertEquals("3L8q8yNQCZP7L5VraVUPSdnVXHAGqtNqf891tc81QGrDZQQr3BJxExUVWcfu5BJKaBaPkDofdpGQ2bGsoFdW7UGT", signatures.getLast());

  }
}
