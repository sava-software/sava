package software.sava.core.accounts.sysvar;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.accounts.token.Token2022;
import software.sava.core.accounts.token.Token2022Account;
import software.sava.core.accounts.token.extensions.AccountType;
import software.sava.core.accounts.token.extensions.AccountTokenExtension;
import software.sava.core.accounts.token.extensions.CpiGuard;
import software.sava.core.accounts.token.extensions.ExtensionType;
import software.sava.core.accounts.token.extensions.ImmutableOwner;
import software.sava.core.accounts.token.extensions.InterestBearingConfig;
import software.sava.core.accounts.token.extensions.MemoTransfer;
import software.sava.core.accounts.token.extensions.MintCloseAuthority;
import software.sava.core.accounts.token.extensions.MintTokenExtension;
import software.sava.core.accounts.token.extensions.PausableAccount;
import software.sava.core.accounts.token.extensions.PausableConfig;
import software.sava.core.accounts.token.extensions.PermanentDelegate;
import software.sava.core.accounts.token.extensions.PermissionedBurnConfig;
import software.sava.core.accounts.token.extensions.ScaledUiAmountConfig;
import software.sava.core.accounts.token.extensions.TokenExtension;
import software.sava.core.accounts.token.extensions.TokenMetadata;
import software.sava.core.accounts.token.extensions.TransferFeeAmount;
import software.sava.core.accounts.token.extensions.TransferHook;
import software.sava.core.accounts.token.extensions.TransferHookAccount;
import software.sava.core.encoding.ByteUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// First-party defensive conformance for Sava's client-side Token-2022 and sysvar
/// parsers. The committed fixtures were generated through pinned upstream Rust APIs;
/// ordinary Java tests require neither Rust nor network access.
@SuppressWarnings("removal")
public final class SolanaUpstreamLayoutConformanceTests {

