use sha2::{Digest, Sha256};
use solana_address::Address;
use solana_hash::Hash;
use solana_instruction::{AccountMeta, Instruction};
use solana_keypair::Keypair;
use solana_message::{
    compiled_instruction::CompiledInstruction,
    v1::{
        self, TransactionConfig, TransactionConfigMask, MAX_ADDRESSES, MAX_HEAP_SIZE,
        MAX_INSTRUCTIONS, MAX_SIGNATURES, MIN_HEAP_SIZE, V1_PREFIX,
    },
    MessageHeader, VersionedMessage,
};
use solana_signer::Signer;
use solana_transaction::{versioned::VersionedTransaction, Signature};
use std::{
    env,
    fmt::Write as _,
    fs,
    path::{Path, PathBuf},
};

const FORMAT: &str = "sava-solana-v1-message-v1";
const PROPERTY: &str = "Sava transaction v1 framing, field offsets, config mask arithmetic, header derivation, and validation limits match SIMD-0385 as implemented by current Solana Rust";
const SOLANA_SDK: &str = "7e8f4a52f044e7729406bd24ae7c586de92e7f58";
const SIMD: &str = "0385-transaction-v1";
const SHA2_CHECKSUM: &str = "a7507d819769d01a365ab707794a4084392c824f54a7a6a7862f8c3d0892b283";
const SOLANA_ADDRESS_CHECKSUM: &str =
    "01332a01c0a3098404d55a724c8d9a92aed4a50fe40a7dd0c7a51e29274c14de";
const SOLANA_HASH_CHECKSUM: &str =
    "0df9b01495ed31100aca97a7f5862d5e19ab1636d60d1a9f02391408dd9dec84";
const SOLANA_INSTRUCTION_CHECKSUM: &str =
    "d70cf6ece1070a66d25e68274f338f29664794669c175223940a298645ef3495";
const SOLANA_KEYPAIR_CHECKSUM: &str =
    "263d614c12aa267a3278703175fd6440552ca61bc960b5a02a4482720c53438b";
const SOLANA_MESSAGE_CHECKSUM: &str =
    "188f4df6f4f4d5bd8b9d82aee0f053b6c37826dcd96933f7b59f684b406a7b3c";
const SOLANA_SIGNER_CHECKSUM: &str =
    "520bd6021163ee517f4bdc7ae03ded904f97e11320001ba0b3355f45eb14f558";
const SOLANA_TRANSACTION_CHECKSUM: &str =
    "1e8eb7c4143b2e453af994fafd68ece93bccedab8975d42e6a0b081fb727d784";
const WINCODE_CHECKSUM: &str = "bfc6339f1ba427bf7ad7c42403b28e524832ba2ddb5eef1bb2cc3b85db6b7b75";

/// Distinctive little-endian payloads. Each byte differs, so a byte-order or offset
/// mistake in a consumer cannot accidentally decode to the expected number.
const PRIORITY_FEE: u64 = 0x0102_0304_0506_0708;
const COMPUTE_UNIT_LIMIT: u32 = 0x1122_3344;
const LOADED_ACCOUNTS_DATA_SIZE_LIMIT: u32 = 0x5566_7788;
const HEAP_SIZE: u32 = 64 * 1024;

struct Vector {
    id: &'static str,
    category: &'static str,
    message: v1::Message,
    spec: Vec<Ix>,
    patch: Option<fn(&mut [u8])>,
    signer_seeds: Vec<[u8; 32]>,
    expect_validate: bool,
    expect_deserialize: bool,
    expect_wire_round_trip: bool,
}

struct Ix {
    program: [u8; 32],
    accounts: Vec<Meta>,
    data: Vec<u8>,
}

#[derive(Clone, Copy)]
struct Meta {
    key: [u8; 32],
    signer: bool,
    writable: bool,
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
    assert_locked_package(&lock, "sha2", "0.10.9", SHA2_CHECKSUM)?;
    assert_locked_package(&lock, "solana-address", "2.7.0", SOLANA_ADDRESS_CHECKSUM)?;
    assert_locked_package(&lock, "solana-hash", "4.6.0", SOLANA_HASH_CHECKSUM)?;
    assert_locked_package(
        &lock,
        "solana-instruction",
        "3.5.0",
        SOLANA_INSTRUCTION_CHECKSUM,
    )?;
    assert_locked_package(&lock, "wincode", "0.6.1", WINCODE_CHECKSUM)?;
    assert_locked_package(&lock, "solana-keypair", "3.1.2", SOLANA_KEYPAIR_CHECKSUM)?;
    assert_locked_package(&lock, "solana-message", "4.5.0", SOLANA_MESSAGE_CHECKSUM)?;
    assert_locked_package(&lock, "solana-signer", "3.0.1", SOLANA_SIGNER_CHECKSUM)?;
    assert_locked_package(
        &lock,
        "solana-transaction",
        "4.2.0",
        SOLANA_TRANSACTION_CHECKSUM,
    )?;

    let fixture = generate(&lock, &manifest, &generator, &toolchain)?;
    let fixture_path = manifest_dir.join("../../resources/tx/solana-v1-message-vectors.tsv");
    match mode {
        "--write" => write_fixture(&fixture_path, &fixture),
        "--check" => check_fixture(&fixture_path, &fixture),
        _ => unreachable!(),
    }
}

