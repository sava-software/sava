package software.sava.core.accounts.token;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.extensions.AccountType;
import software.sava.core.accounts.token.extensions.ConfidentialMintBurn;
import software.sava.core.accounts.token.extensions.ConfidentialTransferAccount;
import software.sava.core.accounts.token.extensions.ConfidentialTransferFeeAmount;
import software.sava.core.accounts.token.extensions.ConfidentialTransferFeeConfig;
import software.sava.core.accounts.token.extensions.ConfidentialTransferMint;
import software.sava.core.accounts.token.extensions.CpiGuard;
import software.sava.core.accounts.token.extensions.DefaultAccountState;
import software.sava.core.accounts.token.extensions.GroupMemberPointer;
import software.sava.core.accounts.token.extensions.GroupPointer;
import software.sava.core.accounts.token.extensions.InterestBearingConfig;
import software.sava.core.accounts.token.extensions.MemoTransfer;
import software.sava.core.accounts.token.extensions.MetadataPointer;
import software.sava.core.accounts.token.extensions.MintCloseAuthority;
import software.sava.core.accounts.token.extensions.PausableConfig;
import software.sava.core.accounts.token.extensions.PermanentDelegate;
import software.sava.core.accounts.token.extensions.PermissionedBurnConfig;
import software.sava.core.accounts.token.extensions.ScaledUiAmountConfig;
import software.sava.core.accounts.token.extensions.TokenExtension;
import software.sava.core.accounts.token.extensions.TokenGroup;
import software.sava.core.accounts.token.extensions.TokenGroupMember;
import software.sava.core.accounts.token.extensions.TokenMetadata;
import software.sava.core.accounts.token.extensions.TransferFee;
import software.sava.core.accounts.token.extensions.TransferFeeAmount;
import software.sava.core.accounts.token.extensions.TransferFeeConfig;
import software.sava.core.accounts.token.extensions.TransferHook;
import software.sava.core.accounts.token.extensions.TransferHookAccount;
import software.sava.core.accounts.token.extensions.UnknownTokenExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/// Conformance for this library's Token-2022 account decoding against **real mainnet
/// accounts**, captured with their slot under `src/test/resources/token2022/mainnet`.
///
/// The oracle is the validator's own `jsonParsed` decoder. Every fixture ships as three
/// files: the raw account bytes named by the account's address, the node's decode of those
/// same bytes as `<address>.parsed.json`, and a row in `manifest.json` recording the slot,
/// the length, the account kind and the extension type ids in TLV order. So a disagreement
/// here is a disagreement between two independent decoders of one byte string, not between
/// this library and someone's reading of the program's source. `SolanaUpstreamLayoutConformanceTests`
/// is the other half of that: it pins synthetic layouts through the upstream Rust APIs,
/// where this pins what the chain actually holds.
///
/// The corpus is deliberately not a fuzz seed corpus and does not live under
/// `src/test/resources/fuzz`: provenance files sit beside a corpus, never inside one, because
/// every file inside a corpus directory is fed to the harness as a seed.
///
/// Three things are checked per fixture, and each of them fails differently:
///
/// 1. the parsed extension ids and their order match the TLV chain the manifest recorded;
/// 2. writing the decoded record back into a fresh `byte[l()]` reproduces the account
///    byte for byte, including the two-byte pad the program adds at `Multisig::LEN`;
/// 3. every field the node's parse exposes equals the corresponding record component.
///
/// A zeroable-option authority is where the two decoders deliberately differ in shape:
/// jsonParsed prints `null`, this library decodes the 32 zero bytes that are actually there
/// as [PublicKey#NONE] (`CONVENTIONS.md`). That correspondence is asserted rather than
/// skipped — treating an unset authority as a real key is exactly the mistake the convention
/// exists to prevent.
final class Token2022MainnetConformanceTests {

  private static final String CORPUS = "/token2022/mainnet/";

  /// The highest extension type this library decodes; a fixture may only carry an
  /// [UnknownTokenExtension] above it.
  private static final int HIGHEST_KNOWN_EXTENSION = 28;

  /// Extension types with no mainnet instance found when this corpus was captured, and what
  /// was searched for each. This is an empirical absence, not a claim that none can exist —
  /// so it is a recorded exception, and the coverage guard fails the moment a fixture turns
  /// one up.
  private static final Map<Integer, String> NOT_FOUND_ON_MAINNET = Map.of(
      17, "ConfidentialTransferFeeAmount: six million extended token accounts walked TLV by"
          + " TLV, plus every token account of the eighteen mints found carrying"
          + " ConfidentialTransferFeeConfig or ConfidentialTransferMint — the mint-side"
          + " pairing this account-side state requires"
  );