  private static final String EXTENSIONS_RESOURCE = "/upstream/solana-token2022-extensions.tsv";
  private static final String TOKEN_BOOLS_RESOURCE = "/upstream/solana-token2022-bools.tsv";
  private static final String TOKEN_ACCOUNTS_RESOURCE = "/upstream/solana-token2022-accounts.tsv";
  private static final String METADATA_RESOURCE = "/upstream/solana-token2022-metadata.tsv";
  private static final String SYSVARS_RESOURCE = "/upstream/solana-sysvars.tsv";
  private static final String EXTENSION_COLUMNS =
      "ordinal\tname\taccount_type\tlength_kind\tvalue_length\tvalue_hex\tvalue_sha256\t"
          + "rust_exact_accepts\trust_short_accepts\trust_long_accepts";
  private static final String METADATA_COLUMNS =
      "id\tname_unit_utf8_hex\tname_repetitions\tsymbol_utf8_hex\turi_utf8_hex\t"
          + "extra_key_utf8_hex\textra_value_utf8_hex\tpacked_length\tpacked_sha256\t"
          + "value_hex\ttlv_u16_fits\trust_round_trip";
  private static final String TOKEN_BOOL_COLUMNS =
      "ordinal\tname\tfield\tbool_offset\tvalue_hex\trust_value";
  private static final String TOKEN_ACCOUNT_COLUMNS =
      "id\tkind\taddress_fill\tdata_length\tdata_sha256\tdata_hex";
  private static final String EPOCH_COLUMNS =
      "id\tdistribution_starting_block_height\tnum_partitions\tparent_blockhash_hex\t"
          + "total_points\ttotal_rewards\tdistributed_rewards\tactive\twire_hex";
  private static final String RENT_COLUMNS =
      "id\tlamports_per_byte\texemption_threshold_bits_hex\tburn_percent\tdata_length\t"
          + "minimum_balance\twire_hex";
  private static final String INVALID_BOOL_COLUMNS =
      "type\tfield\tinvalid_byte\trust_rejects\twire_hex";
  private static final HexFormat HEX = HexFormat.of();
  private static final Map<String, String> COMMON_METADATA = Map.ofEntries(
      Map.entry("format", "sava-solana-upstream-layout-v1"),
      Map.entry("agave", "b9687a87037c787fa3257dc62fa00ff4708f1879"),
      Map.entry("agave-cargo-lock-sha256", "449a23496ae1776aa5109c9f86773885e8e839b3f4a1077be2c7b8698f8b5404"),
      Map.entry("solana-sdk", "7e8f4a52f044e7729406bd24ae7c586de92e7f58"),
      Map.entry("spl-token-2022-interface", "3.1.1"),
      Map.entry("spl-token-2022-interface-crate-checksum", "821d96d034ea31c4965d182c742153c491ae0abee531331b55771086c5030d86"),
      Map.entry("spl-token-group-interface", "0.7.2"),
      Map.entry("spl-token-group-interface-crate-checksum", "841cbd6f2322d02719be4da1affedbe6495b1048b7b985ec9796032564026e22"),
      Map.entry("spl-token-metadata-interface", "1.0.1"),
      Map.entry("spl-token-metadata-interface-crate-checksum", "3d3d96f175e7022ff200464dfa75a3708a4e9b70c83c4ecd04fe52ee479f4fef"),
      Map.entry("spl-type-length-value", "0.9.1"),
      Map.entry("spl-type-length-value-crate-checksum", "2504631748c48d2a937414d64a12dcac4588d34bd07d355d648619c189d29435"),
      Map.entry("solana-epoch-rewards", "3.2.0"),
      Map.entry("solana-epoch-rewards-crate-checksum", "0788d74ee15778deecaa15ed1a1e37727ba954f86cbc35225450a1f2b5012969"),
      Map.entry("solana-epoch-schedule", "3.3.0"),
      Map.entry("solana-epoch-schedule-crate-checksum", "a1633cfd10cde127f2caf8f12021b4f8e9a425e7e4eea4326e428c422376d6fd"),
      Map.entry("solana-program-option", "3.1.0"),
      Map.entry("solana-program-option-crate-checksum", "7a88006a9b8594088cec9027ab77caaaa258a2aaa2083d3f086c44b42e50aeab"),
      Map.entry("solana-rent", "4.4.0"),
      Map.entry("solana-rent-crate-checksum", "dc016b348926395ba01f8288cdf5da25fc30f5a0806028b32d5e5b3147b10bf9"),
      Map.entry("wincode", "0.6.1"),
      Map.entry("wincode-crate-checksum", "bfc6339f1ba427bf7ad7c42403b28e524832ba2ddb5eef1bb2cc3b85db6b7b75"),
      Map.entry("cargo-lock-sha256", "c68880c52e3faefefe48544e061650df0faa00b636f2dab2ead43d4e214f1406"),
      Map.entry("cargo-manifest-sha256", "1cd46c2c2ecb27d7cd89ffc8c97ce9a30e16b4d49cccdaae5d12bd3245c6bebd"),
      Map.entry("generator-source-sha256", "33d6770501fbb66239267d3afc0971e11ccdb17b30e49a12285bb631799b8ff2"),
      Map.entry("rust-toolchain", "1.97.1"),
      Map.entry("rust-toolchain-sha256", "5d959dfcc98b53886ee772ba216c4f9a1b31f093b46b5b263c0d084af54e821d")
  );

