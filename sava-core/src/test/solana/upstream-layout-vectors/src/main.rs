use {
    bytemuck::Pod,
    sha2::{Digest, Sha256},
    solana_address::Address,
    solana_epoch_rewards::EpochRewards,
    solana_epoch_schedule::EpochSchedule,
    solana_hash::Hash,
    solana_rent::Rent,
    spl_token_2022_interface::{
        extension::{
            confidential_mint_burn::ConfidentialMintBurn,
            confidential_transfer::{ConfidentialTransferAccount, ConfidentialTransferMint},
            confidential_transfer_fee::{
                ConfidentialTransferFeeAmount, ConfidentialTransferFeeConfig,
            },
            cpi_guard::CpiGuard,
            default_account_state::DefaultAccountState,
            group_member_pointer::GroupMemberPointer,
            group_pointer::GroupPointer,
            immutable_owner::ImmutableOwner,
            interest_bearing_mint::InterestBearingConfig,
            memo_transfer::MemoTransfer,
            metadata_pointer::MetadataPointer,
            mint_close_authority::MintCloseAuthority,
            non_transferable::{NonTransferable, NonTransferableAccount},
            pausable::{PausableAccount, PausableConfig},
            permanent_delegate::PermanentDelegate,
            permissioned_burn::PermissionedBurnConfig,
            scaled_ui_amount::ScaledUiAmountConfig,
            transfer_fee::{TransferFeeAmount, TransferFeeConfig},
            transfer_hook::{TransferHook, TransferHookAccount},
            BaseStateWithExtensions, Extension, ExtensionType, Length,
        },
        state::{Account, Mint},
    },
    spl_token_group_interface::state::{TokenGroup, TokenGroupMember},
    spl_token_metadata_interface::state::TokenMetadata,
    spl_type_length_value::variable_len_pack::VariableLenPack,
    std::{
        env,
        fmt::Write as _,
        fs,
        mem::size_of,
        path::{Path, PathBuf},
    },
};

const FORMAT: &str = "sava-solana-upstream-layout-v1";
const AGAVE: &str = "b9687a87037c787fa3257dc62fa00ff4708f1879";
const AGAVE_CARGO_LOCK_SHA256: &str =
    "449a23496ae1776aa5109c9f86773885e8e839b3f4a1077be2c7b8698f8b5404";
const SOLANA_SDK: &str = "7e8f4a52f044e7729406bd24ae7c586de92e7f58";
const TOOLCHAIN: &str = "1.97.1";

const TOKEN_2022_CHECKSUM: &str =
    "821d96d034ea31c4965d182c742153c491ae0abee531331b55771086c5030d86";
const TOKEN_GROUP_CHECKSUM: &str =
    "841cbd6f2322d02719be4da1affedbe6495b1048b7b985ec9796032564026e22";
const TOKEN_METADATA_CHECKSUM: &str =
    "3d3d96f175e7022ff200464dfa75a3708a4e9b70c83c4ecd04fe52ee479f4fef";
const TYPE_LENGTH_VALUE_CHECKSUM: &str =
    "2504631748c48d2a937414d64a12dcac4588d34bd07d355d648619c189d29435";
const EPOCH_REWARDS_CHECKSUM: &str =
    "0788d74ee15778deecaa15ed1a1e37727ba954f86cbc35225450a1f2b5012969";
const EPOCH_SCHEDULE_CHECKSUM: &str =
    "a1633cfd10cde127f2caf8f12021b4f8e9a425e7e4eea4326e428c422376d6fd";
const RENT_CHECKSUM: &str = "dc016b348926395ba01f8288cdf5da25fc30f5a0806028b32d5e5b3147b10bf9";
const WINCODE_CHECKSUM: &str = "bfc6339f1ba427bf7ad7c42403b28e524832ba2ddb5eef1bb2cc3b85db6b7b75";
const SOLANA_ADDRESS_CHECKSUM: &str =
    "01332a01c0a3098404d55a724c8d9a92aed4a50fe40a7dd0c7a51e29274c14de";
const SOLANA_HASH_CHECKSUM: &str =
    "0df9b01495ed31100aca97a7f5862d5e19ab1636d60d1a9f02391408dd9dec84";
const BYTEMUCK_CHECKSUM: &str = "95832e849adfb21180ccb6826a99da14e5d266ae5c2e668e1602cf234f153797";
const SHA2_CHECKSUM: &str = "a7507d819769d01a365ab707794a4084392c824f54a7a6a7862f8c3d0892b283";

const EXTENSION_TYPES: [ExtensionType; 29] = [
    ExtensionType::Uninitialized,
    ExtensionType::TransferFeeConfig,
    ExtensionType::TransferFeeAmount,
    ExtensionType::MintCloseAuthority,
    ExtensionType::ConfidentialTransferMint,
    ExtensionType::ConfidentialTransferAccount,
    ExtensionType::DefaultAccountState,
    ExtensionType::ImmutableOwner,
    ExtensionType::MemoTransfer,
    ExtensionType::NonTransferable,
    ExtensionType::InterestBearingConfig,
    ExtensionType::CpiGuard,
    ExtensionType::PermanentDelegate,
    ExtensionType::NonTransferableAccount,
    ExtensionType::TransferHook,
    ExtensionType::TransferHookAccount,
    ExtensionType::ConfidentialTransferFeeConfig,
    ExtensionType::ConfidentialTransferFeeAmount,
    ExtensionType::MetadataPointer,
    ExtensionType::TokenMetadata,
    ExtensionType::GroupPointer,
    ExtensionType::TokenGroup,
    ExtensionType::GroupMemberPointer,
    ExtensionType::TokenGroupMember,
    ExtensionType::ConfidentialMintBurn,
    ExtensionType::ScaledUiAmount,
    ExtensionType::Pausable,
    ExtensionType::PausableAccount,
    ExtensionType::PermissionedBurn,
];