fn generate(
    lock: &str,
    manifest: &[u8],
    generator: &[u8],
    toolchain: &[u8],
) -> Result<String, String> {
    let vectors = vectors();
    let prefix_facts = prefix_facts()?;

    let mut rows = String::new();
    for vector in &vectors {
        rows.push_str(&row(vector)?);
    }

    let mut output = String::new();
    writeln!(output, "# format: {FORMAT}").unwrap();
    writeln!(output, "# property: {PROPERTY}").unwrap();
    writeln!(output, "# simd: {SIMD}").unwrap();
    writeln!(output, "# solana-sdk: {SOLANA_SDK}").unwrap();
    writeln!(output, "# solana-message: 4.5.0").unwrap();
    writeln!(output, "# solana-transaction: 4.2.0").unwrap();
    writeln!(output, "# wincode: 0.6.1").unwrap();
    writeln!(
        output,
        "# v1-constants: V1_PREFIX={V1_PREFIX} FIXED_HEADER_SIZE={} MAX_ADDRESSES={MAX_ADDRESSES} \
         MAX_INSTRUCTIONS={MAX_INSTRUCTIONS} MAX_SIGNATURES={MAX_SIGNATURES} \
         MIN_HEAP_SIZE={MIN_HEAP_SIZE} MAX_HEAP_SIZE={MAX_HEAP_SIZE} \
         MAX_TRANSACTION_SIZE={}",
        v1::FIXED_HEADER_SIZE,
        v1::MAX_TRANSACTION_SIZE
    )
    .unwrap();
    writeln!(
        output,
        "# config-mask-bits: PRIORITY_FEE={} u64 COMPUTE_UNIT_LIMIT={} u32 \
         LOADED_ACCOUNTS_DATA_SIZE={} u32 HEAP_SIZE={} u32 KNOWN_BITS={}",
        TransactionConfigMask::PRIORITY_FEE,
        TransactionConfigMask::COMPUTE_UNIT_LIMIT,
        TransactionConfigMask::LOADED_ACCOUNTS_DATA_SIZE,
        TransactionConfigMask::HEAP_SIZE,
        TransactionConfigMask::KNOWN_BITS
    )
    .unwrap();
    writeln!(output, "# framing: message-first; signatures trail with no compact-u16 count; signature_block_offset equals the serialized message length").unwrap();
    writeln!(output, "# signed-payload: VersionedMessage::serialize() including the 0x81 prefix is the ed25519 signed payload").unwrap();
    writeln!(output, "# v1-message-serialize-emits-version-prefix: {}", prefix_facts.serialize_emits_prefix).unwrap();
    writeln!(output, "# v1-message-deserialize-consumes-version-prefix: {}", prefix_facts.deserialize_consumes_prefix).unwrap();
    writeln!(output, "# v1-message-deserialize-with-prefix-error: {}", prefix_facts.with_prefix_error).unwrap();
    writeln!(
        output,
        "# cargo-lock-sha256: {}",
        encode_hex(&Sha256::digest(lock.as_bytes()))
    )
    .unwrap();
    writeln!(
        output,
        "# cargo-manifest-sha256: {}",
        encode_hex(&Sha256::digest(manifest))
    )
    .unwrap();
    writeln!(
        output,
        "# generator-source-sha256: {}",
        encode_hex(&Sha256::digest(generator))
    )
    .unwrap();
    writeln!(output, "# rust-toolchain: 1.93.0").unwrap();
    writeln!(
        output,
        "# rust-toolchain-sha256: {}",
        encode_hex(&Sha256::digest(toolchain))
    )
    .unwrap();
    writeln!(output, "# vectors: {}", vectors.len()).unwrap();
    writeln!(output, "{}", columns()).unwrap();
    output.push_str(&rows);
    Ok(output)
}

fn columns() -> &'static str {
    "id\tcategory\theader\tconfig_mask\tnum_addresses\tnum_instructions\tmessage_len\toffsets\t\
     config_values\tmessage_hex\taddresses_hex\tinstructions\tsigner_seeds_hex\tsignatures_hex\t\
     signature_block_offset\ttransaction_hex\trust_validate\trust_validate_error\t\
     rust_deserialize\trust_deserialize_error\trust_deserialize_with_prefix\t\
     rust_wire_round_trip"
}

struct PrefixFacts {
    serialize_emits_prefix: bool,
    deserialize_consumes_prefix: bool,
    with_prefix_error: &'static str,
}

/// `v1::Message::serialize` writes the 0x81 version byte, but `v1::deserialize` reads the
/// bare message body. Establish both halves by execution rather than by reading the source.
fn prefix_facts() -> Result<PrefixFacts, String> {
    let message = simple_message(TransactionConfig::empty(), 3, 1, 0xc1);
    let bytes = message.serialize();
    let serialize_emits_prefix = bytes.first().copied() == Some(V1_PREFIX);
    if !serialize_emits_prefix {
        return Err("v1::Message::serialize no longer emits the version prefix".into());
    }
    let without_prefix = v1::deserialize(&bytes[1..]);
    if without_prefix.as_ref().ok() != Some(&message) {
        return Err(format!(
            "v1::deserialize rejected the prefix-stripped message: {without_prefix:?}"
        ));
    }
    let with_prefix = v1::deserialize(&bytes);
    let deserialize_consumes_prefix = with_prefix.is_ok();
    if deserialize_consumes_prefix {
        return Err("v1::deserialize now accepts the version prefix; the asymmetry is gone".into());
    }
    Ok(PrefixFacts {
        serialize_emits_prefix,
        deserialize_consumes_prefix,
        with_prefix_error: read_error_discriminant(with_prefix.as_ref().err().unwrap()),
    })
}

