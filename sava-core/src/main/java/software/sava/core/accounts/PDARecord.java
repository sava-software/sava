package software.sava.core.accounts;

import java.util.List;

record PDARecord(List<byte[]> seeds, PublicKey publicKey, int nonce) implements ProgramDerivedAddress {

}