struct Tlv<'a>(&'a [u8]);

impl BaseStateWithExtensions<Mint> for Tlv<'_> {
    fn get_tlv_data(&self) -> &[u8] {
        self.0
    }
}

impl BaseStateWithExtensions<Account> for Tlv<'_> {
    fn get_tlv_data(&self) -> &[u8] {
        self.0
    }
}

#[derive(Clone)]
struct TokenRow {
    ordinal: u16,
    name: String,
    account_type: &'static str,
    length_kind: &'static str,
    value: Vec<u8>,
    exact: bool,
    short: Option<bool>,
    long: Option<bool>,
}

struct TokenBoolRow {
    extension_type: ExtensionType,
    name: &'static str,
    field: &'static str,
    offset: usize,
    value: Vec<u8>,
    rust_value: bool,
}

fn main() -> Result<(), String> {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    let mode = match arguments.as_slice() {
        [mode] if mode == "--write" || mode == "--check" => mode.as_str(),
        _ => return Err("usage: cargo run --locked --release -- --write|--check".into()),
    };

    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let lock_path = manifest_dir.join("Cargo.lock");
    let lock = fs::read_to_string(&lock_path)
        .map_err(|error| format!("failed to read {}: {error}", lock_path.display()))?;
    let manifest = read_bytes(&manifest_dir.join("Cargo.toml"))?;
    let generator = read_bytes(&manifest_dir.join("src/main.rs"))?;
    let toolchain = read_bytes(&manifest_dir.join("rust-toolchain.toml"))?;

    for (name, version, checksum) in [
        ("spl-token-2022-interface", "3.1.1", TOKEN_2022_CHECKSUM),
        ("spl-token-group-interface", "0.7.2", TOKEN_GROUP_CHECKSUM),
        (
            "spl-token-metadata-interface",
            "1.0.1",
            TOKEN_METADATA_CHECKSUM,
        ),
        ("spl-type-length-value", "0.9.1", TYPE_LENGTH_VALUE_CHECKSUM),
        ("solana-epoch-schedule", "3.3.0", EPOCH_SCHEDULE_CHECKSUM),
        ("solana-epoch-rewards", "3.2.0", EPOCH_REWARDS_CHECKSUM),
        ("solana-rent", "4.4.0", RENT_CHECKSUM),
        ("wincode", "0.6.1", WINCODE_CHECKSUM),
        ("solana-address", "2.7.0", SOLANA_ADDRESS_CHECKSUM),
        ("solana-hash", "4.6.0", SOLANA_HASH_CHECKSUM),
        ("bytemuck", "1.25.2", BYTEMUCK_CHECKSUM),
        ("sha2", "0.10.9", SHA2_CHECKSUM),
    ] {
        assert_locked_package(&lock, name, version, checksum)?;
    }

    let provenance = provenance(&lock, &manifest, &generator, &toolchain);
    let resources = manifest_dir.join("../../resources/upstream");
    let token = generate_token_fixture(&provenance)?;
    let token_bools = generate_token_bool_fixture(&provenance)?;
    let metadata = generate_metadata_fixture(&provenance)?;
    let sysvars = generate_sysvar_fixture(&provenance)?;
    let fixtures = [
        (resources.join("solana-token2022-extensions.tsv"), token),
        (resources.join("solana-token2022-bools.tsv"), token_bools),
        (resources.join("solana-token2022-metadata.tsv"), metadata),
        (resources.join("solana-sysvars.tsv"), sysvars),
    ];
    for (path, fixture) in fixtures {
        match mode {
            "--write" => write_fixture(&path, &fixture)?,
            "--check" => check_fixture(&path, &fixture)?,
            _ => unreachable!(),
        }
    }
    Ok(())
}

fn generate_token_fixture(provenance: &str) -> Result<String, String> {
    let mut output = header(
        provenance,
        "every Token-2022 extension ordinal and fixed Rust TLV value length matches Sava",
        29,
    );
    writeln!(
        output,
        "ordinal\tname\taccount_type\tlength_kind\tvalue_length\tvalue_hex\tvalue_sha256\trust_exact_accepts\trust_short_accepts\trust_long_accepts"
    )
    .unwrap();

    for row in token_rows()? {
        writeln!(
            output,
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            row.ordinal,
            row.name,
            row.account_type,
            row.length_kind,
            row.value.len(),
            encode_hex(&row.value),
            sha256_hex(&row.value),
            row.exact,
            optional_bool(row.short),
            optional_bool(row.long),
        )
        .unwrap();
    }
    Ok(output)
}

fn generate_token_bool_fixture(provenance: &str) -> Result<String, String> {
    let rows = token_bool_rows()?;
    let mut output = header(
        provenance,
        "every nonzero solana-zero-copy Bool byte in Token-2022 decodes as true",
        rows.len(),
    );
    writeln!(
        output,
        "ordinal\tname\tfield\tbool_offset\tvalue_hex\trust_value"
    )
    .unwrap();
    for row in rows {
        writeln!(
            output,
            "{}\t{}\t{}\t{}\t{}\t{}",
            u16::from(row.extension_type),
            row.name,
            row.field,
            row.offset,
            encode_hex(&row.value),
            row.rust_value,
        )
        .unwrap();
    }
    Ok(output)
}

