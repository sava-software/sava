package test.software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.*;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.request.Commitment.FINALIZED;

final class ParseTransactionErrorTests {

  @Test
  void incorrectProgramIdInstructionError() {
    final var json = """
        {
          "jsonrpc":"2.0",
          "result":{
            "blockTime":1732555754,
            "meta":{
              "computeUnitsConsumed":6330,
              "err":{
                "InstructionError":[
                  0,
                  "IncorrectProgramId"
                ]
              },
              "fee":5000,
              "innerInstructions":[
                {
                  "index":0,
                  "instructions":[
                    {
                      "accounts":[
                        5
                      ],
                      "data":"84eT",
                      "programIdIndex":6,
                      "stackHeight":2
                    }
                  ]
                }
              ],
              "loadedAddresses":{
                "readonly":[
                 \s
                ],
                "writable":[
                 \s
                ]
              },
              "logMessages":[
                "Program ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL invoke [1]",
                "Program log: Create",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA invoke [2]",
                "Program log: Instruction: GetAccountDataSize",
                "Program log: Error: IncorrectProgramId",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA consumed 884 of 194554 compute units",
                "Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA failed: incorrect program id for instruction",
                "Program ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL consumed 6330 of 200000 compute units",
                "Program ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL failed: incorrect program id for instruction"
              ],
              "postBalances":[
                29627282692,
                0,
                1,
                9440893353795,
                731913600,
                3946320,
                934087680
              ],
              "postTokenBalances":[
               \s
              ],
              "preBalances":[
                29627287692,
                0,
                1,
                9440893353795,
                731913600,
                3946320,
                934087680
              ],
              "preTokenBalances":[
               \s
              ],
              "rewards":[
               \s
              ],
              "status":{
                "Err":{
                  "InstructionError":[
                    0,
                    "IncorrectProgramId"
                  ]
                }
              }
            },
            "slot":303576101,
            "transaction":[
              "Ac6jx8WK8aGSfIXYF+EpbqRtEvzJO1j9poIJP68sz3eBvYFi9u0oXALBc2Kd0Ur6mBrfUYu57KUUrlbr8odv8gkBAAUHVZ+QnjwqeX2SA8SNQes1qMAE7kSE3CCGaAxcgrksS1hhB5bWlv49vgdZPb0EEATxSJXDHPdSfVbWTcnpeZlP8gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEBRhfkpLukKXqgF2m5VSaItwyBCbPj304vYI07SntiiMlyWPTiSJ8bs9ECkUjg2DC1oTmdr/EIQEjnvY2+n4WQzlz2b6rFqWF3JYKSU4VrvGb/PJ0n4n2UG4AruhTlRpBt324ddloZPZy+FGzut5rBy0he1fWzeROoz1hX7/AKlQLfII0mSALt0RA7pfF5XwmufH3SKqHpivWp6h/KAu6wEEBgABAwUCBgA=",
              "base64"
            ],
            "version":"legacy"
          },
          "id":1732556605319
        }""";

    final var ji = JsonIterator.parse(json);
    ji.skipUntil("result");
    final var tx = Tx.parse(ji);

    final var meta = tx.meta();
    final var error = meta.error();

    assertInstanceOf(TransactionError.InstructionError.class, error);
    final var instructionError = (TransactionError.InstructionError) error;
    assertEquals(0, instructionError.ixIndex());
    final var ixError = instructionError.ixError();
    assertInstanceOf(IxError.IncorrectProgramId.class, ixError);
  }