  public static void assertToken2022OrdinalsFixedLengthsAndRustAcceptanceMatch() throws IOException {
    final var fixture = loadFlatFixture(
        EXTENSIONS_RESOURCE,
        EXTENSION_COLUMNS,
        "every Token-2022 extension ordinal and fixed Rust TLV value length matches Sava",
        29
    );
    final var javaTypes = ExtensionType.values();
    assertEquals(javaTypes.length, fixture.rows().size());

    for (int index = 0; index < fixture.rows().size(); ++index) {
      final var fields = fields(fixture.rows().get(index), 10);
      final int ordinal = Integer.parseInt(fields[0]);
      final String name = fields[1];
      final String accountType = fields[2];
      final boolean fixed = fields[3].equals("fixed");
      final int valueLength = Integer.parseInt(fields[4]);
      final byte[] value = HEX.parseHex(fields[5]);

      assertEquals(index, ordinal, () -> "non-contiguous Rust ordinal for " + name);
      assertEquals(javaTypes[index].name(), name, () -> "Java extension name for ordinal " + ordinal);
      assertEquals(valueLength, value.length, () -> "fixture length for " + name);
      assertEquals(fields[6], sha256Hex(value), () -> "fixture value hash for " + name);
      assertTrue(parseBoolean(fields[7]), () -> "Rust rejected exact value for " + name);

      final var extension = onlyExtension(parseTlv(ordinal, value));
      assertEquals(ordinal, extension.ordinal(), () -> "parsed ordinal for " + name);
      assertEquals(valueLength, extension.l(), () -> "Java fixed/value length for " + name);
      assertAccountType(accountType, extension, name);
      final byte[] written = new byte[valueLength];
      assertEquals(valueLength, extension.write(written, 0), () -> "write length for " + name);
      assertArrayEquals(value, written, () -> "Rust/Java value bytes for " + name);

      if (fixed) {
        if (!fields[8].equals("n/a")) {
          final byte[] shortValue = Arrays.copyOf(value, value.length - 1);
          assertAcceptance(parseBoolean(fields[8]), ordinal, shortValue, name + " short");
        }
        final byte[] longValue = Arrays.copyOf(value, value.length + 1);
        longValue[longValue.length - 1] = (byte) 0xa5;
        assertAcceptance(parseBoolean(fields[9]), ordinal, longValue, name + " long");
      } else {
        assertEquals("TokenMetadata", name);
        assertEquals("n/a", fields[8]);
        assertEquals("n/a", fields[9]);
      }
    }
  }

  public static void assertTokenMetadataBorshAndTlvLengthBoundaryMatchRust() throws IOException {
    final var fixture = loadFlatFixture(
        METADATA_RESOURCE,
        METADATA_COLUMNS,
        "TokenMetadata Borsh bytes and the Token-2022 TLV u16 length boundary match Rust",
        4
    );
    final var updateAuthority = PublicKey.createPubKey(fill(0x11));
    final var mint = PublicKey.createPubKey(fill(0x22));

    for (final var row : fixture.rows()) {
      final var fields = fields(row, 12);
      final String id = fields[0];
      final String nameUnit = utf8(fields[1]);
      final String name = nameUnit.repeat(Integer.parseInt(fields[2]));
      final String symbol = utf8(fields[3]);
      final String uri = utf8(fields[4]);
      final String extraKey = utf8(fields[5]);
      final String extraValue = utf8(fields[6]);
      final Map<String, String> extras = extraKey.isEmpty()
          ? Map.of()
          : Map.of(extraKey, extraValue);
      final int packedLength = Integer.parseInt(fields[7]);
      final boolean tlvFits = parseBoolean(fields[10]);

      assertTrue(parseBoolean(fields[11]), () -> "Rust TokenMetadata round trip for " + id);
      final var metadata = new TokenMetadata(updateAuthority, mint, name, symbol, uri, extras);
      assertEquals(packedLength, metadata.l(), () -> "packed length for " + id);
      final byte[] written = new byte[packedLength];
      assertEquals(packedLength, metadata.write(written, 0), () -> "write length for " + id);
      assertEquals(fields[8], sha256Hex(written), () -> "Rust/Java metadata bytes for " + id);
      if (!fields[9].equals("-")) {
        assertArrayEquals(HEX.parseHex(fields[9]), written, () -> "literal fixture bytes for " + id);
      }

      final var parsed = TokenMetadata.read(written, 0);
      assertEquals(name, parsed.name(), () -> "name for " + id);
      assertEquals(symbol, parsed.symbol(), () -> "symbol for " + id);
      assertEquals(uri, parsed.uri(), () -> "uri for " + id);
      assertEquals(extras, parsed.additionalMetadata(), () -> "additional metadata for " + id);
      assertEquals(packedLength, parsed.l(), () -> "reparsed length for " + id);
      assertEquals(packedLength <= 0xffff, tlvFits, () -> "Rust TLV u16 boundary for " + id);

      if (tlvFits) {
        final var extension = onlyExtension(parseTlv(ExtensionType.TokenMetadata.ordinal(), written));
        assertInstanceOf(TokenMetadata.class, extension, () -> "TLV metadata for " + id);
      }
    }
  }