fn token_bool_rows() -> Result<Vec<TokenBoolRow>, String> {
    let mut rows = Vec::with_capacity(9);

    let value = noncanonical_bool_value::<ConfidentialTransferMint>(32);
    let parsed = bytemuck::pod_read_unaligned::<ConfidentialTransferMint>(&value);
    rows.push(TokenBoolRow {
        extension_type: ExtensionType::ConfidentialTransferMint,
        name: "ConfidentialTransferMint",
        field: "autoApproveNewAccounts",
        offset: 32,
        rust_value: bool::from(parsed.auto_approve_new_accounts),
        value,
    });

    for (field, offset, value, rust_value) in {
        let approved = noncanonical_bool_value::<ConfidentialTransferAccount>(0);
        let approved_value = bool::from(
            bytemuck::pod_read_unaligned::<ConfidentialTransferAccount>(&approved).approved,
        );
        let confidential = noncanonical_bool_value::<ConfidentialTransferAccount>(261);
        let confidential_value = bool::from(
            bytemuck::pod_read_unaligned::<ConfidentialTransferAccount>(&confidential)
                .allow_confidential_credits,
        );
        let non_confidential = noncanonical_bool_value::<ConfidentialTransferAccount>(262);
        let non_confidential_value = bool::from(
            bytemuck::pod_read_unaligned::<ConfidentialTransferAccount>(&non_confidential)
                .allow_non_confidential_credits,
        );
        [
            ("approved", 0, approved, approved_value),
            (
                "allowConfidentialCredits",
                261,
                confidential,
                confidential_value,
            ),
            (
                "allowNonConfidentialCredits",
                262,
                non_confidential,
                non_confidential_value,
            ),
        ]
    } {
        rows.push(TokenBoolRow {
            extension_type: ExtensionType::ConfidentialTransferAccount,
            name: "ConfidentialTransferAccount",
            field,
            offset,
            value,
            rust_value,
        });
    }

    let value = noncanonical_bool_value::<ConfidentialTransferFeeConfig>(64);
    let parsed = bytemuck::pod_read_unaligned::<ConfidentialTransferFeeConfig>(&value);
    rows.push(TokenBoolRow {
        extension_type: ExtensionType::ConfidentialTransferFeeConfig,
        name: "ConfidentialTransferFeeConfig",
        field: "harvestToMintEnabled",
        offset: 64,
        rust_value: bool::from(parsed.harvest_to_mint_enabled),
        value,
    });

    let value = noncanonical_bool_value::<MemoTransfer>(0);
    let parsed = bytemuck::pod_read_unaligned::<MemoTransfer>(&value);
    rows.push(TokenBoolRow {
        extension_type: ExtensionType::MemoTransfer,
        name: "MemoTransfer",
        field: "requireIncomingTransferMemos",
        offset: 0,
        rust_value: bool::from(parsed.require_incoming_transfer_memos),
        value,
    });

    let value = noncanonical_bool_value::<CpiGuard>(0);
    let parsed = bytemuck::pod_read_unaligned::<CpiGuard>(&value);
    rows.push(TokenBoolRow {
        extension_type: ExtensionType::CpiGuard,
        name: "CpiGuard",
        field: "lockCpi",
        offset: 0,
        rust_value: bool::from(parsed.lock_cpi),
        value,
    });

    let value = noncanonical_bool_value::<TransferHookAccount>(0);
    let parsed = bytemuck::pod_read_unaligned::<TransferHookAccount>(&value);
    rows.push(TokenBoolRow {
        extension_type: ExtensionType::TransferHookAccount,
        name: "TransferHookAccount",
        field: "transferring",
        offset: 0,
        rust_value: bool::from(parsed.transferring),
        value,
    });

    let value = noncanonical_bool_value::<PausableConfig>(32);
    let parsed = bytemuck::pod_read_unaligned::<PausableConfig>(&value);
    rows.push(TokenBoolRow {
        extension_type: ExtensionType::Pausable,
        name: "PausableConfig",
        field: "paused",
        offset: 32,
        rust_value: bool::from(parsed.paused),
        value,
    });

    for row in &rows {
        if !accepts(row.extension_type, &row.value) {
            return Err(format!(
                "Rust rejected noncanonical Bool fixture for {}.{}",
                row.name, row.field
            ));
        }
        if !row.rust_value {
            return Err(format!(
                "Rust decoded nonzero Bool as false for {}.{}",
                row.name, row.field
            ));
        }
    }
    Ok(rows)
}

fn noncanonical_bool_value<T>(offset: usize) -> Vec<u8> {
    let mut value = vec![0; size_of::<T>()];
    value[offset] = 2;
    value
}