  @Test
  void customInstructionError() {
    final var json = """
        {
          "jsonrpc":"2.0",
          "result":{
            "blockTime":1732554556,
            "meta":{
              "computeUnitsConsumed":17108,
              "err":{
                "InstructionError":[
                  2,
                  {
                    "Custom":30
                  }
                ]
              },
              "fee":31566,
              "innerInstructions":[
               \s
              ],
              "loadedAddresses":{
                "readonly":[
                 \s
                ],
                "writable":[
                 \s
                ]
              },
              "logMessages":[
                "Program ComputeBudget111111111111111111111111111111 invoke [1]",
                "Program ComputeBudget111111111111111111111111111111 success",
                "Program ComputeBudget111111111111111111111111111111 invoke [1]",
                "Program ComputeBudget111111111111111111111111111111 success",
                "Program 675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8 invoke [1]",
                "Program log: ray_log: A8vS5+1GCAMCQA0DAAAAAAACAAAAAAAAAHmdZPvDl4AN6YWnMwBbGS8IAA4AAAAAAHiSAAAAAAAA",
                "Program log: Error: exceeds desired slippage limit",
                "Program 675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8 consumed 16808 of 38200 compute units",
                "Program 675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8 failed: custom program error: 0x1e"
              ],
              "postBalances":[
                58834408633,
                6124800,
                23357760,
                4677120,
                2039280,
                2956792,
                3591360,
                101977920,
                101977920,
                79594560,
                2039280,
                2039280,
                2039280,
                73874317256,
                1,
                1141440,
                934087680,
                13411301697,
                1141440,
                0,
                1141440
              ],
              "postTokenBalances":[
                {
                  "accountIndex":4,
                  "mint":"J2mdZe9vzN8ayJnAbif1Trv7zPyVguksxqnWetNXpump",
                  "owner":"5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"3393843850625123817",
                    "decimals":6,
                    "uiAmount":3393843850625.124,
                    "uiAmountString":"3393843850625.123817"
                  }
                },
                {
                  "accountIndex":5,
                  "mint":"So11111111111111111111111111111111111111112",
                  "owner":"5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"917512",
                    "decimals":9,
                    "uiAmount":0.000917512,
                    "uiAmountString":"0.000917512"
                  }
                },
                {
                  "accountIndex":10,
                  "mint":"J2mdZe9vzN8ayJnAbif1Trv7zPyVguksxqnWetNXpump",
                  "owner":"BhU2QYV2EcHSC5X4y4oeh9rCQD8Uv9K765Yzdkd1pEML",
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
                  "mint":"So11111111111111111111111111111111111111112",
                  "owner":"BhU2QYV2EcHSC5X4y4oeh9rCQD8Uv9K765Yzdkd1pEML",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"0",
                    "decimals":9,
                    "uiAmount":null,
                    "uiAmountString":"0"
                  }
                },
                {
                  "accountIndex":12,
                  "mint":"J2mdZe9vzN8ayJnAbif1Trv7zPyVguksxqnWetNXpump",
                  "owner":"66ZC9U8y1uYaAxt4WFYVW11YZeZohvi8ev6wBHsAxykh",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"972944387504119161",
                    "decimals":6,
                    "uiAmount":972944387504.1191,
                    "uiAmountString":"972944387504.119161"
                  }
                },
                {
                  "accountIndex":13,
                  "mint":"So11111111111111111111111111111111111111112",
                  "owner":"66ZC9U8y1uYaAxt4WFYVW11YZeZohvi8ev6wBHsAxykh",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"73872277776",
                    "decimals":9,
                    "uiAmount":73.872277776,
                    "uiAmountString":"73.872277776"
                  }
                }
              ],
              "preBalances":[
                58834440199,
                6124800,
                23357760,
                4677120,
                2039280,
                2956792,
                3591360,
                101977920,
                101977920,
                79594560,
                2039280,
                2039280,
                2039280,
                73874317256,
                1,
                1141440,
                934087680,
                13411301697,
                1141440,
                0,
                1141440
              ],
              "preTokenBalances":[
                {
                  "accountIndex":4,
                  "mint":"J2mdZe9vzN8ayJnAbif1Trv7zPyVguksxqnWetNXpump",
                  "owner":"5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"3393843850625123817",
                    "decimals":6,
                    "uiAmount":3393843850625.124,
                    "uiAmountString":"3393843850625.123817"
                  }
                },
                {
                  "accountIndex":5,
                  "mint":"So11111111111111111111111111111111111111112",
                  "owner":"5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"917512",
                    "decimals":9,
                    "uiAmount":0.000917512,
                    "uiAmountString":"0.000917512"
                  }
                },
                {
                  "accountIndex":10,
                  "mint":"J2mdZe9vzN8ayJnAbif1Trv7zPyVguksxqnWetNXpump",
                  "owner":"BhU2QYV2EcHSC5X4y4oeh9rCQD8Uv9K765Yzdkd1pEML",
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
                  "mint":"So11111111111111111111111111111111111111112",
                  "owner":"BhU2QYV2EcHSC5X4y4oeh9rCQD8Uv9K765Yzdkd1pEML",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"0",
                    "decimals":9,
                    "uiAmount":null,
                    "uiAmountString":"0"
                  }
                },
                {
                  "accountIndex":12,
                  "mint":"J2mdZe9vzN8ayJnAbif1Trv7zPyVguksxqnWetNXpump",
                  "owner":"66ZC9U8y1uYaAxt4WFYVW11YZeZohvi8ev6wBHsAxykh",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"972944387504119161",
                    "decimals":6,
                    "uiAmount":972944387504.1191,
                    "uiAmountString":"972944387504.119161"
                  }
                },
                {
                  "accountIndex":13,
                  "mint":"So11111111111111111111111111111111111111112",
                  "owner":"66ZC9U8y1uYaAxt4WFYVW11YZeZohvi8ev6wBHsAxykh",
                  "programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                  "uiTokenAmount":{
                    "amount":"73872277776",
                    "decimals":9,
                    "uiAmount":73.872277776,
                    "uiAmountString":"73.872277776"
                  }
                }
              ],
              "rewards":[
               \s
              ],
              "status":{
                "Err":{
                  "InstructionError":[
                    2,
                    {
                      "Custom":30
                    }
                  ]
                }
              }
            },
            "slot":303573396,
            "transaction":[
              "AaFTNh6FdP2Qyyh/OkL2QGaAUvbu7zb+ckpuk1IuLfoqkJfzkCT1RM8Yl436hVz8VOYBv9NK+ezw88UvlSTDjw+AAQAHFUu2vQ0wRJZmBuTTGmRcqiQUN/S6eCVx79TozVINbaguT5zAE0CW4fom9fDwJazL8lvJDRmQkTa98YWUo+IsyDXzcOFqMcLCTYcwKd52fj4NtChzUGPnXmFSkgzE4feDvXn9/fP/LoNhXqpKF3rwMBiJzjmBOByblNO48nwIB5gsTSDPbNTBkbCFT8LaJ+O7Gs2cyc9gc60lhYPJuSEWc9m3PEl3FCfSsvgAa+CqvBUnJuIhANNtz3aI6Wrmoom6EEI5IJg2j02INcJKDRDt6IUYuX44M5P34v8p9mcEecs0SeGmbZdwnHKnBLUEyFHMXJkh2TwAm32B5TeHIi2uqRFIJYfDlRYi2lNnQxMi/tUTAbFtpaejCxtqTKjQhJvy+3Z2aXblfdAF79FP9MllwSMz7i/7iDHNJxp+TBq2dzTjWZZGzXEPTGug55XoJhGN4B41tL73jMmd4QLcI+TaRqOEc242GW9h+hpWjA8cuee5uk/ksbBJkQddjLcraO5LmZggmXFEfhqXjpFL7Uo8F8EwZfAxa14c3bFOAjNDqEK+ZOeqartugdgacDm6witX2zCRq/eF2nuEvJAmHqj/eoEDBkZv5SEXMv/srbpyw5vnvIzlu8X3EmssQ5s6QAAAAEvZScQ2AsM/IHeQ7RajUkyhuZdc8SGiqQz/7H34torNBt324ddloZPZy+FGzut5rBy0he1fWzeROoz1hX7/AKlBV7BYDzHF/ORKYlgtvPnXjudZQ6CEo5OzUDaNIomTCA0HUagoLaYTBf4pnDe5mOWEcdsRNQNzEPi+EEWmCvbunvKodMe8tG88ryurjSeqXlf2GhV3mlojHubjacWCpQfzoQQP2CW/hYwd0lyG8/Zlm8M9Cro+eJpipSyuyZn3gff3MbJnvdtyH6xDxB2kQRzptgGR4bT6Xfq7Ocw/tjK+BA4ABQJklgAADgAJA2CHCgAAAAAADxIQARECAwQFEgYHCAkKCxMMDQARCcvS5+1GCAMCQA0DAAAAAAAUAB9Qb3dlcmVkIGJ5IGJsb1hyb3V0ZSBUcmFkZXIgQXBpAA==",
              "base64"
            ],
            "version":0
          },
          "id":1732554750701
        }""";

    final var ji = JsonIterator.parse(json);
    ji.skipUntil("result");
    final var tx = Tx.parse(ji);

    final var meta = tx.meta();
    final var error = meta.error();

    assertInstanceOf(TransactionError.InstructionError.class, error);
    final var instructionError = (TransactionError.InstructionError) error;
    assertEquals(2, instructionError.ixIndex());
    final var ixError = instructionError.ixError();
    assertInstanceOf(IxError.Custom.class, ixError);
    final var customError = (IxError.Custom) ixError;
    assertEquals(30, customError.error());
  }