  public static void assertToken2022NonzeroBoolBytesMatchRust() throws IOException {
    final var fixture = loadFlatFixture(
        TOKEN_BOOLS_RESOURCE,
        TOKEN_BOOL_COLUMNS,
        "every nonzero solana-zero-copy Bool byte in Token-2022 decodes as true",
        9
    );
    for (final var row : fixture.rows()) {
      final var fields = fields(row, 6);
      final int ordinal = Integer.parseInt(fields[0]);
      final String name = fields[1];
      final String field = fields[2];
      final int boolOffset = Integer.parseInt(fields[3]);
      final byte[] value = HEX.parseHex(fields[4]);
      assertEquals(2, value[boolOffset] & 0xFF, () -> "fixture bool probe for " + name + '.' + field);
      assertTrue(parseBoolean(fields[5]), () -> "Rust Bool conversion for " + name + '.' + field);

      final var extension = onlyExtension(parseTlv(ordinal, value));
      final boolean javaValue = switch (extension) {
        case software.sava.core.accounts.token.extensions.ConfidentialTransferMint valueExtension ->
            valueExtension.autoApproveNewAccounts();
        case software.sava.core.accounts.token.extensions.ConfidentialTransferAccount valueExtension ->
            switch (field) {
              case "approved" -> valueExtension.approved();
              case "allowConfidentialCredits" -> valueExtension.allowConfidentialCredits();
              case "allowNonConfidentialCredits" -> valueExtension.allowNonConfidentialCredits();
              default -> fail("unknown ConfidentialTransferAccount Bool fixture field: " + field);
            };
        case software.sava.core.accounts.token.extensions.ConfidentialTransferFeeConfig valueExtension ->
            valueExtension.harvestToMintEnabled();
        case software.sava.core.accounts.token.extensions.MemoTransfer valueExtension ->
            valueExtension.requireIncomingTransferMemos();
        case software.sava.core.accounts.token.extensions.CpiGuard valueExtension -> valueExtension.lockCPI();
        case software.sava.core.accounts.token.extensions.TransferHookAccount valueExtension ->
            valueExtension.transferring();
        case software.sava.core.accounts.token.extensions.PausableConfig valueExtension -> valueExtension.paused();
        default -> fail("unexpected Bool fixture extension: " + extension);
      };
      assertTrue(javaValue, () -> "Java Bool conversion for " + name + '.' + field);
    }
  }

  public static void assertToken2022FullAccountsMatchRust() throws IOException {
    final var fixture = loadFlatFixture(
        TOKEN_ACCOUNTS_RESOURCE,
        TOKEN_ACCOUNT_COLUMNS,
        "full Token-2022 Mint and Account bytes pack and unpack through pinned SPL Rust APIs",
        2
    );
    for (final var row : fixture.rows()) {
      final var fields = fields(row, 6);
      final String id = fields[0];
      final String kind = fields[1];
      final var address = key(Integer.parseInt(fields[2]));
      final byte[] data = HEX.parseHex(fields[5]);
      assertEquals(Integer.parseInt(fields[3]), data.length, () -> "wire length for " + id);
      assertEquals(fields[4], sha256Hex(data), () -> "wire hash for " + id);

      switch (id) {
        case "agave_multi_extension_mint" -> assertFullMint(kind, address, data);
        case "agave_multi_extension_account" -> assertFullTokenAccount(kind, address, data);
        default -> fail("unknown full Token-2022 fixture: " + id);
      }
    }
  }

  private static void assertFullMint(final String kind, final PublicKey address, final byte[] data) {
    assertEquals("Mint", kind);
    final var token = Token2022.read(address, data);
    assertEquals(AccountType.Mint, token.accountType());

    final var mint = token.mint();
    assertEquals(address, mint.address());
    assertEquals(key(0x11), mint.mintAuthority());
    assertEquals(0x0102_0304_0506_0708L, mint.supply());
    assertEquals(9, mint.decimals());
    assertTrue(mint.initialized());
    assertEquals(key(0x12), mint.freezeAuthority());

    final var extensions = token.tokenExtensions();
    assertEquals(7, extensions.size());
    assertEquals(key(0x31), extension(extensions, MintCloseAuthority.class).closeAuthority());

    final var interest = extension(extensions, InterestBearingConfig.class);
    assertEquals(key(0x32), interest.rateAuthority());
    assertEquals(-1_700_000_000L, interest.initializationTimestamp());
    assertEquals(-321, interest.preUpdateAverageRate());
    assertEquals(1_700_000_000L, interest.lastUpdateTimestamp());
    assertEquals(456, interest.currentRate());

    assertEquals(key(0x34), extension(extensions, PermanentDelegate.class).delegate());
    final var hook = extension(extensions, TransferHook.class);
    assertEquals(key(0x35), hook.authority());
    assertEquals(key(0x36), hook.programId());

    final var scaled = extension(extensions, ScaledUiAmountConfig.class);
    assertEquals(key(0x33), scaled.authority());
    assertEquals(1.25, scaled.multiplier());
    assertEquals(1_800_000_000L, scaled.newMultiplierEffectiveTimestamp());
    assertEquals(2.5, scaled.newMultiplier());

    final var pausable = extension(extensions, PausableConfig.class);
    assertEquals(key(0x37), pausable.authority());
    assertTrue(pausable.paused());
    assertEquals(key(0x38), extension(extensions, PermissionedBurnConfig.class).authority());

    final byte[] written = new byte[token.l()];
    assertEquals(data.length, token.write(written, 0));
    assertArrayEquals(data, written, "Java must reproduce the complete SPL-packed mint");
  }

