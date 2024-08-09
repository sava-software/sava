package software.sava.core.crypto.bip32.wallet;

public record HdAddress(HdKey privateKey, HdKey publicKey, String path) {

}