  @Test
  void insufficientFundsForRent() {
    final var json = """
        {
          "jsonrpc":"2.0",
          "result":[
            {
              "blockTime":1719899984,
              "confirmationStatus":"finalized",
              "err":null,
              "memo":null,
              "signature":"2EhWejyZMgKKiWSXXQYe9LHCkTicoG3uDeBU4FkZQPFtWPiFUPWNNbJfMpWnmapRgrUEDRN8TpAcUj7J2SUnLNMc",
              "slot":275200832
            },
            {
              "blockTime":1719891284,
              "confirmationStatus":"finalized",
              "err":{
                "InsufficientFundsForRent":{
                  "account_index":1
                }
              },
              "memo":null,
              "signature":"3HMFHzjwBoqmMmwbcUFz42ZNXEcRfhk2GPbAAV6Lw2FQ4SwKWhNN4BBNuihb63c5HxNYwyStRXDjCzyNXmjmFs4U",
              "slot":275181771
            },
            {
              "blockTime":1719891252,
              "confirmationStatus":"finalized",
              "err":{
                "InsufficientFundsForRent":{
                  "account_index":1
                }
              },
              "memo":null,
              "signature":"3y9AaVZSVnEGZVDX7k39ZZwXhV9Ph7mBYJvmj39Ddzqz2b5QsTaHZ9f5Fmq7jNLM86pq1AxxzV2F3FbzC1KW47KD",
              "slot":275181700
            },
            {
              "blockTime":1719891192,
              "confirmationStatus":"finalized",
              "err":{
                "InsufficientFundsForRent":{
                  "account_index":1
                }
              },
              "memo":null,
              "signature":"4SaVxiFtwLt2G96ZMtKZCuNKDA99FZkHBAJHbNF8GudqF6uhKzXVokL5tdQP5PHnP13xWu1u2sNNXs2B5o5QUKDW",
              "slot":275181572
            },
            {
              "blockTime":1719891129,
              "confirmationStatus":"finalized",
              "err":{
                "InsufficientFundsForRent":{
                  "account_index":1
                }
              },
              "memo":null,
              "signature":"ZpJetL7BWHak7gZcF7SuiguymLMin1mdhmduxMUXP4qKEFsZf31UpSy4Kr3wFUuPsAii8JdYTReqZwbFdoWkHit",
              "slot":275181444
            },
            {
              "blockTime":1719891085,
              "confirmationStatus":"finalized",
              "err":{
                "InsufficientFundsForRent":{
                  "account_index":1
                }
              },
              "memo":null,
              "signature":"31wAdYrVhyzNSzQ42tdBES1znVrNt58dYBLSVdX6bWFRu5FGoTKrBDHrURjJcHnXns33YhJvppmx6vBAT8dyk78i",
              "slot":275181348
            },
            {
              "blockTime":1719887793,
              "confirmationStatus":"finalized",
              "err":null,
              "memo":null,
              "signature":"2HaJT7GrfPDqr2zKnyWoeQnpcozSmjbAaBkosQ7xbUPRqXZkpRxnpJ8dcbw8NYFucrWMSykWj2XqkxyEZ7k6EDxb",
              "slot":275174232
            },
            {
              "blockTime":1719384882,
              "confirmationStatus":"finalized",
              "err":null,
              "memo":null,
              "signature":"2pq6s4nJ5yDjdosTjdmFiuyRKCyPKyK1y2CmBJtbqyquVvo1ggHkFvqLKeSDzpwKhRUBjqkqN8Tp9rd4RJaRq76v",
              "slot":274069760
            },
            {
              "blockTime":1719384469,
              "confirmationStatus":"finalized",
              "err":{
                "InsufficientFundsForRent":{
                  "account_index":0
                }
              },
              "memo":null,
              "signature":"2RDTnfYo5HSPYdkD533pGR14bK6LLuBfoX5oCfCyn7nNMaJMR2MuzCJ8hsHQXyW5LrF4CkLWdYVdzcjLUd7YNWGR",
              "slot":274068836
            },
            {
              "blockTime":1717646362,
              "confirmationStatus":"finalized",
              "err":{
                "InsufficientFundsForRent":{
                  "account_index":0
                }
              },
              "memo":null,
              "signature":"4dsb5o6Ea9Mj4xNuQqb6DZxZBfezn9xfrxfX4KXSDfMKvVZHS2LCTx9weNzDXJcHkgzNdjHm5PqHDHiRPEuwgc9K",
              "slot":270151056
            }
          ],
          "id":1732552665402
        }""";

    final var ji = JsonIterator.parse(json);
    ji.skipUntil("result");
    final var txSignatures = TxSig.parseSignatures(ji);
    assertEquals(10, txSignatures.size());

    var txSig = txSignatures.getFirst();
    assertEquals(275200832L, txSig.slot());
    assertTrue(txSig.blockTime().isPresent());
    assertEquals(1719899984L, txSig.blockTime().getAsLong());
    assertEquals(FINALIZED, txSig.confirmationStatus());
    assertNull(txSig.transactionError());
    assertNull(txSig.memo());
    assertEquals("2EhWejyZMgKKiWSXXQYe9LHCkTicoG3uDeBU4FkZQPFtWPiFUPWNNbJfMpWnmapRgrUEDRN8TpAcUj7J2SUnLNMc", txSig.signature());

    txSig = txSignatures.get(5);
    assertEquals(275181348L, txSig.slot());
    assertTrue(txSig.blockTime().isPresent());
    assertEquals(1719891085L, txSig.blockTime().getAsLong());
    assertEquals(FINALIZED, txSig.confirmationStatus());
    assertEquals(new TransactionError.InsufficientFundsForRent(1), txSig.transactionError());
    assertNull(txSig.memo());
    assertEquals("31wAdYrVhyzNSzQ42tdBES1znVrNt58dYBLSVdX6bWFRu5FGoTKrBDHrURjJcHnXns33YhJvppmx6vBAT8dyk78i", txSig.signature());

    txSig = txSignatures.getLast();
    assertEquals(270151056L, txSig.slot());
    assertTrue(txSig.blockTime().isPresent());
    assertEquals(1717646362L, txSig.blockTime().getAsLong());
    assertEquals(FINALIZED, txSig.confirmationStatus());
    assertEquals(new TransactionError.InsufficientFundsForRent(0), txSig.transactionError());
    assertNull(txSig.memo());
    assertEquals("4dsb5o6Ea9Mj4xNuQqb6DZxZBfezn9xfrxfX4KXSDfMKvVZHS2LCTx9weNzDXJcHkgzNdjHm5PqHDHiRPEuwgc9K", txSig.signature());
  }