fn row(vector: &Vector) -> Result<String, String> {
    let mut message_bytes = vector.message.serialize();
    if message_bytes.first().copied() != Some(V1_PREFIX) {
        return Err(format!("{} message lost its version prefix", vector.id));
    }

    let signatures = if vector.signer_seeds.is_empty() {
        vec![
            Signature::default();
            usize::from(vector.message.header.num_required_signatures)
        ]
    } else {
        let keypairs = vector
            .signer_seeds
            .iter()
            .copied()
            .map(Keypair::new_from_array)
            .collect::<Vec<_>>();
        let signer_refs = keypairs
            .iter()
            .map(|keypair| keypair as &dyn Signer)
            .collect::<Vec<_>>();
        VersionedTransaction::try_new(
            VersionedMessage::V1(vector.message.clone()),
            signer_refs.as_slice(),
        )
        .map_err(|error| format!("{} signing oracle failed: {error}", vector.id))?
        .signatures
    };

    let transaction = VersionedTransaction {
        signatures: signatures.clone(),
        message: VersionedMessage::V1(vector.message.clone()),
    };
    let mut wire = wincode::serialize(&transaction)
        .map_err(|error| format!("{} transaction serialization failed: {error}", vector.id))?;

    if let Some(patch) = vector.patch {
        patch(&mut message_bytes);
        patch(&mut wire);
    }

    // The message is the leading run of the v1 transaction. Nothing precedes it, so the
    // signature block starts exactly at the serialized message length.
    let signature_block_offset = message_bytes.len();
    if wire.len() != signature_block_offset + signatures.len() * 64 {
        return Err(format!("{} unexpected v1 transaction length", vector.id));
    }
    if wire[..signature_block_offset] != message_bytes[..] {
        return Err(format!(
            "{} transaction does not lead with the serialized message",
            vector.id
        ));
    }
    for (index, signature) in signatures.iter().enumerate() {
        let start = signature_block_offset + index * 64;
        if &wire[start..start + 64] != signature.as_ref() {
            return Err(format!("{} signature slot {index} moved", vector.id));
        }
    }

    let validate = vector.message.validate();
    let deserialized = v1::deserialize(&message_bytes[1..]);
    let deserialized_with_prefix = v1::deserialize(&message_bytes);
    let wire_round_trip = wincode::deserialize::<VersionedTransaction>(&wire)
        .is_ok_and(|decoded| decoded == transaction);

    if validate.is_ok() != vector.expect_validate {
        return Err(format!("{} unexpected validate: {validate:?}", vector.id));
    }
    if deserialized.is_ok() != vector.expect_deserialize {
        return Err(format!(
            "{} unexpected deserialize: {deserialized:?}",
            vector.id
        ));
    }
    if deserialized_with_prefix.is_ok() {
        return Err(format!(
            "{} deserialize accepted the version prefix",
            vector.id
        ));
    }
    if wire_round_trip != vector.expect_wire_round_trip {
        return Err(format!(
            "{} unexpected transaction wire round trip: {wire_round_trip}",
            vector.id
        ));
    }
    if vector.expect_deserialize && deserialized.as_ref().ok() != Some(&vector.message) {
        return Err(format!("{} did not decode back to itself", vector.id));
    }

    let layout = Layout::of(&message_bytes)?;
    if vector.patch.is_none() && layout.message_end != message_bytes.len() {
        return Err(format!(
            "{} wire layout ends at {} but the message is {} bytes",
            vector.id,
            layout.message_end,
            message_bytes.len()
        ));
    }

    let mut encoded = String::new();
    writeln!(
        encoded,
        "{id}\t{category}\t{required}:{readonly_signed}:{readonly_unsigned}\t{mask}\t\
         {num_addresses}\t{num_instructions}\t{message_len}\t{offsets}\t{config_values}\t\
         {message_hex}\t{addresses}\t{instructions}\t{seeds}\t{signatures}\t\
         {signature_block_offset}\t{transaction_hex}\t{validate_ok}\t{validate_error}\t\
         {deserialize_ok}\t{deserialize_error}\t{deserialize_with_prefix_ok}\t\
         {wire_round_trip}",
        id = vector.id,
        category = vector.category,
        required = message_bytes[1],
        readonly_signed = message_bytes[2],
        readonly_unsigned = message_bytes[3],
        mask = layout.config_mask,
        num_addresses = layout.num_addresses,
        num_instructions = layout.num_instructions,
        message_len = message_bytes.len(),
        offsets = layout.encode(),
        config_values = encode_config_values(&layout, &message_bytes),
        message_hex = encode_hex(&message_bytes),
        // The address array is a verbatim slice of `message_hex` at offset 42, so it is only
        // restated where the ordering itself is the property under test.
        addresses = if vector.category == "ordering" {
            encode_addresses(&vector.message.account_keys)
        } else {
            String::new()
        },
        instructions = encode_instructions(&vector.spec),
        seeds = vector
            .signer_seeds
            .iter()
            .map(|seed| encode_hex(seed))
            .collect::<Vec<_>>()
            .join(","),
        signatures = signatures
            .iter()
            .map(|signature| encode_hex(signature.as_ref()))
            .collect::<Vec<_>>()
            .join(","),
        signature_block_offset = signature_block_offset,
        // Every row asserts `signature_block_offset == message_len`, so the full wire bytes
        // are only restated for the framing category, whose vectors carry real signatures.
        transaction_hex = if vector.category == "framing" {
            encode_hex(&wire)
        } else {
            String::new()
        },
        validate_ok = validate.is_ok(),
        deserialize_ok = deserialized.is_ok(),
        deserialize_with_prefix_ok = deserialized_with_prefix.is_ok(),
        wire_round_trip = wire_round_trip,
        validate_error = validate
            .as_ref()
            .err()
            .map(|error| format!("{error:?}"))
            .unwrap_or_default(),
        deserialize_error = deserialized
            .as_ref()
            .err()
            .map(read_error_discriminant)
            .unwrap_or_default(),
    )
    .unwrap();
    Ok(encoded)
}