  private static void assertFullTokenAccount(
      final String kind,
      final PublicKey address,
      final byte[] data
  ) {
    assertEquals("Account", kind);
    final var account = Token2022Account.read(address, data);
    assertEquals(AccountType.Account, account.type());

    final var base = account.tokenAccount();
    assertEquals(address, base.address());
    assertEquals(key(0x41), base.mint());
    assertEquals(key(0x42), base.owner());
    assertEquals(0x1112_1314_1516_1718L, base.amount());
    assertEquals(1, base.delegateOption());
    assertEquals(key(0x43), base.delegate());
    assertEquals(AccountState.Frozen, base.state());
    assertEquals(1, base.isNativeOption());
    assertEquals(0x191a_1b1c_1d1e_1f20L, base.isNative());
    assertEquals(0x2122_2324_2526_2728L, base.delegatedAmount());
    assertEquals(1, base.closeAuthorityOption());
    assertEquals(key(0x44), base.closeAuthority());

    final var extensions = account.tokenExtensions();
    assertEquals(6, extensions.size());
    extension(extensions, ImmutableOwner.class);
    assertTrue(extension(extensions, MemoTransfer.class).requireIncomingTransferMemos());
    assertTrue(extension(extensions, CpiGuard.class).lockCPI());
    assertTrue(extension(extensions, TransferHookAccount.class).transferring());
    assertEquals(
        0x3132_3334_3536_3738L,
        extension(extensions, TransferFeeAmount.class).withHeldAmount()
    );
    extension(extensions, PausableAccount.class);

    final byte[] written = new byte[account.l()];
    assertEquals(data.length, account.write(written, 0));
    assertArrayEquals(data, written, "Java must reproduce the complete SPL-packed token account");
  }

  @Test
  void epochRewardsUnsignedU128AndWincodeLayoutMatchRust() throws IOException {
    final var fixture = loadSysvarFixture();
    assertEquals(2, fixture.epochRows().size());
    for (final var row : fixture.epochRows()) {
      final var fields = fields(row, 9);
      final String id = fields[0];
      final byte[] wire = HEX.parseHex(fields[8]);
      assertEquals(EpochRewards.BYTES, wire.length, () -> "wire length for " + id);

      final var rewards = EpochRewards.read(wire);
      assertEquals(Long.parseUnsignedLong(fields[1]), rewards.distributionStartingBlockHeight(), () -> "height for " + id);
      assertEquals(Long.parseUnsignedLong(fields[2]), rewards.numPartitions(), () -> "partitions for " + id);
      assertArrayEquals(HEX.parseHex(fields[3]), rewards.parentBlockHash(), () -> "parent hash for " + id);
      assertEquals(new BigInteger(fields[4]), rewards.totalPoints(), () -> "unsigned u128 for " + id);
      assertEquals(Long.parseUnsignedLong(fields[5]), rewards.totalRewards(), () -> "total rewards for " + id);
      assertEquals(Long.parseUnsignedLong(fields[6]), rewards.distributedRewards(), () -> "distributed rewards for " + id);
      assertEquals(parseBoolean(fields[7]), rewards.active(), () -> "active for " + id);

      final byte[] written = new byte[EpochRewards.BYTES];
      assertEquals(EpochRewards.BYTES, rewards.write(written, 0));
      assertArrayEquals(wire, written, () -> "Rust wincode bytes for " + id);
    }
  }