fn token_rows() -> Result<Vec<TokenRow>, String> {
    let metadata = metadata_value("☉sol", "Σ", "https://例.invalid/☉", vec![("ключ", "值")])?;
    let mut rows = Vec::with_capacity(EXTENSION_TYPES.len());
    for (expected_ordinal, extension_type) in EXTENSION_TYPES.into_iter().enumerate() {
        let ordinal = u16::from(extension_type);
        if usize::from(ordinal) != expected_ordinal {
            return Err(format!(
                "extension {extension_type:?} has ordinal {ordinal}, expected {expected_ordinal}"
            ));
        }
        let (length_kind, value) = if extension_type == ExtensionType::TokenMetadata {
            ("variable", metadata.clone())
        } else {
            let mut value = vec![0_u8; fixed_len(extension_type)?];
            if !value.is_empty() {
                value[0] = 1;
            }
            ("fixed", value)
        };
        let exact = accepts(extension_type, &value);
        if !exact {
            return Err(format!(
                "Rust rejected generated exact value for {extension_type:?}"
            ));
        }
        let (short, long) = if length_kind == "fixed" {
            let short =
                (!value.is_empty()).then(|| accepts(extension_type, &value[..value.len() - 1]));
            let mut longer = value.clone();
            longer.push(0xa5);
            (short, Some(accepts(extension_type, &longer)))
        } else {
            (None, None)
        };
        rows.push(TokenRow {
            ordinal,
            name: format!("{extension_type:?}"),
            account_type: match extension_type.get_account_type() {
                spl_token_2022_interface::extension::AccountType::Uninitialized => "Uninitialized",
                spl_token_2022_interface::extension::AccountType::Mint => "Mint",
                spl_token_2022_interface::extension::AccountType::Account => "Account",
            },
            length_kind,
            value,
            exact,
            short,
            long,
        });
    }
    Ok(rows)
}

fn fixed_len(extension_type: ExtensionType) -> Result<usize, String> {
    Ok(match extension_type {
        ExtensionType::Uninitialized => 0,
        ExtensionType::TransferFeeConfig => size_of::<TransferFeeConfig>(),
        ExtensionType::TransferFeeAmount => size_of::<TransferFeeAmount>(),
        ExtensionType::MintCloseAuthority => size_of::<MintCloseAuthority>(),
        ExtensionType::ConfidentialTransferMint => size_of::<ConfidentialTransferMint>(),
        ExtensionType::ConfidentialTransferAccount => size_of::<ConfidentialTransferAccount>(),
        ExtensionType::DefaultAccountState => size_of::<DefaultAccountState>(),
        ExtensionType::ImmutableOwner => size_of::<ImmutableOwner>(),
        ExtensionType::MemoTransfer => size_of::<MemoTransfer>(),
        ExtensionType::NonTransferable => size_of::<NonTransferable>(),
        ExtensionType::InterestBearingConfig => size_of::<InterestBearingConfig>(),
        ExtensionType::CpiGuard => size_of::<CpiGuard>(),
        ExtensionType::PermanentDelegate => size_of::<PermanentDelegate>(),
        ExtensionType::NonTransferableAccount => size_of::<NonTransferableAccount>(),
        ExtensionType::TransferHook => size_of::<TransferHook>(),
        ExtensionType::TransferHookAccount => size_of::<TransferHookAccount>(),
        ExtensionType::ConfidentialTransferFeeConfig => size_of::<ConfidentialTransferFeeConfig>(),
        ExtensionType::ConfidentialTransferFeeAmount => size_of::<ConfidentialTransferFeeAmount>(),
        ExtensionType::MetadataPointer => size_of::<MetadataPointer>(),
        ExtensionType::TokenMetadata => return Err("TokenMetadata is variable length".into()),
        ExtensionType::GroupPointer => size_of::<GroupPointer>(),
        ExtensionType::TokenGroup => size_of::<TokenGroup>(),
        ExtensionType::GroupMemberPointer => size_of::<GroupMemberPointer>(),
        ExtensionType::TokenGroupMember => size_of::<TokenGroupMember>(),
        ExtensionType::ConfidentialMintBurn => size_of::<ConfidentialMintBurn>(),
        ExtensionType::ScaledUiAmount => size_of::<ScaledUiAmountConfig>(),
        ExtensionType::Pausable => size_of::<PausableConfig>(),
        ExtensionType::PausableAccount => size_of::<PausableAccount>(),
        ExtensionType::PermissionedBurn => size_of::<PermissionedBurnConfig>(),
    })
}