/// Absolute byte offsets within the serialized message, derived from the wire bytes rather
/// than from the in-memory struct.
struct Layout {
    config_mask: u32,
    num_instructions: usize,
    num_addresses: usize,
    addresses: usize,
    config_values: usize,
    instruction_headers: usize,
    instruction_payloads: usize,
    message_end: usize,
}

impl Layout {
    const VERSION: usize = 0;
    const HEADER: usize = 1;
    const CONFIG_MASK: usize = 4;
    const LIFETIME_SPECIFIER: usize = 8;
    const NUM_INSTRUCTIONS: usize = 40;
    const NUM_ADDRESSES: usize = 41;
    const ADDRESSES: usize = 42;

    fn of(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() <= Self::ADDRESSES {
            return Err("message shorter than the fixed v1 header".into());
        }
        let config_mask = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
        let num_instructions = usize::from(bytes[Self::NUM_INSTRUCTIONS]);
        let num_addresses = usize::from(bytes[Self::NUM_ADDRESSES]);
        let config_values = Self::ADDRESSES + num_addresses * 32;
        let instruction_headers =
            config_values + TransactionConfigMask::new(config_mask).size_of_config();
        let instruction_payloads = instruction_headers + num_instructions * 4;
        let mut message_end = instruction_payloads;
        for index in 0..num_instructions {
            let header = instruction_headers + index * 4;
            if header + 4 > bytes.len() {
                return Err("truncated instruction headers".into());
            }
            message_end += usize::from(bytes[header + 1])
                + usize::from(u16::from_le_bytes([bytes[header + 2], bytes[header + 3]]));
        }
        Ok(Self {
            config_mask,
            num_instructions,
            num_addresses,
            addresses: Self::ADDRESSES,
            config_values,
            instruction_headers,
            instruction_payloads,
            message_end,
        })
    }

    fn encode(&self) -> String {
        format!(
            "version={};header={};config_mask={};lifetime_specifier={};num_instructions={};\
             num_addresses={};addresses={};config_values={};instruction_headers={};\
             instruction_payloads={};message_end={}",
            Self::VERSION,
            Self::HEADER,
            Self::CONFIG_MASK,
            Self::LIFETIME_SPECIFIER,
            Self::NUM_INSTRUCTIONS,
            Self::NUM_ADDRESSES,
            self.addresses,
            self.config_values,
            self.instruction_headers,
            self.instruction_payloads,
            self.message_end
        )
    }
}

fn encode_config_values(layout: &Layout, bytes: &[u8]) -> String {
    let mask = TransactionConfigMask::new(layout.config_mask);
    let mut offset = layout.config_values;
    let mut encoded = Vec::new();
    if mask.has_priority_fee() && offset + 8 <= bytes.len() {
        let value = u64::from_le_bytes(bytes[offset..offset + 8].try_into().unwrap());
        encoded.push(format!("priority_fee@{offset}={value}"));
        offset += 8;
    }
    for (present, name) in [
        (mask.has_compute_unit_limit(), "compute_unit_limit"),
        (
            mask.has_loaded_accounts_data_size(),
            "loaded_accounts_data_size_limit",
        ),
        (mask.has_heap_size(), "heap_size"),
    ] {
        if present && offset + 4 <= bytes.len() {
            let value = u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap());
            encoded.push(format!("{name}@{offset}={value}"));
            offset += 4;
        }
    }
    encoded.join(";")
}

fn read_error_discriminant(error: &wincode::error::ReadError) -> &'static str {
    use wincode::error::ReadError;
    match error {
        ReadError::Io(_) => "Io",
        ReadError::InvalidUtf8Encoding(_) => "InvalidUtf8Encoding",
        ReadError::InvalidUtf8Code(_) => "InvalidUtf8Code",
        ReadError::PointerSizedReadError => "PointerSizedReadError",
        ReadError::PreallocationSizeLimit { .. } => "PreallocationSizeLimit",
        ReadError::InvalidTagEncoding(_) => "InvalidTagEncoding",
        ReadError::InvalidBoolEncoding(_) => "InvalidBoolEncoding",
        ReadError::LengthEncodingOverflow(_) => "LengthEncodingOverflow",
        ReadError::InvalidValue(_) => "InvalidValue",
        ReadError::InvalidCharLead(_) => "InvalidCharLead",
        ReadError::TrailingBytes => "TrailingBytes",
        ReadError::Custom(_) => "Custom",
        ReadError::UnalignedPointerRead => "UnalignedPointerRead",
        ReadError::TagEncodingOverflow(_) => "TagEncodingOverflow",
    }
}

