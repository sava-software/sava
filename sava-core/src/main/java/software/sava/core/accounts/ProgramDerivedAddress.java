package software.sava.core.accounts;

import java.util.List;

public interface ProgramDerivedAddress {

  static ProgramDerivedAddress createPDA(final PublicKey publicKey, final int nonce) {
    return new PDARecord(null, publicKey, nonce);
  }

  static ProgramDerivedAddress createPDA(final List<byte[]> seeds,
                                         final PublicKey publicKey,
                                         final int nonce) {
    return new PDARecord(seeds, publicKey, nonce);
  }

  List<byte[]> seeds();

  PublicKey publicKey();

  int nonce();
}