  @Test
  void sigStatusInstructionError() {
    final var json = """
        {
          "jsonrpc":"2.0",
          "result":{
            "context":{
              "apiVersion":"2.0.15",
              "slot":303578453
            },
            "value":[
              {
                "confirmationStatus":"finalized",
                "confirmations":null,
                "err":{
                  "InstructionError":[
                    2,
                    {
                      "Custom":3012
                    }
                  ]
                },
                "slot":303578335,
                "status":{
                  "Err":{
                    "InstructionError":[
                      2,
                      {
                        "Custom":3012
                      }
                    ]
                  }
                }
              }
            ]
          },
          "id":1732556781437
        }""";

    final var error = validateSigStatus(json);
    assertInstanceOf(TransactionError.InstructionError.class, error);
    final var instructionError = (TransactionError.InstructionError) error;
    assertEquals(2, instructionError.ixIndex());
    final var ixError = instructionError.ixError();
    assertInstanceOf(IxError.Custom.class, ixError);
    final var customError = (IxError.Custom) ixError;
    assertEquals(3012, customError.error());
  }

  @Test
  void sigStatusDuplicateInstruction() {
    final var json = """
        {
          "jsonrpc":"2.0",
          "result":{
            "context":{
              "apiVersion":"2.0.15",
              "slot":303578453
            },
            "value":[
              {
                "confirmationStatus":"finalized",
                "confirmations":null,
                "err":{
                  "DuplicateInstruction": 1
                },
                "slot":303578335,
                "status":{
                  "Err":{
                    "DuplicateInstruction": 1
                  }
                }
              }
            ]
          },
          "id":1732556781437
        }""";

    final var error = validateSigStatus(json);
    assertInstanceOf(TransactionError.DuplicateInstruction.class, error);
    final var instructionError = (TransactionError.DuplicateInstruction) error;
    assertEquals(1, instructionError.index());
  }