fn vectors() -> Vec<Vector> {
    let mut vectors = Vec::new();
    vectors.extend(framing_vectors());
    vectors.extend(offset_vectors());
    vectors.extend(config_mask_vectors());
    vectors.extend(ordering_vectors());
    vectors.extend(rejection_vectors());
    vectors
}

// 1. Framing --------------------------------------------------------------------------------

fn framing_vectors() -> Vec<Vector> {
    (1u8..=3)
        .map(|num_signers| {
            let seeds = (0..num_signers).map(signer_seed).collect::<Vec<_>>();
            let keys = seeds
                .iter()
                .copied()
                .map(Keypair::new_from_array)
                .map(|keypair| keypair.pubkey().to_bytes())
                .collect::<Vec<_>>();
            let spec = vec![Ix {
                program: fill(0x77),
                accounts: keys[1..]
                    .iter()
                    .copied()
                    .map(|key| Meta {
                        key,
                        signer: true,
                        writable: true,
                    })
                    .collect(),
                data: vec![num_signers, 0xab],
            }];
            let message = compile(
                keys[0],
                &spec,
                fill(0x90 + num_signers),
                TransactionConfig::empty(),
            );
            Vector {
                id: match num_signers {
                    1 => "framing_1_signature",
                    2 => "framing_2_signatures",
                    _ => "framing_3_signatures",
                },
                category: "framing",
                message,
                spec,
                patch: None,
                // Deliberately reversed. try_new resolves each signer back to its slot.
                signer_seeds: seeds.into_iter().rev().collect(),
                expect_validate: true,
                expect_deserialize: true,
                expect_wire_round_trip: true,
            }
        })
        .collect()
}

// 2. Field offsets --------------------------------------------------------------------------

fn offset_vectors() -> Vec<Vector> {
    vec![
        accepted(
            "offsets_minimal",
            "offsets",
            simple_message(TransactionConfig::empty(), 2, 1, 0xa1),
        ),
        accepted(
            "offsets_all_config_values",
            "offsets",
            simple_message(full_config(), 2, 1, 0xa2),
        ),
        accepted(
            "offsets_max_addresses",
            "offsets",
            simple_message(TransactionConfig::empty(), MAX_ADDRESSES as usize, 1, 0xa3),
        ),
        accepted(
            "offsets_max_instructions",
            "offsets",
            simple_message(
                TransactionConfig::empty(),
                2,
                MAX_INSTRUCTIONS as usize,
                0xa4,
            ),
        ),
        accepted(
            "offsets_max_addresses_and_instructions_with_config",
            "offsets",
            simple_message(
                full_config(),
                MAX_ADDRESSES as usize,
                MAX_INSTRUCTIONS as usize,
                0xa5,
            ),
        ),
        accepted(
            "offsets_wide_instruction_data",
            "offsets",
            raw_message(
                MessageHeader {
                    num_required_signatures: 1,
                    num_readonly_signed_accounts: 0,
                    num_readonly_unsigned_accounts: 1,
                },
                TransactionConfig::empty().with_compute_unit_limit(COMPUTE_UNIT_LIMIT),
                3,
                vec![
                    CompiledInstruction {
                        program_id_index: 2,
                        accounts: vec![0, 1, 0, 1],
                        // 300 bytes exercises the u16 little-endian data length.
                        data: (0..300).map(|index| index as u8).collect(),
                    },
                    CompiledInstruction {
                        program_id_index: 2,
                        accounts: vec![],
                        data: vec![],
                    },
                ],
                0xa6,
            ),
        ),
    ]
}

// 3. Config mask ----------------------------------------------------------------------------

fn config_mask_vectors() -> Vec<Vector> {
    (0u8..16)
        .map(|combination| {
            let mut config = TransactionConfig::empty();
            if combination & 0b0001 != 0 {
                config = config.with_priority_fee(PRIORITY_FEE);
            }
            if combination & 0b0010 != 0 {
                config = config.with_compute_unit_limit(COMPUTE_UNIT_LIMIT);
            }
            if combination & 0b0100 != 0 {
                config =
                    config.with_loaded_accounts_data_size_limit(LOADED_ACCOUNTS_DATA_SIZE_LIMIT);
            }
            if combination & 0b1000 != 0 {
                config = config.with_heap_size(HEAP_SIZE);
            }
            let id: &'static str = CONFIG_MASK_IDS[combination as usize];
            accepted(
                id,
                "config_mask",
                simple_message(config, 3, 2, 0xb0 + combination),
            )
        })
        .collect()
}

const CONFIG_MASK_IDS: [&str; 16] = [
    "config_none",
    "config_pf",
    "config_cul",
    "config_pf_cul",
    "config_adsl",
    "config_pf_adsl",
    "config_cul_adsl",
    "config_pf_cul_adsl",
    "config_heap",
    "config_pf_heap",
    "config_cul_heap",
    "config_pf_cul_heap",
    "config_adsl_heap",
    "config_pf_adsl_heap",
    "config_cul_adsl_heap",
    "config_pf_cul_adsl_heap",
];

// 4. Account ordering and header derivation -------------------------------------------------