  @Test
  void wincodeBoolRejectionsMatchRust() throws IOException {
    final var fixture = loadSysvarFixture();
    assertEquals(2, fixture.invalidBoolRows().size());
    for (final var row : fixture.invalidBoolRows()) {
      final var fields = fields(row, 5);
      final String type = fields[0];
      assertEquals(2, Integer.parseInt(fields[2]), () -> "invalid byte for " + type);
      assertTrue(parseBoolean(fields[3]), () -> "Rust rejection for " + type + '.' + fields[1]);
      final byte[] wire = HEX.parseHex(fields[4]);
      final IllegalArgumentException exception = switch (type) {
        case "EpochRewards" -> assertThrows(IllegalArgumentException.class, () -> EpochRewards.read(wire));
        case "EpochSchedule" -> {
          final byte[] validTrue = wire.clone();
          validTrue[Long.BYTES << 1] = 1;
          assertTrue(EpochSchedule.read(validTrue).warmup(), "wincode accepts the canonical true byte");
          yield assertThrows(IllegalArgumentException.class, () -> EpochSchedule.read(wire));
        }
        default -> fail("unknown invalid-bool fixture type: " + type);
      };
      assertTrue(exception.getMessage().contains("boolean encoding"), exception::getMessage);
    }
  }

  @Test
  void rentIntegerPathsValidationAndWincodeLayoutMatchRust() throws IOException {
    final var fixture = loadSysvarFixture();
    assertEquals(20, fixture.rentRows().size());
    for (final var row : fixture.rentRows()) {
      final var fields = fields(row, 7);
      final String id = fields[0];
      final byte[] wire = HEX.parseHex(fields[6]);
      assertEquals(Rent.BYTES, wire.length, () -> "wire length for " + id);

      final var rent = Rent.read(wire);
      assertEquals(Long.parseUnsignedLong(fields[1]), rent.lamportsPerByteYear(), () -> "rate for " + id);
      assertArrayEquals(
          HEX.parseHex(fields[2]),
          littleEndian(Double.doubleToRawLongBits(rent.exemptionThreshold())),
          () -> "threshold bits for " + id
      );
      assertEquals(Integer.parseInt(fields[3]), rent.burnPercent(), () -> "burn percent for " + id);
      final long dataLength = Long.parseUnsignedLong(fields[4]);
      if (fields[5].equals("none")) {
        assertThrows(IllegalArgumentException.class, () -> rent.minimumBalance(dataLength), id);
      } else {
        final long minimumBalance = rent.minimumBalance(dataLength);
        assertEquals(fields[5], Long.toUnsignedString(minimumBalance), () -> "minimum balance for " + id);
      }

      final byte[] written = new byte[Rent.BYTES];
      assertEquals(Rent.BYTES, rent.write(written, 0));
      assertArrayEquals(wire, written, () -> "Rust wincode bytes for " + id);
    }
  }

  @Test
  void fixtureProvenanceMatchesCommittedGeneratorInputs() throws IOException {
    final var extensions = loadFlatFixture(
        EXTENSIONS_RESOURCE,
        EXTENSION_COLUMNS,
        "every Token-2022 extension ordinal and fixed Rust TLV value length matches Sava",
        29
    );
    final var metadata = loadFlatFixture(
        METADATA_RESOURCE,
        METADATA_COLUMNS,
        "TokenMetadata Borsh bytes and the Token-2022 TLV u16 length boundary match Rust",
        4
    );
    final var tokenBools = loadFlatFixture(
        TOKEN_BOOLS_RESOURCE,
        TOKEN_BOOL_COLUMNS,
        "every nonzero solana-zero-copy Bool byte in Token-2022 decodes as true",
        9
    );
    final var tokenAccounts = loadFlatFixture(
        TOKEN_ACCOUNTS_RESOURCE,
        TOKEN_ACCOUNT_COLUMNS,
        "full Token-2022 Mint and Account bytes pack and unpack through pinned SPL Rust APIs",
        2
    );
    final var sysvars = loadSysvarFixture();
    assertEquals(extensions.metadata().get("cargo-lock-sha256"), metadata.metadata().get("cargo-lock-sha256"));
    assertEquals(extensions.metadata().get("cargo-lock-sha256"), tokenBools.metadata().get("cargo-lock-sha256"));
    assertEquals(extensions.metadata().get("cargo-lock-sha256"), tokenAccounts.metadata().get("cargo-lock-sha256"));
    assertEquals(extensions.metadata().get("cargo-lock-sha256"), sysvars.metadata().get("cargo-lock-sha256"));
    assertFileSha256(extensions.metadata(), "cargo-lock-sha256", "Cargo.lock");
    assertFileSha256(extensions.metadata(), "cargo-manifest-sha256", "Cargo.toml");
    assertFileSha256(extensions.metadata(), "generator-source-sha256", "src/main.rs");
    assertFileSha256(extensions.metadata(), "rust-toolchain-sha256", "rust-toolchain.toml");
  }

