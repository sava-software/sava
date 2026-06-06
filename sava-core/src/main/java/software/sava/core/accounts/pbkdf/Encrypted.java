package software.sava.core.accounts.pbkdf;

public record Encrypted(KeyDerivation keyDerivation, byte[] salt, byte[] iv, byte[] cipherText) {
}