fn ordering_vectors() -> Vec<Vector> {
    // The first two instruction shapes are the ones the legacy fixture already compiles, so
    // the two fixtures can be diffed for identical promotion, ordering, and header rules.
    let promotion = vec![
        ix(
            0x20,
            &[
                meta(0x61, false, false),
                meta(0x51, false, false),
                meta(0x41, false, true),
                meta(0x31, true, false),
                meta(0x21, true, true),
                meta(0x61, true, false),
            ],
            &[1, 2, 3],
        ),
        ix(
            0x70,
            &[
                meta(0x51, false, true),
                meta(0x41, true, false),
                meta(0x31, false, true),
                meta(0x21, false, false),
            ],
            &[4, 5],
        ),
    ];
    let duplicates = vec![ix(
        0x02,
        &[
            meta(0x04, false, false),
            meta(0x03, false, true),
            meta(0x04, false, false),
            meta(0x03, false, false),
        ],
        &[0x80, 0x00, 0xff],
    )];
    let signer_slots = vec![ix(
        0x77,
        &[
            meta(0x22, true, true),
            meta(0x33, true, false),
            meta(0x44, false, true),
            meta(0x55, false, false),
        ],
        &[9, 8, 7, 6],
    )];

    vec![
        compiled(
            "ordering_promotion",
            fill(0x10),
            promotion,
            fill(0xa0),
            TransactionConfig::empty(),
        ),
        compiled(
            "ordering_duplicate_indices",
            fill(0x01),
            duplicates,
            fill(0xb0),
            TransactionConfig::empty(),
        ),
        compiled(
            "ordering_all_four_roles",
            fill(0x11),
            signer_slots,
            fill(0xc0),
            full_config(),
        ),
    ]
}

// 5. Rejections -----------------------------------------------------------------------------

fn rejection_vectors() -> Vec<Vector> {
    let mut vectors = vec![
        // Accepted boundaries first, so each rejection has an adjacent positive control.
        accepted(
            "accept_signatures_12",
            "rejection",
            signature_boundary_message(MAX_SIGNATURES, 0xd0),
        ),
        accepted(
            "accept_addresses_64",
            "rejection",
            simple_message(TransactionConfig::empty(), MAX_ADDRESSES as usize, 1, 0xd1),
        ),
        accepted(
            "accept_instructions_64",
            "rejection",
            simple_message(
                TransactionConfig::empty(),
                2,
                MAX_INSTRUCTIONS as usize,
                0xd2,
            ),
        ),
        accepted(
            "accept_heap_size_min",
            "rejection",
            simple_message(
                TransactionConfig::empty().with_heap_size(MIN_HEAP_SIZE),
                2,
                1,
                0xd3,
            ),
        ),
        accepted(
            "accept_heap_size_max",
            "rejection",
            simple_message(
                TransactionConfig::empty().with_heap_size(MAX_HEAP_SIZE),
                2,
                1,
                0xd4,
            ),
        ),
        // validate() rejections. The bytes still decode: these are semantic limits, not
        // framing errors, so a permissive byte reader still sees a coherent message.
        rejected_by_validate(
            "reject_signatures_13",
            signature_boundary_message(MAX_SIGNATURES + 1, 0xd5),
        ),
        rejected_by_validate(
            "reject_addresses_65",
            simple_message(
                TransactionConfig::empty(),
                MAX_ADDRESSES as usize + 1,
                1,
                0xd6,
            ),
        ),
        rejected_by_validate(
            "reject_instructions_65",
            simple_message(
                TransactionConfig::empty(),
                2,
                MAX_INSTRUCTIONS as usize + 1,
                0xd7,
            ),
        ),
        rejected_by_validate(
            "reject_readonly_signed_equals_required",
            raw_message(
                MessageHeader {
                    num_required_signatures: 2,
                    num_readonly_signed_accounts: 2,
                    num_readonly_unsigned_accounts: 0,
                },
                TransactionConfig::empty(),
                3,
                vec![CompiledInstruction {
                    program_id_index: 2,
                    accounts: vec![0, 1],
                    data: vec![0x11],
                }],
                0xd8,
            ),
        ),
        rejected_by_validate(
            "reject_not_enough_addresses_for_signatures",
            raw_message(
                MessageHeader {
                    num_required_signatures: 3,
                    num_readonly_signed_accounts: 1,
                    num_readonly_unsigned_accounts: 2,
                },
                TransactionConfig::empty(),
                4,
                vec![CompiledInstruction {
                    program_id_index: 3,
                    accounts: vec![0],
                    data: vec![0x12],
                }],
                0xd9,
            ),
        ),
        rejected_by_validate(
            "reject_heap_size_not_1kib_multiple",
            simple_message(
                TransactionConfig::empty().with_heap_size(MIN_HEAP_SIZE + 1),
                2,
                1,
                0xda,
            ),
        ),
        rejected_by_validate(
            "reject_heap_size_below_min",
            simple_message(
                TransactionConfig::empty().with_heap_size(MIN_HEAP_SIZE - 1024),
                2,
                1,
                0xdb,
            ),
        ),
        rejected_by_validate(
            "reject_heap_size_above_max",
            simple_message(
                TransactionConfig::empty().with_heap_size(MAX_HEAP_SIZE + 1024),
                2,
                1,
                0xdc,
            ),
        ),
        rejected_by_validate(
            "reject_program_id_index_is_fee_payer",
            raw_message(
                MessageHeader {
                    num_required_signatures: 1,
                    num_readonly_signed_accounts: 0,
                    num_readonly_unsigned_accounts: 1,
                },
                TransactionConfig::empty(),
                2,
                vec![CompiledInstruction {
                    program_id_index: 0,
                    accounts: vec![1],
                    data: vec![0x13],
                }],
                0xdd,
            ),
        ),
        rejected_by_validate(
            "reject_account_index_out_of_bounds",
            raw_message(
                MessageHeader {
                    num_required_signatures: 1,
                    num_readonly_signed_accounts: 0,
                    num_readonly_unsigned_accounts: 1,
                },
                TransactionConfig::empty(),
                2,
                vec![CompiledInstruction {
                    program_id_index: 1,
                    accounts: vec![0, 2],
                    data: vec![0x14],
                }],
                0xde,
            ),
        ),
    ];

    // Mask rejections cannot be expressed as a `TransactionConfig`; serialization regenerates
    // the mask from the typed config. They only exist in raw wire bytes, so the mask is
    // patched after serialization and `validate()` cannot see them at all.
    vectors.push(mask_patch_vector(
        "reject_config_mask_priority_fee_low_bit_only",
        0xdf,
        patch_mask_to_1,
    ));
    vectors.push(mask_patch_vector(
        "reject_config_mask_priority_fee_high_bit_only",
        0xe0,
        patch_mask_to_2,
    ));
    vectors.push(mask_patch_vector(
        "reject_config_mask_unknown_bit_5",
        0xe1,
        patch_mask_to_32,
    ));
    vectors.push(mask_patch_vector(
        "reject_config_mask_unknown_high_bit",
        0xe2,
        patch_mask_to_high_bit,
    ));
    vectors
}