  private static Set<TokenExtension> parseTlv(final int ordinal, final byte[] value) {
    final byte[] tlv = new byte[4 + value.length];
    ByteUtil.putInt16LE(tlv, 0, ordinal);
    ByteUtil.putInt16LE(tlv, 2, value.length);
    System.arraycopy(value, 0, tlv, 4, value.length);
    return Token2022.parseExtensions(tlv, 0);
  }

  private static TokenExtension onlyExtension(final Set<TokenExtension> extensions) {
    assertEquals(1, extensions.size());
    return extensions.iterator().next();
  }

  private static <T extends TokenExtension> T extension(
      final Set<TokenExtension> extensions,
      final Class<T> type
  ) {
    for (final var extension : extensions) {
      if (type.isInstance(extension)) {
        return type.cast(extension);
      }
    }
    return fail("missing extension " + type.getSimpleName());
  }

  private static void assertAcceptance(
      final boolean accepts,
      final int ordinal,
      final byte[] value,
      final String description
  ) {
    if (accepts) {
      assertDoesNotThrow(() -> parseTlv(ordinal, value), description);
    } else {
      final var thrown = assertThrows(RuntimeException.class, () -> parseTlv(ordinal, value), description);
      assertTrue(
          thrown instanceof IllegalArgumentException || thrown instanceof IndexOutOfBoundsException,
          () -> "unexpected rejection for " + description + ": " + thrown
      );
    }
  }

  private static void assertAccountType(
      final String accountType,
      final TokenExtension extension,
      final String name
  ) {
    switch (accountType) {
      case "Mint" -> assertInstanceOf(MintTokenExtension.class, extension, name);
      case "Account" -> assertInstanceOf(AccountTokenExtension.class, extension, name);
      case "Uninitialized" -> assertEquals(ExtensionType.Uninitialized.ordinal(), extension.ordinal(), name);
      default -> fail("unknown Rust account type for " + name + ": " + accountType);
    }
  }

  private static FlatFixture loadFlatFixture(
      final String resource,
      final String columns,
      final String property,
      final int rowCount
  ) throws IOException {
    final var lines = readLines(resource);
    final var metadata = parseMetadata(lines);
    assertMetadata(metadata, property, rowCount);
    final int columnsIndex = firstBodyLine(lines);
    assertEquals(columns, lines.get(columnsIndex), () -> "columns for " + resource);
    final var rows = new ArrayList<>(lines.subList(columnsIndex + 1, lines.size()));
    assertEquals(rowCount, rows.size(), () -> "row count for " + resource);
    return new FlatFixture(metadata, rows);
  }

  private static SysvarFixture loadSysvarFixture() throws IOException {
    final var lines = readLines(SYSVARS_RESOURCE);
    final var metadata = parseMetadata(lines);
    assertMetadata(
        metadata,
        "EpochRewards u128, strict wincode bools, and Rent boundaries match current Solana APIs",
        24
    );
    final int first = firstBodyLine(lines);
    assertEquals("[epoch_rewards]", lines.get(first));
    assertEquals(EPOCH_COLUMNS, lines.get(first + 1));
    final int invalidBools = lines.indexOf("[invalid_bools]");
    assertTrue(invalidBools > first + 1, "missing invalid-bools section");
    assertEquals(INVALID_BOOL_COLUMNS, lines.get(invalidBools + 1));
    final int rent = lines.indexOf("[rent]");
    assertTrue(rent > invalidBools + 1, "missing rent section");
    assertEquals(RENT_COLUMNS, lines.get(rent + 1));
    final var epochRows = new ArrayList<>(lines.subList(first + 2, invalidBools));
    final var invalidBoolRows = new ArrayList<>(lines.subList(invalidBools + 2, rent));
    final var rentRows = new ArrayList<>(lines.subList(rent + 2, lines.size()));
    assertEquals(24, epochRows.size() + invalidBoolRows.size() + rentRows.size());
    return new SysvarFixture(metadata, epochRows, invalidBoolRows, rentRows);
  }