  /// jsonParsed extension name to the on-chain type id, from
  /// `account_decoder/src/parse_token_extension.rs`. The names are the node's, the ids are
  /// [TokenExtension#ordinal()]'s, and pairing them is what lets a field comparison be
  /// written against the right record.
  private static final Map<String, Integer> EXTENSION_IDS = Map.ofEntries(
      Map.entry("transferFeeConfig", 1),
      Map.entry("transferFeeAmount", 2),
      Map.entry("mintCloseAuthority", 3),
      Map.entry("confidentialTransferMint", 4),
      Map.entry("confidentialTransferAccount", 5),
      Map.entry("defaultAccountState", 6),
      Map.entry("immutableOwner", 7),
      Map.entry("memoTransfer", 8),
      Map.entry("nonTransferable", 9),
      Map.entry("interestBearingConfig", 10),
      Map.entry("cpiGuard", 11),
      Map.entry("permanentDelegate", 12),
      Map.entry("nonTransferableAccount", 13),
      Map.entry("transferHook", 14),
      Map.entry("transferHookAccount", 15),
      Map.entry("confidentialTransferFeeConfig", 16),
      Map.entry("confidentialTransferFeeAmount", 17),
      Map.entry("metadataPointer", 18),
      Map.entry("tokenMetadata", 19),
      Map.entry("groupPointer", 20),
      Map.entry("tokenGroup", 21),
      Map.entry("groupMemberPointer", 22),
      Map.entry("tokenGroupMember", 23),
      Map.entry("confidentialMintBurn", 24),
      Map.entry("scaledUiAmountConfig", 25),
      Map.entry("pausableConfig", 26),
      Map.entry("pausableAccount", 27),
      Map.entry("permissionedBurnConfig", 28)
  );

  /// One captured account. `extensions` is the TLV chain read off the committed bytes when
  /// they were captured, in wire order.
  private record Fixture(String address,
                         long slot,
                         int length,
                         String kind,
                         List<Integer> extensions,
                         String sha256,
                         String note,
                         byte[] data,
                         Map<String, Object> parsed) {

    PublicKey key() {
      return PublicKey.fromBase58Encoded(address);
    }

    Map<String, Object> info() {
      return object(parsed.get("info"));
    }

    List<Object> parsedExtensions() {
      final var extensions = info().get("extensions");
      return extensions == null ? List.of() : array(extensions);
    }

    @Override
    public String toString() {
      return address + " (" + kind + ", " + length + " bytes, slot " + slot + ")";
    }
  }

  private static List<Fixture> loadCorpus() {
    final var manifest = object(Json.parse(readString("manifest.json")));
    assertEquals(
        "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",
        string(manifest.get("program")),
        "the corpus must be Token-2022 accounts"
    );
    final var accounts = array(manifest.get("accounts"));
    assertFalse(accounts.isEmpty(), "the mainnet corpus is empty");
    final var fixtures = new ArrayList<Fixture>(accounts.size());
    for (final var entry : accounts) {
      final var row = object(entry);
      final var address = string(row.get("address"));
      final var ids = new ArrayList<Integer>();
      for (final var id : array(row.get("extensions"))) {
        ids.add(Math.toIntExact(u64(id)));
      }
      fixtures.add(new Fixture(
          address,
          u64(row.get("slot")),
          Math.toIntExact(u64(row.get("length"))),
          string(row.get("kind")),
          List.copyOf(ids),
          string(row.get("sha256")),
          string(row.get("note")),
          read(address),
          object(Json.parse(readString(address + ".parsed.json")))
      ));
    }
    return fixtures;
  }