fn accepts(extension_type: ExtensionType, value: &[u8]) -> bool {
    let tlv = tlv(extension_type, value);
    match extension_type {
        ExtensionType::Uninitialized => {
            <Tlv<'_> as BaseStateWithExtensions<Mint>>::get_extension_types(&Tlv(&tlv))
                .is_ok_and(|types| types.is_empty())
        }
        ExtensionType::TransferFeeConfig => mint_accepts::<TransferFeeConfig>(&tlv),
        ExtensionType::TransferFeeAmount => account_accepts::<TransferFeeAmount>(&tlv),
        ExtensionType::MintCloseAuthority => mint_accepts::<MintCloseAuthority>(&tlv),
        ExtensionType::ConfidentialTransferMint => mint_accepts::<ConfidentialTransferMint>(&tlv),
        ExtensionType::ConfidentialTransferAccount => {
            account_accepts::<ConfidentialTransferAccount>(&tlv)
        }
        ExtensionType::DefaultAccountState => mint_accepts::<DefaultAccountState>(&tlv),
        ExtensionType::ImmutableOwner => account_accepts::<ImmutableOwner>(&tlv),
        ExtensionType::MemoTransfer => account_accepts::<MemoTransfer>(&tlv),
        ExtensionType::NonTransferable => mint_accepts::<NonTransferable>(&tlv),
        ExtensionType::InterestBearingConfig => mint_accepts::<InterestBearingConfig>(&tlv),
        ExtensionType::CpiGuard => account_accepts::<CpiGuard>(&tlv),
        ExtensionType::PermanentDelegate => mint_accepts::<PermanentDelegate>(&tlv),
        ExtensionType::NonTransferableAccount => account_accepts::<NonTransferableAccount>(&tlv),
        ExtensionType::TransferHook => mint_accepts::<TransferHook>(&tlv),
        ExtensionType::TransferHookAccount => account_accepts::<TransferHookAccount>(&tlv),
        ExtensionType::ConfidentialTransferFeeConfig => {
            mint_accepts::<ConfidentialTransferFeeConfig>(&tlv)
        }
        ExtensionType::ConfidentialTransferFeeAmount => {
            account_accepts::<ConfidentialTransferFeeAmount>(&tlv)
        }
        ExtensionType::MetadataPointer => mint_accepts::<MetadataPointer>(&tlv),
        ExtensionType::TokenMetadata => {
            <Tlv<'_> as BaseStateWithExtensions<Mint>>::get_variable_len_extension::<TokenMetadata>(
                &Tlv(&tlv),
            )
            .is_ok()
        }
        ExtensionType::GroupPointer => mint_accepts::<GroupPointer>(&tlv),
        ExtensionType::TokenGroup => mint_accepts::<TokenGroup>(&tlv),
        ExtensionType::GroupMemberPointer => mint_accepts::<GroupMemberPointer>(&tlv),
        ExtensionType::TokenGroupMember => mint_accepts::<TokenGroupMember>(&tlv),
        ExtensionType::ConfidentialMintBurn => mint_accepts::<ConfidentialMintBurn>(&tlv),
        ExtensionType::ScaledUiAmount => mint_accepts::<ScaledUiAmountConfig>(&tlv),
        ExtensionType::Pausable => mint_accepts::<PausableConfig>(&tlv),
        ExtensionType::PausableAccount => account_accepts::<PausableAccount>(&tlv),
        ExtensionType::PermissionedBurn => mint_accepts::<PermissionedBurnConfig>(&tlv),
    }
}

fn mint_accepts<V: Extension + Pod>(tlv: &[u8]) -> bool {
    <Tlv<'_> as BaseStateWithExtensions<Mint>>::get_extension::<V>(&Tlv(tlv)).is_ok()
}

fn account_accepts<V: Extension + Pod>(tlv: &[u8]) -> bool {
    <Tlv<'_> as BaseStateWithExtensions<Account>>::get_extension::<V>(&Tlv(tlv)).is_ok()
}

fn tlv(extension_type: ExtensionType, value: &[u8]) -> Vec<u8> {
    let length = u16::try_from(value.len()).expect("fixture TLV value fits u16");
    let mut tlv = Vec::with_capacity(4 + value.len());
    tlv.extend_from_slice(&u16::from(extension_type).to_le_bytes());
    tlv.extend_from_slice(&length.to_le_bytes());
    tlv.extend_from_slice(value);
    tlv
}

fn generate_metadata_fixture(provenance: &str) -> Result<String, String> {
    let cases = [
        ("empty", "", 0_usize, "", "", Vec::new(), true),
        (
            "unicode",
            "☉sol",
            1,
            "Σ",
            "https://例.invalid/☉",
            vec![("ключ", "值")],
            true,
        ),
        ("u16_max", "x", 65_455, "", "", Vec::new(), true),
        ("u16_overflow", "x", 65_456, "", "", Vec::new(), false),
    ];
    let mut output = header(
        provenance,
        "TokenMetadata Borsh bytes and the Token-2022 TLV u16 length boundary match Rust",
        cases.len(),
    );
    writeln!(
        output,
        "id\tname_unit_utf8_hex\tname_repetitions\tsymbol_utf8_hex\turi_utf8_hex\textra_key_utf8_hex\textra_value_utf8_hex\tpacked_length\tpacked_sha256\tvalue_hex\ttlv_u16_fits\trust_round_trip"
    )
    .unwrap();
    for (id, name_unit, repetitions, symbol, uri, extras, expected_fits) in cases {
        let name = name_unit.repeat(repetitions);
        let value = metadata_value(&name, symbol, uri, extras.clone())?;
        let unpacked = TokenMetadata::unpack_from_slice(&value)
            .map_err(|error| format!("failed to unpack {id}: {error:?}"))?;
        let round_trip = unpacked.name == name
            && unpacked.symbol == symbol
            && unpacked.uri == uri
            && unpacked.additional_metadata
                == extras
                    .iter()
                    .map(|(key, value)| ((*key).to_owned(), (*value).to_owned()))
                    .collect::<Vec<_>>();
        let fits = Length::try_from(value.len()).is_ok();
        if fits != expected_fits {
            return Err(format!(
                "unexpected TLV length verdict for {id}: {}",
                value.len()
            ));
        }
        let (extra_key, extra_value) = extras.first().copied().unwrap_or(("", ""));
        let value_hex = if value.len() <= 4_096 {
            encode_hex(&value)
        } else {
            "-".into()
        };
        writeln!(
            output,
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            id,
            encode_hex(name_unit.as_bytes()),
            repetitions,
            encode_hex(symbol.as_bytes()),
            encode_hex(uri.as_bytes()),
            encode_hex(extra_key.as_bytes()),
            encode_hex(extra_value.as_bytes()),
            value.len(),
            sha256_hex(&value),
            value_hex,
            fits,
            round_trip,
        )
        .unwrap();
    }
    Ok(output)
}