  @Test
  void sigStatusUnknownError() {
    final var json = """
        {
          "jsonrpc":"2.0",
          "result":{
            "context":{
              "apiVersion":"2.0.15",
              "slot":303578453
            },
            "value":[
              {
                "confirmationStatus":"finalized",
                "confirmations":null,
                "err":{
                  "NewUnknownError":[
                    2,
                    {
                      "Custom":3012
                    }
                  ]
                },
                "slot":303578335,
                "status":{
                  "Err":{
                    "NewUnknownError":[
                      2,
                      {
                        "Custom":3012
                      }
                    ]
                  }
                }
              }
            ]
          },
          "id":1732556781437
        }""";

    final var error = validateSigStatus(json);
    assertInstanceOf(TransactionError.Unknown.class, error);
    final var instructionError = (TransactionError.Unknown) error;
    assertEquals("NewUnknownError", instructionError.type());
  }

  private TransactionError validateSigStatus(final String json) {
    final var sig = "4CMrEPktKxqHeGiLVbFHhN2QdaJX9ovTH1NBiSzktxfwWgne5cMKmS2o1S8drF2EpVgnNdgZVzbQBYqXcSyrCBzm";

    final var ji = JsonIterator.parse(json);
    ji.skipUntil("result");
    ji.skipUntil("context");
    final var context = Context.parse(ji);

    ji.skipUntil("value");
    final var statusMap = TxStatus.parse(List.of(sig), ji, context);

    assertEquals(1, statusMap.size());
    final var status = statusMap.get(sig);
    assertNotNull(status);
    assertEquals(FINALIZED, status.confirmationStatus());
    assertTrue(status.confirmations().isEmpty());
    assertEquals(303578335L, status.slot());

    final var error = status.error();
    assertNotNull(error);
    return error;
  }
}