  private static List<String> readLines(final String resource) throws IOException {
    final var input = SolanaUpstreamLayoutConformanceTests.class.getResourceAsStream(resource);
    assertNotNull(input, "missing fixture " + resource);
    final var lines = new ArrayList<String>();
    try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        assertFalse(line.isBlank(), () -> "blank line in " + resource);
        lines.add(line);
      }
    }
    return lines;
  }

  private static Map<String, String> parseMetadata(final List<String> lines) {
    final var metadata = new LinkedHashMap<String, String>();
    for (final var line : lines) {
      if (!line.startsWith("# ")) {
        break;
      }
      final int separator = line.indexOf(": ", 2);
      assertTrue(separator > 2, () -> "malformed metadata: " + line);
      final var key = line.substring(2, separator);
      assertNull(metadata.putIfAbsent(key, line.substring(separator + 2)), () -> "duplicate metadata: " + key);
    }
    return metadata;
  }

  private static int firstBodyLine(final List<String> lines) {
    for (int index = 0; index < lines.size(); ++index) {
      if (!lines.get(index).startsWith("# ")) {
        return index;
      }
    }
    return fail("fixture contains only metadata");
  }

  private static void assertMetadata(
      final Map<String, String> metadata,
      final String property,
      final int rows
  ) {
    final var expected = new LinkedHashMap<>(COMMON_METADATA);
    expected.put("property", property);
    expected.put("rows", Integer.toString(rows));
    assertEquals(expected, metadata, "fixture provenance changed");
  }

  private static String[] fields(final String row, final int expected) {
    final var fields = row.split("\\t", -1);
    assertEquals(expected, fields.length, () -> "field count for " + row);
    return fields;
  }

  private static boolean parseBoolean(final String value) {
    assertTrue(value.equals("true") || value.equals("false"), () -> "invalid boolean: " + value);
    return Boolean.parseBoolean(value);
  }

  private static String utf8(final String hex) {
    return new String(HEX.parseHex(hex), StandardCharsets.UTF_8);
  }

  private static byte[] fill(final int value) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  private static PublicKey key(final int value) {
    return PublicKey.createPubKey(fill(value));
  }

  private static byte[] littleEndian(final long value) {
    final byte[] bytes = new byte[Long.BYTES];
    ByteUtil.putInt64LE(bytes, 0, value);
    return bytes;
  }

  private static void assertFileSha256(
      final Map<String, String> metadata,
      final String metadataKey,
      final String relativePath
  ) throws IOException {
    final var path = generatorPath(relativePath);
    assertEquals(
        metadata.get(metadataKey),
        sha256Hex(Files.readAllBytes(path)),
        () -> "stale fixture provenance for " + path
    );
  }

  private static Path generatorPath(final String relativePath) {
    final var moduleRelative = Path.of("src/test/solana/upstream-layout-vectors").resolve(relativePath);
    if (Files.isRegularFile(moduleRelative)) {
      return moduleRelative;
    }
    final var repositoryRelative = Path.of("sava-core").resolve(moduleRelative);
    assertTrue(Files.isRegularFile(repositoryRelative), () -> "missing generator input " + relativePath);
    return repositoryRelative;
  }

  private static String sha256Hex(final byte[] bytes) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new AssertionError("JDK has no SHA-256 provider", e);
    }
  }

  private record FlatFixture(Map<String, String> metadata, ArrayList<String> rows) {
  }

  private record SysvarFixture(
      Map<String, String> metadata,
      ArrayList<String> epochRows,
      ArrayList<String> invalidBoolRows,
      ArrayList<String> rentRows
  ) {
  }
}