fn metadata_value(
    name: &str,
    symbol: &str,
    uri: &str,
    extras: Vec<(&str, &str)>,
) -> Result<Vec<u8>, String> {
    let metadata = TokenMetadata {
        update_authority: Address::new_from_array([0x11; 32]).into(),
        mint: Address::new_from_array([0x22; 32]),
        name: name.to_owned(),
        symbol: symbol.to_owned(),
        uri: uri.to_owned(),
        additional_metadata: extras
            .into_iter()
            .map(|(key, value)| (key.to_owned(), value.to_owned()))
            .collect(),
    };
    let length = metadata
        .get_packed_len()
        .map_err(|error| format!("failed to size metadata: {error:?}"))?;
    let mut value = vec![0_u8; length];
    metadata
        .pack_into_slice(&mut value)
        .map_err(|error| format!("failed to pack metadata: {error:?}"))?;
    Ok(value)
}

fn generate_sysvar_fixture(provenance: &str) -> Result<String, String> {
    let epoch_rows = [
        EpochRewards {
            distribution_starting_block_height: 0x0102_0304_0506_0708,
            num_partitions: 0x1112_1314_1516_1718,
            parent_blockhash: Hash::new_from_array(core::array::from_fn(|index| index as u8)),
            total_points: 1_u128 << 127,
            total_rewards: 0x2122_2324_2526_2728,
            distributed_rewards: 0x3132_3334_3536_3738,
            active: true,
        },
        EpochRewards {
            distribution_starting_block_height: 9,
            num_partitions: 7,
            parent_blockhash: Hash::new_from_array([0xa5; 32]),
            total_points: u128::MAX,
            total_rewards: 101,
            distributed_rewards: 99,
            active: false,
        },
    ];
    let rent_rows = rent_rows();
    let invalid_epoch_rewards_bool = {
        let mut wire = wincode::serialize(&epoch_rows[0])
            .map_err(|error| format!("failed to serialize epoch rewards bool probe: {error:?}"))?;
        *wire.last_mut().ok_or("empty EpochRewards wire")? = 2;
        let rejected = wincode::deserialize::<EpochRewards>(&wire).is_err();
        (wire, rejected)
    };
    let epoch_schedule = EpochSchedule::custom(432_000, 432_000, true);
    let invalid_epoch_schedule_bool = {
        let mut wire = wincode::serialize(&epoch_schedule)
            .map_err(|error| format!("failed to serialize epoch schedule bool probe: {error:?}"))?;
        wire[16] = 2;
        let rejected = wincode::deserialize::<EpochSchedule>(&wire).is_err();
        (wire, rejected)
    };
    let mut output = header(
        provenance,
        "EpochRewards u128, strict wincode bools, and Rent boundaries match current Solana APIs",
        epoch_rows.len() + rent_rows.len() + 2,
    );
    writeln!(output, "[epoch_rewards]").unwrap();
    writeln!(
        output,
        "id\tdistribution_starting_block_height\tnum_partitions\tparent_blockhash_hex\ttotal_points\ttotal_rewards\tdistributed_rewards\tactive\twire_hex"
    )
    .unwrap();
    for (index, rewards) in epoch_rows.iter().enumerate() {
        let wire = wincode::serialize(rewards)
            .map_err(|error| format!("failed to serialize epoch rewards: {error:?}"))?;
        if wire.len() != solana_epoch_rewards::SIZE {
            return Err(format!("EpochRewards serialized to {} bytes", wire.len()));
        }
        writeln!(
            output,
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            if index == 0 { "high_bit" } else { "max" },
            rewards.distribution_starting_block_height,
            rewards.num_partitions,
            encode_hex(rewards.parent_blockhash.as_ref()),
            rewards.total_points,
            rewards.total_rewards,
            rewards.distributed_rewards,
            rewards.active,
            encode_hex(&wire),
        )
        .unwrap();
    }
    writeln!(output, "[invalid_bools]").unwrap();
    writeln!(output, "type\tfield\tinvalid_byte\trust_rejects\twire_hex").unwrap();
    writeln!(
        output,
        "EpochRewards\tactive\t2\t{}\t{}",
        invalid_epoch_rewards_bool.1,
        encode_hex(&invalid_epoch_rewards_bool.0),
    )
    .unwrap();
    writeln!(
        output,
        "EpochSchedule\twarmup\t2\t{}\t{}",
        invalid_epoch_schedule_bool.1,
        encode_hex(&invalid_epoch_schedule_bool.0),
    )
    .unwrap();
    writeln!(output, "[rent]").unwrap();
    writeln!(
        output,
        "id\tlamports_per_byte\texemption_threshold_bits_hex\tburn_percent\tdata_length\tminimum_balance\twire_hex"
    )
    .unwrap();
    for row in rent_rows {
        #[allow(deprecated)]
        let rent = Rent {
            lamports_per_byte: row.lamports_per_byte,
            exemption_threshold: row.threshold.to_le_bytes(),
            burn_percent: row.burn_percent,
        };
        let minimum = rent.try_minimum_balance(row.data_length);
        if minimum != row.expected {
            return Err(format!(
                "unexpected rent result for {}: {minimum:?}, expected {:?}",
                row.id, row.expected
            ));
        }
        let wire = wincode::serialize(&rent)
            .map_err(|error| format!("failed to serialize rent: {error:?}"))?;
        if wire.len() != solana_rent::SIZE {
            return Err(format!("Rent serialized to {} bytes", wire.len()));
        }
        writeln!(
            output,
            "{}\t{}\t{}\t{}\t{}\t{}\t{}",
            row.id,
            row.lamports_per_byte,
            encode_hex(&row.threshold.to_le_bytes()),
            row.burn_percent,
            row.data_length,
            row.expected
                .map(|value| value.to_string())
                .unwrap_or_else(|| "none".into()),
            encode_hex(&wire),
        )
        .unwrap();
    }
    Ok(output)
}

