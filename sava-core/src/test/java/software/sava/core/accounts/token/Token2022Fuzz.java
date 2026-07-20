package software.sava.core.accounts.token;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.extensions.TokenExtension;

import java.util.Set;

/// Jazzer entry point for Token-2022 TLV parsing, untrusted account data fetched over RPC.
/// The TLV walker in [Token2022#parseExtensions] is the shared surface: a corrupt u16
/// length shifts every subsequent parse, and 29 extension read/write pairs hang off the
/// type field. Same malformed-input contract as the transaction harness: "garbage in ->
/// RuntimeException out". Jazzer flags hangs, memory exhaustion, and any
/// non-[RuntimeException] throwable; this harness adds the round-trip invariants that must
/// hold whenever a parse fully succeeds — re-serializing what was parsed must consume
/// exactly [software.sava.core.serial.Serializable#l()] bytes and parse back equal.
///
/// Seeded from real mainnet accounts under src/test/resources/fuzz/token2022 (the PYUSD
/// mint with 8 extensions including TokenMetadata, and a confidential token account), the
/// same fixtures ParseExtensionsTests pins.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :sava-core:fuzzToken2022 [-PmaxFuzzTime=<seconds>]`.
public final class Token2022Fuzz {

  private static final PublicKey ADDRESS = PublicKey.createPubKey(new byte[PublicKey.PUBLIC_KEY_LENGTH]);

  public static void fuzzerTestOneInput(final byte[] data) {
    fuzzExtensions(data);
    fuzzMint(data);
    fuzzTokenAccount(data);
  }

  private static void fuzzExtensions(final byte[] data) {
    final Set<TokenExtension> extensions;
    try {
      extensions = Token2022.parseExtensions(data, 0);
    } catch (final RuntimeException tolerated) {
      return;
    }
    final var reparsed = Token2022.parseExtensions(serialize(extensions), 0);
    if (!extensions.equals(reparsed)) {
      throw new AssertionError("extensions did not survive a serialization round trip");
    }
  }

  private static byte[] serialize(final Set<TokenExtension> extensions) {
    int l = 0;
    for (final var extension : extensions) {
      l += Integer.BYTES + extension.l();
    }
    final byte[] out = new byte[l];
    int i = 0;
    for (final var extension : extensions) {
      i += TokenExtension.write(extension, out, i);
    }
    if (i != l) {
      throw new AssertionError("extensions wrote " + i + " bytes but l() promised " + l);
    }
    return out;
  }

  private static void fuzzMint(final byte[] data) {
    final Token2022 token2022;
    try {
      token2022 = Token2022.read(ADDRESS, data);
    } catch (final RuntimeException tolerated) {
      return;
    }
    if (token2022 == null) {
      if (data.length != 0) {
        throw new AssertionError("read returned null for non-empty data");
      }
      return;
    }
    // parseAccountType returns null for ordinals released after AccountType was last
    // synced; such a value cannot be re-serialized, which is the write side's contract
    if (token2022.accountType() == null) {
      return;
    }
    final byte[] out = new byte[token2022.l()];
    final int written = token2022.write(out, 0);
    if (written != out.length) {
      throw new AssertionError("write consumed " + written + " bytes but l() promised " + out.length);
    }
    if (!token2022.equals(Token2022.read(ADDRESS, out))) {
      throw new AssertionError("Token2022 did not survive a serialization round trip");
    }
  }

  private static void fuzzTokenAccount(final byte[] data) {
    final Token2022Account account;
    try {
      account = Token2022Account.read(ADDRESS, data);
    } catch (final RuntimeException tolerated) {
      return;
    }
    if (account == null) {
      if (data.length != 0) {
        throw new AssertionError("read returned null for non-empty data");
      }
      return;
    }
    if (account.type() == null) {
      return;
    }
    final byte[] out = new byte[account.l()];
    final int written = account.write(out, 0);
    if (written != out.length) {
      throw new AssertionError("write consumed " + written + " bytes but l() promised " + out.length);
    }
    if (!account.equals(Token2022Account.read(ADDRESS, out))) {
      throw new AssertionError("Token2022Account did not survive a serialization round trip");
    }
  }

  private Token2022Fuzz() {
  }
}
