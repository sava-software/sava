package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.response.Tx;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

final class ParseTXTests {

  @Test
  void testParseTX() {
    final var jsonResponse = """
        {
          "jsonrpc":"2.0",
          "result":{
            "blockTime":1732156970,
            "meta":{
              "computeUnitsConsumed":74477,
              "err":null,
              "fee":62024,
              "innerInstructions":[
                {
                  "index":2,
                  "instructions":[
                    {
                      "accounts":[
                        13
                      ],
                      "data":"84eT",
                      "programIdIndex":6,
                      "stackHeight":2
                    },
                    {
                      "accounts":[
                        0,
                        1
                      ],
                      "data":"11119os1e9qSs2u7TsThXqkBSRVFxhmYaFKFZ1waB2X7armDmvK3p5GmLdUxYdg3h7QSrL",
                      "programIdIndex":3,
                      "stackHeight":2
                    },
                    {
                      "accounts":[
                        1
                      ],
                      "data":"P",
                      "programIdIndex":6,
                      "stackHeight":2
                    },
                    {
                      "accounts":[
                        1,
                        13
                      ],
                      "data":"6auHQdCNgSFbow1FwY7q5okS6HR93Lmaw5gGnfhCzMfcU",
                      "programIdIndex":6,
                      "stackHeight":2
                    }
                  ]
                },
                {
                  "index":5,
                  "instructions":[
                    {
                      "accounts":[
                        6,
                        10,
                        14,
                        10,
                        12,
                        11,
                        10,
                        10,
                        10,
                        10,
                        10,
                        10,
                        10,
                        10,
                        1,
                        2,
                        0
                      ],
                      "data":"5uZJEbuZKBdvJJ4oxtz2buR",
                      "programIdIndex":15,
                      "stackHeight":2
                    },
                    {
                      "accounts":[
                        1,
                        12,
                        0
                      ],
                      "data":"3DXRMMziYTL3",
                      "programIdIndex":6,
                      "stackHeight":3
                    },
                    {
                      "accounts":[
                        11,
                        2,
                        14
                      ],
                      "data":"3J2CfBdE26iB",
                      "programIdIndex":6,
                      "stackHeight":3
                    },
                    {
                      "accounts":[
                        9
                      ],
                      "data":"QMqFu4fYGGeUEysFnenhAvR83g86EDDNxzUskfkWKYCBPWe1hqgD6jgKAXr6aYoEQaxoqYMTvWgPVk2AHWGHjdbNiNtoaPfZA4znu6cRUSWSeJF9uSjAY6beqYCHcgxCSLGoPDABThY1WoDL8UrGdxPMHsMAb4ijDY5RmSzh2FDodqZ",
                      "programIdIndex":5,
                      "stackHeight":2
                    }
                  ]
                }
              ],
              "loadedAddresses":{
                "readonly":[
                  "So11111111111111111111111111111111111111112",
                  "5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1",
                  "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8"
                ],
                "writable":[
                  "7bbUVnKL7McGS1tH5qfNNWCANWNXjXELF6BLqVe4Batt",
                  "8v34GQcr3FPuTjuJYfKtNLqsaRxTWriPDRjJVfdzrJHP",
                  "GSUxFEYjsh9Xxa8A9k689YwG4t9NUNQvN4YinSiroXmp"
                ]
              },
              "logMessages":[
                "Program ComputeBudget111111111111111111111111111111 invoke [1]",
                "Program ComputeBudget111111111111111111111111111111 success",
                "Program ComputeBudget111111111111111111111111111111 invoke [1]",
                "Program ComputeBudget111111111111111111111111111111 success",
                "Program ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL invoke [1]",
                "Program log: CreateIdempotent",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA invoke [2]",
                "Program log: Instruction: GetAccountDataSize",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA consumed 1569 of 86392 compute units",
                "Program return: TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA pQAAAAAAAAA=",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success",
                "Program 11111111111111111111111111111111 invoke [2]",
                "Program 11111111111111111111111111111111 success",
                "Program log: Initialize the associated token account",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA invoke [2]",
                "Program log: Instruction: InitializeImmutableOwner",
                "Program log: Please upgrade to SPL Token 2022 for immutable owner support",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA consumed 1405 of 79805 compute units",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA invoke [2]",
                "Program log: Instruction: InitializeAccount3",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA consumed 3158 of 75923 compute units",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success",
                "Program ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL consumed 19315 of 91797 compute units",
                "Program ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL success",
                "Program 11111111111111111111111111111111 invoke [1]",
                "Program 11111111111111111111111111111111 success",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA invoke [1]",
                "Program log: Instruction: SyncNative",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA consumed 3045 of 72332 compute units",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success",
                "Program JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 invoke [1]",
                "Program log: Instruction: Route",
                "Program 675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8 invoke [2]",
                "Program log: ray_log: AwBlzR0AAAAAAAAAAAAAAAACAAAAAAAAAABlzR0AAAAAUIjNtp0AAADl8ebG/yEAABtHYmcGAAAA",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA invoke [3]",
                "Program log: Instruction: Transfer",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA consumed 4736 of 38732 compute units",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA invoke [3]",
                "Program log: Instruction: Transfer",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA consumed 4645 of 31015 compute units",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success",
                "Program 675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8 consumed 29660 of 55175 compute units",
                "Program 675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8 success",
                "Program JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 invoke [2]",
                "Program JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 consumed 471 of 23141 compute units",
                "Program JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 success",
                "Program JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 consumed 48752 of 69287 compute units",
                "Program return: JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 G0diZwYAAAA=",
                "Program JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4 success",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA invoke [1]",
                "Program log: Instruction: CloseAccount",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA consumed 2915 of 20535 compute units",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success"
              ],
              "postBalances":[
                4560419218,
                0,
                2039280,
                1,
                1,
                1141440,
                934087680,
                731913600,
                1461600,
                0,
                6124800,
                2039280,
                677878827840,
                725135288856,
                13078066259,
                1141440
              ],
              "postTokenBalances":[
                {
                  "accountIndex":2,
                  "mint":"BUZFom1YPnAZVYSccbpddBmFYmHcS4Xtc3JQG5cFpump",
                  "owner":"Emajgzqt9QKyfXARSxdkKmzhnXZDw2KENm6wB7XB4XBW",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"27504297755",
                    "decimals":6,
                    "uiAmount":27504.297755,
                    "uiAmountString":"27504.297755"
                  }
                },
                {
                  "accountIndex":11,
                  "mint":"BUZFom1YPnAZVYSccbpddBmFYmHcS4Xtc3JQG5cFpump",
                  "owner":"5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"37354933103306",
                    "decimals":6,
                    "uiAmount":37354933.103306,
                    "uiAmountString":"37354933.103306"
                  }
                },
                {
                  "accountIndex":12,
                  "mint":"So11111111111111111111111111111111111111112",
                  "owner":"5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"677876788560",
                    "decimals":9,
                    "uiAmount":677.87678856,
                    "uiAmountString":"677.87678856"
                  }
                }
              ],
              "preBalances":[
                5060481242,
                0,
                2039280,
                1,
                1,
                1141440,
                934087680,
                731913600,
                1461600,
                0,
                6124800,
                2039280,
                677378827840,
                725135288856,
                13078066259,
                1141440
              ],
              "preTokenBalances":[
                {
                  "accountIndex":2,
                  "mint":"BUZFom1YPnAZVYSccbpddBmFYmHcS4Xtc3JQG5cFpump",
                  "owner":"Emajgzqt9QKyfXARSxdkKmzhnXZDw2KENm6wB7XB4XBW",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"0",
                    "decimals":6,
                    "uiAmount":null,
                    "uiAmountString":"0"
                  }
                },
                {
                  "accountIndex":11,
                  "mint":"BUZFom1YPnAZVYSccbpddBmFYmHcS4Xtc3JQG5cFpump",
                  "owner":"5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"37382437401061",
                    "decimals":6,
                    "uiAmount":37382437.401061,
                    "uiAmountString":"37382437.401061"
                  }
                },
                {
                  "accountIndex":12,
                  "mint":"So11111111111111111111111111111111111111112",
                  "owner":"5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"677376788560",
                    "decimals":9,
                    "uiAmount":677.37678856,
                    "uiAmountString":"677.37678856"
                  }
                }
              ],
              "rewards":[
              ],
              "status":{
                "Ok":null
              }
            },
            "slot":302649801,
            "transaction":[
              "AXEEVAlzkqo+Fe/uM19xW1t3DnuIpvUpAz5hOZOIFeUfdp1QAK0nK62rQj2TI+xZAf0GmkuojHLQ7f/zriQO5AyAAQAHCsyToY8eD6JJtvYne1BW0DeQuASlERJoCDNd8yywEjTx4TJikpKjqpZeXOBj+DbfbrYXnvOKF0R26eu4wgflXdHiT1rbiEqoqhsKM+ttmJZNnayblXbJ6lvGvvdtUseTfwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwZGb+UhFzL/7K26csOb57yM5bvF9xJrLEObOkAAAAAEedVb8jHAbu50xW7OaBUH/bGy3qP0jlECsc2iVrwTjwbd9uHXZaGT2cvhRs7reawctIXtX1s3kTqM9YV+/wCpjJclj04kifG7PRApFI4NgwtaE5na/xCEBI572Nvp+FmbpAWzaYYXm57lIfkX2aVCtN8wBrez/pWNiNsRmD6GD7Q/+if11/ZKdMCbHylYed5LCas238ndUUsyGqezjOXoJPOq7tXFmriCfnyNQ6vH8nUcS1Is/1tt8y7eXK2XyHgHBAAFAsFnAQAEAAkDpXIJAAAAAAAHBgABAA0DBgEBAwIAAQwCAAAAAGXNHQAAAAAGAQEBEQUbBgABAgUIBQkFDwYKDgoMCwoKCgoKCgoKAQIAI+UXy5d6460qAQAAAAdkAAEAZc0dAAAAABtHYmcGAAAAvAIABgMBAAABCQF3G0n2vTsDayxLcoRWWkzA4zkRSASDNPh0C5VsBXIxYAM5PD0DEAQD",
              "base64"
            ],
            "version":0
          },
          "id":1732198313176
        }""";

    final var ji = JsonIterator.parse(jsonResponse);
    ji.skipUntil("result");
    final var tx = Tx.parse(ji);
    assertTrue(tx.blockTime().isPresent());
    assertEquals(1732156970L, tx.blockTime().getAsLong());
    assertEquals(302649801L, tx.slot());
    assertEquals(0, tx.version());
    assertFalse(tx.isLegacy());

    final byte[] txData = Base64.getDecoder().decode("AXEEVAlzkqo+Fe/uM19xW1t3DnuIpvUpAz5hOZOIFeUfdp1QAK0nK62rQj2TI+xZAf0GmkuojHLQ7f/zriQO5AyAAQAHCsyToY8eD6JJtvYne1BW0DeQuASlERJoCDNd8yywEjTx4TJikpKjqpZeXOBj+DbfbrYXnvOKF0R26eu4wgflXdHiT1rbiEqoqhsKM+ttmJZNnayblXbJ6lvGvvdtUseTfwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwZGb+UhFzL/7K26csOb57yM5bvF9xJrLEObOkAAAAAEedVb8jHAbu50xW7OaBUH/bGy3qP0jlECsc2iVrwTjwbd9uHXZaGT2cvhRs7reawctIXtX1s3kTqM9YV+/wCpjJclj04kifG7PRApFI4NgwtaE5na/xCEBI572Nvp+FmbpAWzaYYXm57lIfkX2aVCtN8wBrez/pWNiNsRmD6GD7Q/+if11/ZKdMCbHylYed5LCas238ndUUsyGqezjOXoJPOq7tXFmriCfnyNQ6vH8nUcS1Is/1tt8y7eXK2XyHgHBAAFAsFnAQAEAAkDpXIJAAAAAAAHBgABAA0DBgEBAwIAAQwCAAAAAGXNHQAAAAAGAQEBEQUbBgABAgUIBQkFDwYKDgoMCwoKCgoKCgoKAQIAI+UXy5d6460qAQAAAAdkAAEAZc0dAAAAABtHYmcGAAAAvAIABgMBAAABCQF3G0n2vTsDayxLcoRWWkzA4zkRSASDNPh0C5VsBXIxYAM5PD0DEAQD");
    assertArrayEquals(txData, tx.data());

    final var meta = tx.meta();
    assertEquals(74477, meta.computeUnitsConsumed());
    assertNull(meta.error());
    assertEquals(62024, meta.fee());

    final var rewards = meta.rewards();
    assertTrue(rewards.isEmpty());

    final var innerInstructions = meta.innerInstructions();
    assertEquals(2, innerInstructions.size());

    var innerIx = innerInstructions.getFirst();
    assertEquals(2, innerIx.index());
    var instructions = innerIx.instructions();
    assertEquals(4, instructions.size());
    var ix = instructions.getFirst();
    assertArrayEquals(new int[]{13}, ix.accountIndices());
    assertEquals("84eT", ix.b58Data());
    assertEquals(6, ix.programIdIndex());
    assertEquals(2, ix.stackHeight());

    ix = instructions.getLast();
    assertArrayEquals(new int[]{1, 13}, ix.accountIndices());
    assertEquals("6auHQdCNgSFbow1FwY7q5okS6HR93Lmaw5gGnfhCzMfcU", ix.b58Data());
    assertEquals(6, ix.programIdIndex());
    assertEquals(2, ix.stackHeight());

    innerIx = innerInstructions.getLast();
    assertEquals(5, innerIx.index());
    instructions = innerIx.instructions();
    assertEquals(4, instructions.size());
    ix = instructions.getFirst();
    assertArrayEquals(new int[]{6, 10, 14, 10, 12, 11, 10, 10, 10, 10, 10, 10, 10, 10, 1, 2, 0}, ix.accountIndices());
    assertEquals("5uZJEbuZKBdvJJ4oxtz2buR", ix.b58Data());
    assertEquals(15, ix.programIdIndex());
    assertEquals(2, ix.stackHeight());

    ix = instructions.getLast();
    assertArrayEquals(new int[]{9}, ix.accountIndices());
    assertEquals(
        "QMqFu4fYGGeUEysFnenhAvR83g86EDDNxzUskfkWKYCBPWe1hqgD6jgKAXr6aYoEQaxoqYMTvWgPVk2AHWGHjdbNiNtoaPfZA4znu6cRUSWSeJF9uSjAY6beqYCHcgxCSLGoPDABThY1WoDL8UrGdxPMHsMAb4ijDY5RmSzh2FDodqZ",
        ix.b58Data()
    );
    assertEquals(5, ix.programIdIndex());
    assertEquals(2, ix.stackHeight());

    final var loadedAddresses = meta.loadedAddresses();
    final var readOnly = loadedAddresses.readonly();
    assertEquals(3, readOnly.size());
    assertEquals(PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112"), readOnly.getFirst());
    assertEquals(PublicKey.fromBase58Encoded("675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8"), readOnly.getLast());

    final var writable = loadedAddresses.writable();
    assertEquals(3, writable.size());
    assertEquals(PublicKey.fromBase58Encoded("7bbUVnKL7McGS1tH5qfNNWCANWNXjXELF6BLqVe4Batt"), writable.getFirst());
    assertEquals(PublicKey.fromBase58Encoded("GSUxFEYjsh9Xxa8A9k689YwG4t9NUNQvN4YinSiroXmp"), writable.getLast());

    final var logMessages = meta.logMessages();
    assertEquals(55, logMessages.size());
    assertEquals("Program ComputeBudget111111111111111111111111111111 invoke [1]", logMessages.getFirst());
    assertEquals("Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA success", logMessages.getLast());

    assertArrayEquals(
        new long[]{5060481242L, 0L, 2039280L, 1L, 1L, 1141440L, 934087680L, 731913600L, 1461600L, 0L, 6124800L, 2039280L, 677378827840L, 725135288856L, 13078066259L, 1141440},
        meta.preBalances().stream().mapToLong(Long::longValue).toArray()
    );
    assertArrayEquals(
        new long[]{4560419218L, 0L, 2039280L, 1L, 1L, 1141440L, 934087680L, 731913600L, 1461600L, 0L, 6124800L, 2039280L, 677878827840L, 725135288856L, 13078066259L, 1141440},
        meta.postBalances().stream().mapToLong(Long::longValue).toArray()
    );

    final var preTokenBalances = meta.preTokenBalances();
    assertEquals(3, preTokenBalances.size());

    var tokenBalance = preTokenBalances.getFirst();
    assertEquals(2, tokenBalance.accountIndex());
    assertEquals(PublicKey.fromBase58Encoded("BUZFom1YPnAZVYSccbpddBmFYmHcS4Xtc3JQG5cFpump"), tokenBalance.mint());
    assertEquals(PublicKey.fromBase58Encoded("Emajgzqt9QKyfXARSxdkKmzhnXZDw2KENm6wB7XB4XBW"), tokenBalance.owner());
    assertEquals(PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"), tokenBalance.programId());
    assertEquals(new BigInteger("0"), tokenBalance.amount());
    assertEquals(6, tokenBalance.decimals());

    tokenBalance = preTokenBalances.getLast();
    assertEquals(12, tokenBalance.accountIndex());
    assertEquals(PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112"), tokenBalance.mint());
    assertEquals(PublicKey.fromBase58Encoded("5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1"), tokenBalance.owner());
    assertEquals(PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"), tokenBalance.programId());
    assertEquals(new BigInteger("677376788560"), tokenBalance.amount());
    assertEquals(9, tokenBalance.decimals());

    final var postTokenBalances = meta.postTokenBalances();
    assertEquals(3, postTokenBalances.size());

    tokenBalance = postTokenBalances.getFirst();
    assertEquals(2, tokenBalance.accountIndex());
    assertEquals(PublicKey.fromBase58Encoded("BUZFom1YPnAZVYSccbpddBmFYmHcS4Xtc3JQG5cFpump"), tokenBalance.mint());
    assertEquals(PublicKey.fromBase58Encoded("Emajgzqt9QKyfXARSxdkKmzhnXZDw2KENm6wB7XB4XBW"), tokenBalance.owner());
    assertEquals(PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"), tokenBalance.programId());
    assertEquals(new BigInteger("27504297755"), tokenBalance.amount());
    assertEquals(6, tokenBalance.decimals());

    tokenBalance = postTokenBalances.getLast();
    assertEquals(12, tokenBalance.accountIndex());
    assertEquals(PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112"), tokenBalance.mint());
    assertEquals(PublicKey.fromBase58Encoded("5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1"), tokenBalance.owner());
    assertEquals(PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"), tokenBalance.programId());
    assertEquals(new BigInteger("677876788560"), tokenBalance.amount());
    assertEquals(9, tokenBalance.decimals());
  }
}
