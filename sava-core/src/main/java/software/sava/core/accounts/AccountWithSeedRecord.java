package software.sava.core.accounts;

record AccountWithSeedRecord(PublicKey baseKey,
                             PublicKey publicKey,
                             byte[] asciiSeed,
                             PublicKey program) implements AccountWithSeed {

}