struct RentRow {
    id: &'static str,
    lamports_per_byte: u64,
    threshold: f64,
    burn_percent: u8,
    data_length: usize,
    expected: Option<u64>,
}

fn rent_rows() -> Vec<RentRow> {
    const MAX_DATA: usize = 10 * 1024 * 1024;
    const SIMD_MAX: u64 = 1_759_197_129_867;
    const CURRENT_MAX: u64 = 879_598_564_933;
    vec![
        RentRow {
            id: "one_zero",
            lamports_per_byte: 6_960,
            threshold: 1.0,
            burn_percent: 50,
            data_length: 0,
            expected: Some(890_880),
        },
        RentRow {
            id: "one_max_data",
            lamports_per_byte: 6_960,
            threshold: 1.0,
            burn_percent: 50,
            data_length: MAX_DATA,
            expected: Some((MAX_DATA as u64 + 128) * 6_960),
        },
        RentRow {
            id: "two_zero",
            lamports_per_byte: 6_960,
            threshold: 2.0,
            burn_percent: 50,
            data_length: 0,
            expected: Some(1_781_760),
        },
        RentRow {
            id: "two_max_data",
            lamports_per_byte: 6_960,
            threshold: 2.0,
            burn_percent: 50,
            data_length: MAX_DATA,
            expected: Some(2 * (MAX_DATA as u64 + 128) * 6_960),
        },
        RentRow {
            id: "data_too_large",
            lamports_per_byte: 6_960,
            threshold: 2.0,
            burn_percent: 50,
            data_length: MAX_DATA + 1,
            expected: None,
        },
        RentRow {
            id: "one_max_rate",
            lamports_per_byte: SIMD_MAX,
            threshold: 1.0,
            burn_percent: 17,
            data_length: MAX_DATA,
            expected: Some((MAX_DATA as u64 + 128) * SIMD_MAX),
        },
        RentRow {
            id: "one_rate_overflow",
            lamports_per_byte: SIMD_MAX + 1,
            threshold: 1.0,
            burn_percent: 17,
            data_length: 0,
            expected: None,
        },
        RentRow {
            id: "two_max_rate",
            lamports_per_byte: CURRENT_MAX,
            threshold: 2.0,
            burn_percent: 83,
            data_length: MAX_DATA,
            expected: Some(2 * (MAX_DATA as u64 + 128) * CURRENT_MAX),
        },
        RentRow {
            id: "two_rate_overflow",
            lamports_per_byte: CURRENT_MAX + 1,
            threshold: 2.0,
            burn_percent: 83,
            data_length: 0,
            expected: None,
        },
        RentRow {
            id: "fallback_unsigned_high_bit",
            lamports_per_byte: 0x8000_0000_0000_0001,
            threshold: 0.5,
            burn_percent: 23,
            data_length: 1,
            expected: Some(4_611_686_018_427_387_904),
        },
        RentRow {
            id: "fallback_positive_saturation",
            lamports_per_byte: u64::MAX,
            threshold: 3.0,
            burn_percent: 29,
            data_length: 0,
            expected: Some(u64::MAX),
        },
        RentRow {
            id: "fallback_negative",
            lamports_per_byte: 6_960,
            threshold: -0.5,
            burn_percent: 31,
            data_length: 0,
            expected: Some(0),
        },
        RentRow {
            id: "fallback_nan",
            lamports_per_byte: 6_960,
            threshold: f64::NAN,
            burn_percent: 37,
            data_length: 0,
            expected: Some(0),
        },
        RentRow {
            id: "fallback_addition_boundary",
            lamports_per_byte: 1,
            threshold: 0.5,
            burn_percent: 41,
            data_length: 1,
            expected: Some(64),
        },
        RentRow {
            id: "fallback_zero",
            lamports_per_byte: 1,
            threshold: 0.0,
            burn_percent: 43,
            data_length: 0,
            expected: Some(0),
        },
        RentRow {
            id: "fallback_exact_one",
            lamports_per_byte: 1,
            threshold: 1.0 / 128.0,
            burn_percent: 47,
            data_length: 0,
            expected: Some(1),
        },
        RentRow {
            id: "fallback_exact_signed_boundary",
            lamports_per_byte: 1,
            threshold: (1_u64 << 56) as f64,
            burn_percent: 53,
            data_length: 0,
            expected: Some(1_u64 << 63),
        },
        RentRow {
            id: "fallback_upper_half",
            lamports_per_byte: 1,
            threshold: (1_u64 << 56) as f64 + 16.0,
            burn_percent: 59,
            data_length: 0,
            expected: Some((1_u64 << 63) + 2_048),
        },
        RentRow {
            id: "fallback_largest_double_below_u64_limit",
            lamports_per_byte: 1,
            threshold: (1_u64 << 57) as f64 - 16.0,
            burn_percent: 61,
            data_length: 0,
            expected: Some(u64::MAX - 2_047),
        },
        RentRow {
            id: "fallback_exact_u64_limit",
            lamports_per_byte: 1,
            threshold: (1_u64 << 57) as f64,
            burn_percent: 67,
            data_length: 0,
            expected: Some(u64::MAX),
        },
    ]
}