  private static byte[] read(final String name) {
    try (final var input = Token2022MainnetConformanceTests.class.getResourceAsStream(CORPUS + name)) {
      assertNotNull(input, "missing Token-2022 mainnet fixture " + name);
      return input.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String readString(final String name) {
    return new String(read(name), StandardCharsets.UTF_8);
  }

  private static String sha256(final byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /// The committed bytes are the fixture. Everything else in the corpus — the manifest row,
  /// the node's parse — describes them, so a silent edit to one of them would otherwise
  /// retune the oracle instead of failing.
  @Test
  void committedBytesMatchTheirManifestRow() {
    for (final var fixture : loadCorpus()) {
      assertEquals(fixture.length(), fixture.data().length, fixture + ": recorded length");
      assertEquals(fixture.sha256(), sha256(fixture.data()), fixture + ": recorded digest");
      assertTrue(fixture.slot() > 0, fixture + ": every capture records the slot it was read at");
      assertFalse(fixture.note().isBlank(),
          fixture + ": every capture says why it is in the corpus, or it cannot be re-picked");
      // "mint", "account" and "multisig" are the node's own words for the three states this
      // program stores, so the manifest's kind column is checked rather than trusted.
      assertEquals(string(fixture.parsed().get("type")), fixture.kind(),
          fixture + ": the node and the manifest must agree on the account kind");
    }
  }

  /// Coverage guard: the corpus exists to answer "does this decode real accounts", which it
  /// cannot do for an extension it does not contain. The comparison is with the recorded
  /// exceptions rather than with the empty set, so a type that stops being covered fails here,
  /// and so does one that is listed as absent while a fixture actually carries it.
  @Test
  void everyExtensionTypeIsRepresentedOrRecordedAsAbsent() {
    final var covered = new TreeSet<Integer>();
    for (final var fixture : loadCorpus()) {
      covered.addAll(fixture.extensions());
    }
    final var missing = new TreeSet<Integer>();
    for (int id = 1; id <= HIGHEST_KNOWN_EXTENSION; ++id) {
      if (!covered.contains(id)) {
        missing.add(id);
      }
    }
    assertEquals(NOT_FOUND_ON_MAINNET.keySet(), missing, "extension types with no mainnet fixture");
  }

  /// The account-side half of the multisig-length pad is not merely missing from the corpus,
  /// it cannot exist: `adjust_len_for_multisig` only fires when the *natural* size is exactly
  /// `Multisig::LEN`, which for a token account means 189 bytes of TLV after the 165-byte base
  /// state and the account-type byte. Every account-side extension other than the confidential
  /// transfer state sums to 107 bytes together, and that state alone is 299 — so no subset of
  /// the types this library knows reaches 189, and a 357-byte Token-2022 account is always a
  /// mint. Only an extension type released after this library was last synced could change
  /// that, which is why the arithmetic is asserted rather than written down.
  @Test
  void noCombinationOfAccountExtensionsLandsOnTheMultisigLength() {
    final int[] tlvLengths = {
        Integer.BYTES + TransferFeeAmount.BYTES,             // 2
        Integer.BYTES + ConfidentialTransferAccount.BYTES,   // 5
        Integer.BYTES,                                       // 7  ImmutableOwner, no value
        Integer.BYTES + MemoTransfer.BYTES,                  // 8
        Integer.BYTES + CpiGuard.BYTES,                      // 11
        Integer.BYTES,                                       // 13 NonTransferableAccount
        Integer.BYTES + TransferHookAccount.BYTES,           // 15
        Integer.BYTES + ConfidentialTransferFeeAmount.BYTES, // 17
        Integer.BYTES                                        // 27 PausableAccount
    };
    final int naturalTlv = 355 - (TokenAccount.BYTES + 1);
    assertEquals(189, naturalTlv, "the TLV budget that would land a token account on Multisig::LEN");

    for (int mask = 1; mask < (1 << tlvLengths.length); ++mask) {
      int total = 0;
      for (int i = 0; i < tlvLengths.length; ++i) {
        if ((mask & (1 << i)) != 0) {
          total += tlvLengths[i];
        }
      }
      final int subset = mask;
      assertNotEquals(naturalTlv, total,
          () -> "the extension subset 0x" + Integer.toHexString(subset) + " reaches Multisig::LEN");
    }
  }

  /// The decode half: the TLV chain this library walks must be the one the bytes carry, in
  /// the order they carry it, with no value falling through to [UnknownTokenExtension].
  ///
  /// Then the write half, which is the assertion a synthetic round trip cannot make: the
  /// account has to come back out identical to what the chain holds. Sizing is where that
  /// bites — an account whose extensions land it exactly on `Multisig::LEN` is allocated two
  /// extra bytes by the program, so a naive sum writes an account the program itself refuses
  /// to unpack.
  @Test
  void accountsDecodeInTlvOrderAndRoundTripByteForByte() {
    for (final var fixture : loadCorpus()) {
      if (fixture.kind().equals("multisig")) {
        continue;
      }
      final var decoded = decode(fixture);

      final var ids = new ArrayList<Integer>();
      for (final var extension : extensionsOf(decoded)) {
        assertFalse(
            extension instanceof UnknownTokenExtension unknown
                && unknown.type() <= HIGHEST_KNOWN_EXTENSION,
            () -> fixture + ": extension type " + extension.ordinal()
                + " is known to this library but decoded as UnknownTokenExtension"
        );
        ids.add(extension.ordinal());
      }
      assertEquals(fixture.extensions(), ids, fixture + ": extension ids in TLV order");

      assertEquals(fixture.length(), length(decoded), fixture + ": serialized length");
      final byte[] written = new byte[length(decoded)];
      assertEquals(written.length, write(decoded, written), fixture + ": bytes written");
      assertArrayEquals(fixture.data(), written, fixture + ": byte-exact round trip");
      assertEquals(decoded, decode(fixture.address(), written, fixture.kind()),
          fixture + ": re-reading the written bytes");
    }
  }

  /// `check_min_len_and_not_multisig` — interface/src/extension/mod.rs. A multisig is never
  /// extensible, so a buffer of exactly `Multisig::LEN` cannot be told apart from a mint or a
  /// token account carrying extensions, and both readers refuse it rather than guess. The
  /// fixture is a real on-chain multisig, and the node's own parse agrees it is one.
  @Test
  void theMultisigLengthFixtureIsRejectedByBothReaders() {
    int multisigs = 0;
    for (final var fixture : loadCorpus()) {
      if (!fixture.kind().equals("multisig")) {
        continue;
      }
      ++multisigs;
      assertEquals(355, fixture.length(), fixture + ": Multisig::LEN");
      assertEquals("multisig", string(fixture.parsed().get("type")),
          fixture + ": the node decodes these bytes as a multisig");

      final var expected =
          "Account data of 355 bytes is the length of a Multisig, which is never extensible.";
      final var address = fixture.key();
      final byte[] data = fixture.data();
      assertEquals(expected, assertThrows(
          IllegalArgumentException.class,
          () -> Token2022.read(address, data),
          fixture + ": the mint reader must refuse a multisig-length account"
      ).getMessage());
      assertEquals(expected, assertThrows(
          IllegalArgumentException.class,
          () -> Token2022Account.read(address, data),
          fixture + ": the token-account reader must refuse a multisig-length account"
      ).getMessage());
    }
    assertEquals(1, multisigs, "the corpus must carry exactly one real multisig");
  }

  /// The field-by-field comparison against the node's decode of the same bytes: base state
  /// first, then every extension, paired by position because both decoders walk the TLV
  /// chain in wire order.
  @Test
  void decodedFieldsMatchTheNodesParse() {
    for (final var fixture : loadCorpus()) {
      if (fixture.kind().equals("multisig")) {
        continue;
      }
      final var decoded = decode(fixture);
      final var info = fixture.info();
      if (decoded instanceof Token2022 mint) {
        assertMintState(fixture, info, mint.mint());
        assertAccountType(fixture, mint.accountType(), AccountType.Mint);
      } else {
        assertTokenAccountState(fixture, info, ((Token2022Account) decoded).tokenAccount());
        assertAccountType(fixture, ((Token2022Account) decoded).type(), AccountType.Account);
      }

      final var parsed = fixture.parsedExtensions();
      final var extensions = extensionsOf(decoded);
      assertEquals(parsed.size(), extensions.size(),
          fixture + ": the node and this library must find the same number of extensions");
      int i = 0;
      for (final var extension : extensions) {
        assertExtension(fixture, object(parsed.get(i++)), extension);
      }
    }
  }

  private static void assertAccountType(final Fixture fixture,
                                        final AccountType decoded,
                                        final AccountType expected) {
    // A base-length account has no discriminant byte at all; anything longer carries one.
    assertEquals(fixture.length() == 82 || fixture.length() == 165 ? null : expected, decoded,
        fixture + ": account type discriminant");
  }

  private static void assertMintState(final Fixture fixture,
                                      final Map<String, Object> info,
                                      final Mint mint) {
    assertEquals(fixture.address(), mint.address().toBase58(), fixture + ": mint address");
    // COption on the base state, so absence really is null here — unlike an extension's
    // zeroable option, which is a present field of 32 zero bytes.
    assertCOption(info.get("mintAuthority"), mint.mintAuthority(), fixture + ": mintAuthority");
    assertCOption(info.get("freezeAuthority"), mint.freezeAuthority(), fixture + ": freezeAuthority");
    assertEquals(u64(info.get("supply")), mint.supply(), fixture + ": supply");
    assertEquals(Math.toIntExact(u64(info.get("decimals"))), mint.decimals(), fixture + ": decimals");
    assertEquals(info.get("isInitialized"), mint.initialized(), fixture + ": isInitialized");
  }

  private static void assertTokenAccountState(final Fixture fixture,
                                              final Map<String, Object> info,
                                              final TokenAccount account) {
    assertEquals(fixture.address(), account.address().toBase58(), fixture + ": account address");
    assertEquals(string(info.get("mint")), account.mint().toBase58(), fixture + ": mint");
    assertEquals(string(info.get("owner")), account.owner().toBase58(), fixture + ": owner");
    assertEquals(u64(object(info.get("tokenAmount")).get("amount")), account.amount(),
        fixture + ": amount");
    assertEquals(
        switch (account.state()) {
          case Uninitialized -> "uninitialized";
          case Initialized -> "initialized";
          case Frozen -> "frozen";
        },
        string(info.get("state")),
        fixture + ": state"
    );
    // An absent COption is an omitted key rather than a null one in the node's account
    // parse, and the delegated amount only appears alongside a delegate.
    assertCOption(info.get("delegate"), account.delegate(), fixture + ": delegate");
    assertEquals(info.containsKey("delegate") ? 1 : 0, account.delegateOption(),
        fixture + ": delegate option tag");
    assertEquals(
        info.containsKey("delegatedAmount")
            ? u64(object(info.get("delegatedAmount")).get("amount"))
            : 0L,
        account.delegatedAmount(),
        fixture + ": delegatedAmount"
    );
    assertCOption(info.get("closeAuthority"), account.closeAuthority(), fixture + ": closeAuthority");
    assertEquals(info.containsKey("closeAuthority") ? 1 : 0, account.closeAuthorityOption(),
        fixture + ": close authority option tag");
    assertEquals(info.get("isNative"), account.isNativeOption() == 1, fixture + ": isNative option tag");
    if (Boolean.TRUE.equals(info.get("isNative"))) {
      assertEquals(u64(object(info.get("rentExemptReserve")).get("amount")), account.isNative(),
          fixture + ": rentExemptReserve");
    } else {
      assertEquals(0L, account.isNative(), fixture + ": a non-native account has no rent-exempt reserve");
    }
  }

  private static void assertExtension(final Fixture fixture,
                                      final Map<String, Object> parsed,
                                      final TokenExtension decoded) {
    final var name = string(parsed.get("extension"));
    final var id = EXTENSION_IDS.get(name);
    assertNotNull(id, fixture + ": the node reported an extension this test cannot map: " + name);
    assertEquals(id.intValue(), decoded.ordinal(), fixture + ": extension at this position is " + name);

    final var where = fixture + " " + name + '.';
    final var state = parsed.containsKey("state") ? object(parsed.get("state")) : Map.<String, Object>of();
    switch (name) {
      case "transferFeeConfig" -> {
        final var config = assertInstanceOf(TransferFeeConfig.class, decoded, where);
        assertZeroable(state.get("transferFeeConfigAuthority"), config.transferFeeConfigAuthority(),
            where + "transferFeeConfigAuthority");
        assertZeroable(state.get("withdrawWithheldAuthority"), config.withdrawWithheldAuthority(),
            where + "withdrawWithheldAuthority");
        assertEquals(u64(state.get("withheldAmount")), config.withheldAmount(), where + "withheldAmount");
        assertTransferFee(object(state.get("olderTransferFee")), config.olderTransferFee(),
            where + "olderTransferFee.");
        assertTransferFee(object(state.get("newerTransferFee")), config.newerTransferFee(),
            where + "newerTransferFee.");
      }
      case "transferFeeAmount" -> assertEquals(
          u64(state.get("withheldAmount")),
          assertInstanceOf(TransferFeeAmount.class, decoded, where).withHeldAmount(),
          where + "withheldAmount"
      );
      case "mintCloseAuthority" -> assertZeroable(
          state.get("closeAuthority"),
          assertInstanceOf(MintCloseAuthority.class, decoded, where).closeAuthority(),
          where + "closeAuthority"
      );
      case "confidentialTransferMint" -> {
        final var config = assertInstanceOf(ConfidentialTransferMint.class, decoded, where);
        assertZeroable(state.get("authority"), config.authority(), where + "authority");
        assertEquals(state.get("autoApproveNewAccounts"), config.autoApproveNewAccounts(),
            where + "autoApproveNewAccounts");
        // The auditor key is an ElGamal public key, printed base64 rather than base58, and
        // zeroable the same way an address is.
        assertZeroableBytes(state.get("auditorElgamalPubkey"), config.auditorElGamalKey().toByteArray(),
            where + "auditorElgamalPubkey");
      }
      case "confidentialTransferAccount" -> {
        final var account = assertInstanceOf(ConfidentialTransferAccount.class, decoded, where);
        assertEquals(state.get("approved"), account.approved(), where + "approved");
        assertArrayEquals(base64(state.get("elgamalPubkey")), account.elgamalPubkey().toByteArray(),
            where + "elgamalPubkey");
        assertArrayEquals(base64(state.get("pendingBalanceLo")), account.pendingBalanceLo(),
            where + "pendingBalanceLo");
        assertArrayEquals(base64(state.get("pendingBalanceHi")), account.pendingBalanceHi(),
            where + "pendingBalanceHi");
        assertArrayEquals(base64(state.get("availableBalance")), account.availableBalance(),
            where + "availableBalance");
        assertArrayEquals(base64(state.get("decryptableAvailableBalance")),
            account.decryptableAvailableBalance(), where + "decryptableAvailableBalance");
        assertEquals(state.get("allowConfidentialCredits"), account.allowConfidentialCredits(),
            where + "allowConfidentialCredits");
        assertEquals(state.get("allowNonConfidentialCredits"), account.allowNonConfidentialCredits(),
            where + "allowNonConfidentialCredits");
        assertEquals(u64(state.get("pendingBalanceCreditCounter")),
            account.pendingBalanceCreditCounter(), where + "pendingBalanceCreditCounter");
        assertEquals(u64(state.get("maximumPendingBalanceCreditCounter")),
            account.maximumPendingBalanceCreditCounter(), where + "maximumPendingBalanceCreditCounter");
        assertEquals(u64(state.get("expectedPendingBalanceCreditCounter")),
            account.expectedPendingBalanceCreditCounter(), where + "expectedPendingBalanceCreditCounter");
        assertEquals(u64(state.get("actualPendingBalanceCreditCounter")),
            account.actualPendingBalanceCreditCounter(), where + "actualPendingBalanceCreditCounter");
      }
      case "defaultAccountState" -> assertEquals(
          switch (assertInstanceOf(DefaultAccountState.class, decoded, where).state()) {
            case 0 -> "uninitialized";
            case 1 -> "initialized";
            case 2 -> "frozen";
            default -> "unknown";
          },
          string(state.get("accountState")),
          where + "accountState"
      );
      case "memoTransfer" -> assertEquals(
          state.get("requireIncomingTransferMemos"),
          assertInstanceOf(MemoTransfer.class, decoded, where).requireIncomingTransferMemos(),
          where + "requireIncomingTransferMemos"
      );
      case "interestBearingConfig" -> {
        final var config = assertInstanceOf(InterestBearingConfig.class, decoded, where);
        assertZeroable(state.get("rateAuthority"), config.rateAuthority(), where + "rateAuthority");
        assertEquals(u64(state.get("initializationTimestamp")), config.initializationTimestamp(),
            where + "initializationTimestamp");
        assertEquals(u64(state.get("initializationTimestamp")), config.unixTimestamp(),
            where + "unixTimestamp is the same field under its wire name");
        assertEquals(u64(state.get("preUpdateAverageRate")), config.preUpdateAverageRate(),
            where + "preUpdateAverageRate");
        assertEquals(u64(state.get("lastUpdateTimestamp")), config.lastUpdateTimestamp(),
            where + "lastUpdateTimestamp");
        assertEquals(u64(state.get("currentRate")), config.currentRate(), where + "currentRate");
      }
      case "cpiGuard" -> assertEquals(
          state.get("lockCpi"),
          assertInstanceOf(CpiGuard.class, decoded, where).lockCPI(),
          where + "lockCpi"
      );
      case "permanentDelegate" -> assertZeroable(
          state.get("delegate"),
          assertInstanceOf(PermanentDelegate.class, decoded, where).delegate(),
          where + "delegate"
      );
      case "transferHook" -> {
        final var hook = assertInstanceOf(TransferHook.class, decoded, where);
        assertZeroable(state.get("authority"), hook.authority(), where + "authority");
        assertZeroable(state.get("programId"), hook.programId(), where + "programId");
      }
      case "transferHookAccount" -> assertEquals(
          state.get("transferring"),
          assertInstanceOf(TransferHookAccount.class, decoded, where).transferring(),
          where + "transferring"
      );
      case "confidentialTransferFeeConfig" -> {
        final var config = assertInstanceOf(ConfidentialTransferFeeConfig.class, decoded, where);
        assertZeroable(state.get("authority"), config.authority(), where + "authority");
        assertZeroableBytes(state.get("withdrawWithheldAuthorityElgamalPubkey"),
            config.withdrawWithheldAuthorityElgamalPubkey().toByteArray(),
            where + "withdrawWithheldAuthorityElgamalPubkey");
        assertEquals(state.get("harvestToMintEnabled"), config.harvestToMintEnabled(),
            where + "harvestToMintEnabled");
        assertArrayEquals(base64(state.get("withheldAmount")), config.withheldAmount(),
            where + "withheldAmount");
      }
      case "confidentialTransferFeeAmount" -> assertArrayEquals(
          base64(state.get("withheldAmount")),
          assertInstanceOf(ConfidentialTransferFeeAmount.class, decoded, where).withheldAmount(),
          where + "withheldAmount"
      );
      case "metadataPointer" -> {
        final var pointer = assertInstanceOf(MetadataPointer.class, decoded, where);
        assertZeroable(state.get("authority"), pointer.authority(), where + "authority");
        assertZeroable(state.get("metadataAddress"), pointer.metadataAddress(), where + "metadataAddress");
      }
      case "tokenMetadata" -> {
        final var metadata = assertInstanceOf(TokenMetadata.class, decoded, where);
        assertZeroable(state.get("updateAuthority"), metadata.updateAuthority(), where + "updateAuthority");
        assertEquals(string(state.get("mint")), metadata.mint().toBase58(), where + "mint");
        assertEquals(string(state.get("name")), metadata.name(), where + "name");
        assertEquals(string(state.get("symbol")), metadata.symbol(), where + "symbol");
        assertEquals(string(state.get("uri")), metadata.uri(), where + "uri");
        // A Borsh Vec of pairs, so it is ordered and may repeat a key on the wire; the node
        // prints the pairs, this library keeps insertion order in an unmodifiable map.
        final var pairs = array(state.get("additionalMetadata"));
        final var expected = new LinkedHashMap<String, String>();
        for (final var pair : pairs) {
          final var entry = array(pair);
          assertEquals(2, entry.size(), where + "additionalMetadata entries are key/value pairs");
          assertNull(expected.put(string(entry.get(0)), string(entry.get(1))),
              where + "additionalMetadata keys are unique");
        }
        assertEquals(expected, metadata.additionalMetadata(), where + "additionalMetadata");
        assertEquals(
            List.copyOf(expected.keySet()),
            List.copyOf(metadata.additionalMetadata().keySet()),
            where + "additionalMetadata order follows the Borsh Vec"
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> metadata.additionalMetadata().put("k", "v"),
            where + "additionalMetadata is unmodifiable"
        );
      }
      case "groupPointer" -> {
        final var pointer = assertInstanceOf(GroupPointer.class, decoded, where);
        assertZeroable(state.get("authority"), pointer.authority(), where + "authority");
        assertZeroable(state.get("groupAddress"), pointer.groupAddress(), where + "groupAddress");
      }
      case "tokenGroup" -> {
        final var group = assertInstanceOf(TokenGroup.class, decoded, where);
        assertZeroable(state.get("updateAuthority"), group.updateAuthority(), where + "updateAuthority");
        assertEquals(string(state.get("mint")), group.mint().toBase58(), where + "mint");
        assertEquals(u64(state.get("size")), group.size(), where + "size");
        assertEquals(u64(state.get("maxSize")), group.maxSize(), where + "maxSize");
      }
      case "groupMemberPointer" -> {
        final var pointer = assertInstanceOf(GroupMemberPointer.class, decoded, where);
        assertZeroable(state.get("authority"), pointer.authority(), where + "authority");
        assertZeroable(state.get("memberAddress"), pointer.memberAddress(), where + "memberAddress");
      }
      case "tokenGroupMember" -> {
        final var member = assertInstanceOf(TokenGroupMember.class, decoded, where);
        assertEquals(string(state.get("mint")), member.mint().toBase58(), where + "mint");
        assertEquals(string(state.get("group")), member.group().toBase58(), where + "group");
        assertEquals(u64(state.get("memberNumber")), member.memberNumber(), where + "memberNumber");
      }
      case "confidentialMintBurn" -> {
        final var mintBurn = assertInstanceOf(ConfidentialMintBurn.class, decoded, where);
        assertArrayEquals(base64(state.get("confidentialSupply")), mintBurn.confidentialSupply(),
            where + "confidentialSupply");
        assertArrayEquals(base64(state.get("decryptableSupply")), mintBurn.decryptableSupply(),
            where + "decryptableSupply");
        assertArrayEquals(base64(state.get("supplyElgamalPubkey")),
            mintBurn.supplyElGamalPubKey().toByteArray(), where + "supplyElgamalPubkey");
        assertArrayEquals(base64(state.get("pendingBurn")), mintBurn.pendingBurn(), where + "pendingBurn");
      }
      case "scaledUiAmountConfig" -> {
        final var config = assertInstanceOf(ScaledUiAmountConfig.class, decoded, where);
        assertZeroable(state.get("authority"), config.authority(), where + "authority");
        // Printed as a decimal string, not a JSON number: the wire field is an f64.
        assertEquals(Double.parseDouble(string(state.get("multiplier"))), config.multiplier(),
            where + "multiplier");
        assertEquals(u64(state.get("newMultiplierEffectiveTimestamp")),
            config.newMultiplierEffectiveTimestamp(), where + "newMultiplierEffectiveTimestamp");
        assertEquals(Double.parseDouble(string(state.get("newMultiplier"))), config.newMultiplier(),
            where + "newMultiplier");
      }
      case "pausableConfig" -> {
        final var config = assertInstanceOf(PausableConfig.class, decoded, where);
        assertZeroable(state.get("authority"), config.authority(), where + "authority");
        assertEquals(state.get("paused"), config.paused(), where + "paused");
      }
      case "permissionedBurnConfig" -> assertZeroable(
          state.get("authority"),
          assertInstanceOf(PermissionedBurnConfig.class, decoded, where).authority(),
          where + "authority"
      );
      // The four marker extensions carry no value at all, so matching the type is the whole
      // comparison; the node prints them with no "state" member.
      case "immutableOwner", "nonTransferable", "nonTransferableAccount", "pausableAccount" ->
          assertEquals(Map.of(), state, where + "a marker extension has no state");
      default -> fail(where + "no field comparison is written for this extension");
    }
  }

  private static void assertTransferFee(final Map<String, Object> parsed,
                                        final TransferFee fee,
                                        final String where) {
    assertEquals(u64(parsed.get("epoch")), fee.epoch(), where + "epoch");
    assertEquals(u64(parsed.get("maximumFee")), fee.maximumFee(), where + "maximumFee");
    assertEquals(u64(parsed.get("transferFeeBasisPoints")), fee.transferFeeBasisPoints(),
        where + "transferFeeBasisPoints");
  }

  /// A zeroable option: the field is always present on the wire and absence is 32 zero bytes,
  /// which jsonParsed prints as `null` and this library decodes as [PublicKey#NONE]. Asserting
  /// the correspondence both ways is the point — a present key must not be NONE either.
  private static void assertZeroable(final Object parsed, final PublicKey decoded, final String where) {
    assertNotNull(decoded, where + " is a zeroable option and is never null");
    if (parsed == null) {
      assertEquals(PublicKey.NONE, decoded, where + " is absent, so it decodes as PublicKey.NONE");
    } else {
      assertEquals(string(parsed), decoded.toBase58(), where);
      assertNotEquals(PublicKey.NONE, decoded, where + " is present, so it is not PublicKey.NONE");
    }
  }

  /// The same convention for a zeroable ElGamal key, which jsonParsed prints base64 because it
  /// is a ciphertext key rather than an address.
  private static void assertZeroableBytes(final Object parsed, final byte[] decoded, final String where) {
    if (parsed == null) {
      assertArrayEquals(new byte[decoded.length], decoded, where + " is absent, so it decodes as zeros");
    } else {
      assertArrayEquals(base64(parsed), decoded, where);
    }
  }

  /// A `COption` on a base state: the tag decides, and this library represents absence as
  /// `null`. The node omits the member entirely rather than printing `null`.
  private static void assertCOption(final Object parsed, final PublicKey decoded, final String where) {
    if (parsed == null) {
      assertNull(decoded, where + " is absent");
    } else {
      assertEquals(string(parsed), decoded.toBase58(), where);
    }
  }

  private static Object decode(final Fixture fixture) {
    return decode(fixture.address(), fixture.data(), fixture.kind());
  }

  private static Object decode(final String address, final byte[] data, final String kind) {
    final var key = PublicKey.fromBase58Encoded(address);
    return kind.equals("mint") ? Token2022.read(key, data) : Token2022Account.read(key, data);
  }

  /// The parser hands back a `LinkedHashSet`, so iterating it is iterating the TLV chain.
  private static List<TokenExtension> extensionsOf(final Object decoded) {
    return List.copyOf(decoded instanceof Token2022 mint
        ? mint.tokenExtensions()
        : ((Token2022Account) decoded).tokenExtensions());
  }

  private static int length(final Object decoded) {
    return decoded instanceof Token2022 mint ? mint.l() : ((Token2022Account) decoded).l();
  }

  private static int write(final Object decoded, final byte[] out) {
    return decoded instanceof Token2022 mint ? mint.write(out, 0) : ((Token2022Account) decoded).write(out, 0);
  }

  private static String string(final Object json) {
    return assertInstanceOf(String.class, json, "expected a JSON string");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(final Object json) {
    return assertInstanceOf(Map.class, json, "expected a JSON object");
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(final Object json) {
    return assertInstanceOf(List.class, json, "expected a JSON array");
  }

  private static byte[] base64(final Object json) {
    return Base64.getDecoder().decode(string(json));
  }

  /// `u64` crosses the JSON boundary two ways: as a number where it fits the node's own
  /// serializer and as a decimal string where it does not, and either can exceed
  /// [Long#MAX_VALUE]. Both come back as the two's-complement `long` this library uses.
  private static long u64(final Object json) {
    return (json instanceof BigDecimal number
        ? number.toBigIntegerExact()
        : new BigInteger(string(json))).longValue();
  }

  /// A JSON reader for the fixtures' oracle files. sava-core carries no JSON dependency by
  /// design, and this is a test-only reader for committed, already-validated documents:
  /// numbers keep their source text as a [BigDecimal] because a `u64` maximum fee has more
  /// significant digits than a `double` holds.
  private static final class Json {

    private final String src;
    private int at;

    private Json(final String src) {
      this.src = src;
    }

    static Object parse(final String src) {
      final var json = new Json(src);
      json.skipWhitespace();
      final var value = json.value();
      json.skipWhitespace();
      if (json.at != src.length()) {
        throw new IllegalArgumentException("Trailing JSON at offset " + json.at);
      }
      return value;
    }

    private void skipWhitespace() {
      while (at < src.length() && Character.isWhitespace(src.charAt(at))) {
        ++at;
      }
    }

    private Object value() {
      return switch (src.charAt(at)) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> keyword("true", Boolean.TRUE);
        case 'f' -> keyword("false", Boolean.FALSE);
        case 'n' -> keyword("null", null);
        default -> number();
      };
    }

    private Object keyword(final String text, final Object value) {
      if (!src.startsWith(text, at)) {
        throw new IllegalArgumentException("Malformed JSON literal at offset " + at);
      }
      at += text.length();
      return value;
    }

    private Map<String, Object> object() {
      expect('{');
      final var map = new LinkedHashMap<String, Object>();
      skipWhitespace();
      if (src.charAt(at) == '}') {
        ++at;
        return map;
      }
      for (; ; ) {
        skipWhitespace();
        final var key = string();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        if (map.put(key, value()) != null) {
          throw new IllegalArgumentException("Duplicate JSON key " + key);
        }
        skipWhitespace();
        final char delimiter = src.charAt(at++);
        if (delimiter == '}') {
          return map;
        }
        if (delimiter != ',') {
          throw new IllegalArgumentException("Malformed JSON object at offset " + (at - 1));
        }
      }
    }

    private List<Object> array() {
      expect('[');
      final var list = new ArrayList<>();
      skipWhitespace();
      if (src.charAt(at) == ']') {
        ++at;
        return list;
      }
      for (; ; ) {
        skipWhitespace();
        list.add(value());
        skipWhitespace();
        final char delimiter = src.charAt(at++);
        if (delimiter == ']') {
          return list;
        }
        if (delimiter != ',') {
          throw new IllegalArgumentException("Malformed JSON array at offset " + (at - 1));
        }
      }
    }

    private String string() {
      expect('"');
      final var out = new StringBuilder();
      for (; ; ) {
        final char c = src.charAt(at++);
        if (c == '"') {
          return out.toString();
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        final char escape = src.charAt(at++);
        switch (escape) {
          case '"', '\\', '/' -> out.append(escape);
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            out.append((char) Integer.parseInt(src, at, at + 4, 16));
            at += 4;
          }
          default -> throw new IllegalArgumentException("Malformed JSON escape at offset " + (at - 1));
        }
      }
    }

    private BigDecimal number() {
      final int from = at;
      while (at < src.length() && "+-.eE0123456789".indexOf(src.charAt(at)) >= 0) {
        ++at;
      }
      if (from == at) {
        throw new IllegalArgumentException("Malformed JSON value at offset " + at);
      }
      return new BigDecimal(src.substring(from, at));
    }

    private void expect(final char expected) {
      if (src.charAt(at++) != expected) {
        throw new IllegalArgumentException("Expected '" + expected + "' at offset " + (at - 1));
      }
    }
  }
}