fn patch_mask_to_1(bytes: &mut [u8]) {
    write_mask(bytes, 0b1);
}

fn patch_mask_to_2(bytes: &mut [u8]) {
    write_mask(bytes, 0b10);
}

fn patch_mask_to_32(bytes: &mut [u8]) {
    write_mask(bytes, 0b10_0000);
}

fn patch_mask_to_high_bit(bytes: &mut [u8]) {
    write_mask(bytes, 0x8000_0000);
}

fn write_mask(bytes: &mut [u8], mask: u32) {
    bytes[4..8].copy_from_slice(&mask.to_le_bytes());
}

fn mask_patch_vector(id: &'static str, blockhash: u8, patch: fn(&mut [u8])) -> Vector {
    Vector {
        id,
        category: "rejection",
        message: simple_message(TransactionConfig::empty(), 2, 1, blockhash),
        spec: Vec::new(),
        patch: Some(patch),
        signer_seeds: Vec::new(),
        // The typed message is well formed; only the patched wire mask is malformed.
        expect_validate: true,
        expect_deserialize: false,
        expect_wire_round_trip: false,
    }
}

// Construction helpers ----------------------------------------------------------------------

fn accepted(id: &'static str, category: &'static str, message: v1::Message) -> Vector {
    Vector {
        id,
        category,
        message,
        spec: Vec::new(),
        patch: None,
        signer_seeds: Vec::new(),
        expect_validate: true,
        expect_deserialize: true,
        expect_wire_round_trip: true,
    }
}

fn rejected_by_validate(id: &'static str, message: v1::Message) -> Vector {
    Vector {
        id,
        category: "rejection",
        message,
        spec: Vec::new(),
        patch: None,
        signer_seeds: Vec::new(),
        expect_validate: false,
        expect_deserialize: true,
        expect_wire_round_trip: true,
    }
}

fn compiled(
    id: &'static str,
    fee_payer: [u8; 32],
    spec: Vec<Ix>,
    blockhash: [u8; 32],
    config: TransactionConfig,
) -> Vector {
    let message = compile(fee_payer, &spec, blockhash, config);
    Vector {
        id,
        category: "ordering",
        message,
        spec,
        patch: None,
        signer_seeds: Vec::new(),
        expect_validate: true,
        expect_deserialize: true,
        expect_wire_round_trip: true,
    }
}

fn compile(
    fee_payer: [u8; 32],
    spec: &[Ix],
    blockhash: [u8; 32],
    config: TransactionConfig,
) -> v1::Message {
    let instructions = spec.iter().map(to_instruction).collect::<Vec<_>>();
    v1::Message::try_compile_with_config(
        &Address::from(fee_payer),
        &instructions,
        Hash::new_from_array(blockhash),
        config,
    )
    .expect("v1 compilation oracle failed")
}

fn full_config() -> TransactionConfig {
    TransactionConfig::empty()
        .with_priority_fee(PRIORITY_FEE)
        .with_compute_unit_limit(COMPUTE_UNIT_LIMIT)
        .with_loaded_accounts_data_size_limit(LOADED_ACCOUNTS_DATA_SIZE_LIMIT)
        .with_heap_size(HEAP_SIZE)
}