fn provenance(lock: &str, manifest: &[u8], generator: &[u8], toolchain: &[u8]) -> String {
    let mut output = String::new();
    writeln!(output, "# format: {FORMAT}").unwrap();
    writeln!(output, "# agave: {AGAVE}").unwrap();
    writeln!(
        output,
        "# agave-cargo-lock-sha256: {AGAVE_CARGO_LOCK_SHA256}"
    )
    .unwrap();
    writeln!(output, "# solana-sdk: {SOLANA_SDK}").unwrap();
    writeln!(output, "# spl-token-2022-interface: 3.1.1").unwrap();
    writeln!(
        output,
        "# spl-token-2022-interface-crate-checksum: {TOKEN_2022_CHECKSUM}"
    )
    .unwrap();
    writeln!(output, "# spl-token-group-interface: 0.7.2").unwrap();
    writeln!(
        output,
        "# spl-token-group-interface-crate-checksum: {TOKEN_GROUP_CHECKSUM}"
    )
    .unwrap();
    writeln!(output, "# spl-token-metadata-interface: 1.0.1").unwrap();
    writeln!(
        output,
        "# spl-token-metadata-interface-crate-checksum: {TOKEN_METADATA_CHECKSUM}"
    )
    .unwrap();
    writeln!(output, "# spl-type-length-value: 0.9.1").unwrap();
    writeln!(
        output,
        "# spl-type-length-value-crate-checksum: {TYPE_LENGTH_VALUE_CHECKSUM}"
    )
    .unwrap();
    writeln!(output, "# solana-epoch-rewards: 3.2.0").unwrap();
    writeln!(
        output,
        "# solana-epoch-rewards-crate-checksum: {EPOCH_REWARDS_CHECKSUM}"
    )
    .unwrap();
    writeln!(output, "# solana-epoch-schedule: 3.3.0").unwrap();
    writeln!(
        output,
        "# solana-epoch-schedule-crate-checksum: {EPOCH_SCHEDULE_CHECKSUM}"
    )
    .unwrap();
    writeln!(output, "# solana-rent: 4.4.0").unwrap();
    writeln!(output, "# solana-rent-crate-checksum: {RENT_CHECKSUM}").unwrap();
    writeln!(output, "# wincode: 0.6.1").unwrap();
    writeln!(output, "# wincode-crate-checksum: {WINCODE_CHECKSUM}").unwrap();
    writeln!(
        output,
        "# cargo-lock-sha256: {}",
        sha256_hex(lock.as_bytes())
    )
    .unwrap();
    writeln!(output, "# cargo-manifest-sha256: {}", sha256_hex(manifest)).unwrap();
    writeln!(
        output,
        "# generator-source-sha256: {}",
        sha256_hex(generator)
    )
    .unwrap();
    writeln!(output, "# rust-toolchain: {TOOLCHAIN}").unwrap();
    writeln!(output, "# rust-toolchain-sha256: {}", sha256_hex(toolchain)).unwrap();
    output
}

fn header(provenance: &str, property: &str, rows: usize) -> String {
    let mut output = provenance.to_owned();
    writeln!(output, "# property: {property}").unwrap();
    writeln!(output, "# rows: {rows}").unwrap();
    output
}

fn assert_locked_package(
    lock: &str,
    name: &str,
    version: &str,
    checksum: &str,
) -> Result<(), String> {
    let expected = format!(
        "name = \"{name}\"\nversion = \"{version}\"\nsource = \"registry+https://github.com/rust-lang/crates.io-index\"\nchecksum = \"{checksum}\""
    );
    if lock.contains(&expected) {
        Ok(())
    } else {
        Err(format!(
            "Cargo.lock does not contain {name} {version} with checksum {checksum}"
        ))
    }
}

fn optional_bool(value: Option<bool>) -> &'static str {
    match value {
        Some(true) => "true",
        Some(false) => "false",
        None => "n/a",
    }
}

fn sha256_hex(bytes: &[u8]) -> String {
    encode_hex(&Sha256::digest(bytes))
}

fn encode_hex(bytes: &[u8]) -> String {
    let mut encoded = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        write!(encoded, "{byte:02x}").unwrap();
    }
    encoded
}

fn read_bytes(path: &Path) -> Result<Vec<u8>, String> {
    fs::read(path).map_err(|error| format!("failed to read {}: {error}", path.display()))
}

fn write_fixture(path: &Path, fixture: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("failed to create {}: {error}", parent.display()))?;
    }
    fs::write(path, fixture).map_err(|error| format!("failed to write {}: {error}", path.display()))
}

fn check_fixture(path: &Path, fixture: &str) -> Result<(), String> {
    let committed =
        fs::read(path).map_err(|error| format!("failed to read {}: {error}", path.display()))?;
    if committed == fixture.as_bytes() {
        Ok(())
    } else {
        Err(format!(
            "{} is stale; regenerate with --write",
            path.display()
        ))
    }
}