/// One writable signer fee payer, one readonly unsigned program, and `num_addresses - 2`
/// writable unsigned accounts. Every instruction invokes the last address.
fn simple_message(
    config: TransactionConfig,
    num_addresses: usize,
    num_instructions: usize,
    blockhash: u8,
) -> v1::Message {
    let program_id_index = (num_addresses - 1) as u8;
    let instructions = (0..num_instructions)
        .map(|index| CompiledInstruction {
            program_id_index,
            accounts: vec![0],
            data: vec![index as u8, 0x5a],
        })
        .collect();
    raw_message(
        MessageHeader {
            num_required_signatures: 1,
            num_readonly_signed_accounts: 0,
            num_readonly_unsigned_accounts: 1,
        },
        config,
        num_addresses,
        instructions,
        blockhash,
    )
}

/// `num_signatures` writable signers followed by one readonly unsigned program.
fn signature_boundary_message(num_signatures: u8, blockhash: u8) -> v1::Message {
    let num_addresses = usize::from(num_signatures) + 1;
    raw_message(
        MessageHeader {
            num_required_signatures: num_signatures,
            num_readonly_signed_accounts: 0,
            num_readonly_unsigned_accounts: 1,
        },
        TransactionConfig::empty(),
        num_addresses,
        vec![CompiledInstruction {
            program_id_index: (num_addresses - 1) as u8,
            accounts: (0..num_signatures).collect(),
            data: vec![num_signatures],
        }],
        blockhash,
    )
}

fn raw_message(
    header: MessageHeader,
    config: TransactionConfig,
    num_addresses: usize,
    instructions: Vec<CompiledInstruction>,
    blockhash: u8,
) -> v1::Message {
    v1::Message {
        header,
        config,
        lifetime_specifier: Hash::new_from_array(fill(blockhash)),
        account_keys: (0..num_addresses).map(unique_address).collect(),
        instructions,
    }
}

fn unique_address(index: usize) -> Address {
    let mut key = [0u8; 32];
    key[0] = 0x80 | ((index >> 8) as u8);
    key[1] = index as u8;
    key[31] = 0x5a;
    Address::from(key)
}

fn signer_seed(index: u8) -> [u8; 32] {
    let mut seed = [0x5au8; 32];
    seed[0] = 0;
    seed[1] = index;
    seed[31] = 0xa5;
    seed
}

fn fill(byte: u8) -> [u8; 32] {
    [byte; 32]
}

fn meta(byte: u8, signer: bool, writable: bool) -> Meta {
    Meta {
        key: fill(byte),
        signer,
        writable,
    }
}

fn ix(program: u8, accounts: &[Meta], data: &[u8]) -> Ix {
    Ix {
        program: fill(program),
        accounts: accounts.to_vec(),
        data: data.to_vec(),
    }
}

fn to_instruction(source: &Ix) -> Instruction {
    Instruction {
        program_id: Address::from(source.program),
        accounts: source
            .accounts
            .iter()
            .map(|meta| AccountMeta {
                pubkey: Address::from(meta.key),
                is_signer: meta.signer,
                is_writable: meta.writable,
            })
            .collect(),
        data: source.data.clone(),
    }
}

// Encoding ----------------------------------------------------------------------------------

fn encode_addresses(addresses: &[Address]) -> String {
    addresses
        .iter()
        .map(|address| encode_hex(address.as_ref()))
        .collect::<Vec<_>>()
        .join(",")
}

fn encode_instructions(instructions: &[Ix]) -> String {
    let mut encoded = String::new();
    for (instruction_index, source) in instructions.iter().enumerate() {
        if instruction_index > 0 {
            encoded.push(';');
        }
        write!(encoded, "{}|", encode_hex(&source.program)).unwrap();
        for (account_index, meta) in source.accounts.iter().enumerate() {
            if account_index > 0 {
                encoded.push(',');
            }
            write!(
                encoded,
                "{}:{}:{}",
                encode_hex(&meta.key),
                u8::from(meta.signer),
                u8::from(meta.writable)
            )
            .unwrap();
        }
        write!(encoded, "|{}", encode_hex(&source.data)).unwrap();
    }
    encoded
}

fn read_bytes(path: &Path) -> Result<Vec<u8>, String> {
    fs::read(path).map_err(|error| format!("failed to read {}: {error}", path.display()))
}

fn encode_hex(bytes: &[u8]) -> String {
    let mut encoded = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        write!(encoded, "{byte:02x}").unwrap();
    }
    encoded
}

fn assert_locked_package(
    lock: &str,
    name: &str,
    version: &str,
    checksum: &str,
) -> Result<(), String> {
    let package_start = format!("name = \"{name}\"\nversion = \"{version}\"");
    let start = lock
        .find(&package_start)
        .ok_or_else(|| format!("Cargo.lock does not pin {name} {version}"))?;
    let package = lock[start..].split("\n[[package]]").next().unwrap();
    let checksum_line = format!("checksum = \"{checksum}\"");
    if package.contains(&checksum_line) {
        Ok(())
    } else {
        Err(format!("Cargo.lock checksum mismatch for {name} {version}"))
    }
}

fn write_fixture(path: &Path, fixture: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("failed to create {}: {error}", parent.display()))?;
    }
    fs::write(path, fixture).map_err(|error| format!("failed to write {}: {error}", path.display()))
}

fn check_fixture(path: &Path, fixture: &str) -> Result<(), String> {
    let committed = fs::read_to_string(path)
        .map_err(|error| format!("failed to read {}: {error}", path.display()))?;
    if committed == fixture {
        Ok(())
    } else {
        Err(format!(
            "{} is stale; regenerate with --write",
            path.display()
        ))
    }
}
