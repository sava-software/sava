use sha2::{Digest, Sha256};
use solana_address::Address;
use solana_hash::Hash;
use solana_instruction::{AccountMeta, Instruction};
use solana_keypair::Keypair;
use solana_message::{v0, Message, VersionedMessage};
use solana_signer::Signer;
use solana_transaction::{versioned::VersionedTransaction, Signature};
use std::{
    env,
    fmt::Write as _,
    fs,
    path::{Path, PathBuf},
};

const FORMAT: &str = "sava-solana-non-lookup-message-v2";
const SOLANA_SDK: &str = "7e8f4a52f044e7729406bd24ae7c586de92e7f58";
const KIT: &str = "v7.0.0 58df993f4bea388121a872b33038c6af0ca3dd90";
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

struct Vector {
    id: &'static str,
    message_version: MessageVersion,
    fee_payer: [u8; 32],
    blockhash: [u8; 32],
    instructions: Vec<Ix>,
    signer_seeds: Vec<[u8; 32]>,
}

#[derive(Clone, Copy)]
enum MessageVersion {
    Legacy,
    V0,
}

impl MessageVersion {
    fn label(self) -> &'static str {
        match self {
            Self::Legacy => "legacy",
            Self::V0 => "v0",
        }
    }
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
    let fixture_path = manifest_dir.join("../../resources/tx/solana-legacy-message-vectors.tsv");
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
    let mut output = String::new();
    writeln!(output, "# format: {FORMAT}").unwrap();
    writeln!(output, "# property: Sava non-lookup compilation resolves to the same header, account roles, indices, instructions, and shortvec boundaries as current Solana Rust messages").unwrap();
    writeln!(output, "# solana-sdk: {SOLANA_SDK}").unwrap();
    writeln!(output, "# solana-message: 4.5.0").unwrap();
    writeln!(output, "# kit: {KIT}").unwrap();
    writeln!(output, "# kit-oracle: compileTransactionMessage; getOrderedAccountsFromAddressMap; getCompiledMessageHeader; getCompiledInstructions; getCompiledTransactionMessageEncoder").unwrap();
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
    writeln!(output, "id\tfee_payer_hex\tblockhash_hex\tinstructions\tmessage_hex\tsigner_seeds_hex\tsignatures_hex\tsignature_prefix_hex\trust_wire_round_trip\tmessage_version").unwrap();
    for vector in vectors {
        let instructions = vector
            .instructions
            .iter()
            .map(to_instruction)
            .collect::<Vec<_>>();
        let legacy_message = Message::new_with_blockhash(
            &instructions,
            Some(&Address::from(vector.fee_payer)),
            &Hash::new_from_array(vector.blockhash),
        );
        let message = match vector.message_version {
            MessageVersion::Legacy => VersionedMessage::Legacy(legacy_message),
            MessageVersion::V0 => VersionedMessage::V0(v0::Message {
                header: legacy_message.header,
                account_keys: legacy_message.account_keys,
                recent_blockhash: legacy_message.recent_blockhash,
                instructions: legacy_message.instructions,
                address_table_lookups: Vec::new(),
            }),
        };
        let (signer_seeds, signatures, transaction) = if vector.signer_seeds.is_empty() {
            (
                String::new(),
                String::new(),
                VersionedTransaction {
                    signatures: vec![
                        Signature::default();
                        message.header().num_required_signatures as usize
                    ],
                    message: message.clone(),
                },
            )
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
            let transaction =
                VersionedTransaction::try_new(message.clone(), signer_refs.as_slice())
                    .map_err(|error| format!("{} signing oracle failed: {error}", vector.id))?;
            (
                vector
                    .signer_seeds
                    .iter()
                    .map(|seed| encode_hex(seed))
                    .collect::<Vec<_>>()
                    .join(","),
                transaction
                    .signatures
                    .iter()
                    .map(|signature| encode_hex(signature.as_ref()))
                    .collect::<Vec<_>>()
                    .join(","),
                transaction,
            )
        };
        let message_bytes = message.serialize();
        let wire = wincode::serialize(&transaction)
            .map_err(|error| format!("{} transaction serialization failed: {error}", vector.id))?;
        let signature_prefix_len = wire
            .len()
            .checked_sub(transaction.signatures.len() * 64 + message_bytes.len())
            .ok_or_else(|| format!("{} invalid serialized transaction length", vector.id))?;
        let signature_prefix = &wire[..signature_prefix_len];
        let rust_wire_round_trip = wincode::deserialize::<VersionedTransaction>(&wire)
            .is_ok_and(|decoded| decoded == transaction);
        let expected_wire_round_trip = !vector.id.ends_with("signature_shortvec_128");
        if rust_wire_round_trip != expected_wire_round_trip {
            return Err(format!(
                "{} unexpected VersionedTransaction wire round trip: {rust_wire_round_trip}",
                vector.id
            ));
        }
        writeln!(
            output,
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            vector.id,
            encode_hex(&vector.fee_payer),
            encode_hex(&vector.blockhash),
            encode_instructions(&vector.instructions),
            encode_hex(&message_bytes),
            signer_seeds,
            signatures,
            encode_hex(signature_prefix),
            rust_wire_round_trip,
            vector.message_version.label(),
        )
        .unwrap();
    }
    Ok(output)
}

fn vectors() -> Vec<Vector> {
    let payer_seed = key(0x11);
    let authority_seed = key(0x22);
    let payer = Keypair::new_from_array(payer_seed);
    let authority = Keypair::new_from_array(authority_seed);
    vec![
        Vector {
            id: "signer_slot_ordering",
            message_version: MessageVersion::Legacy,
            fee_payer: payer.pubkey().to_bytes(),
            blockhash: key(0x90),
            instructions: vec![Ix {
                program: key(0x77),
                accounts: vec![Meta {
                    key: authority.pubkey().to_bytes(),
                    signer: true,
                    writable: true,
                }],
                data: vec![9, 8, 7, 6],
            }],
            // Intentionally reversed from message order. VersionedTransaction::try_new
            // must match these signers by public key and emit payer, authority slots.
            signer_seeds: vec![authority_seed, payer_seed],
        },
        signer_boundary_vector("signature_shortvec_127", 127, 0x91),
        signer_boundary_vector("signature_shortvec_128", 128, 0x92),
        versioned_signer_boundary_vector("v0_signature_shortvec_127", 127, 0x93),
        versioned_signer_boundary_vector("v0_signature_shortvec_128", 128, 0x93),
        Vector {
            id: "promotion_and_ordering",
            message_version: MessageVersion::Legacy,
            fee_payer: key(0x10),
            blockhash: key(0xa0),
            instructions: vec![
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
            ],
            signer_seeds: vec![],
        },
        Vector {
            id: "duplicate_instruction_indices",
            message_version: MessageVersion::Legacy,
            fee_payer: key(0x01),
            blockhash: key(0xb0),
            instructions: vec![ix(
                0x02,
                &[
                    meta(0x04, false, false),
                    meta(0x03, false, true),
                    meta(0x04, false, false),
                    meta(0x03, false, false),
                ],
                &[0x80, 0x00, 0xff],
            )],
            signer_seeds: vec![],
        },
        Vector {
            id: "shortvec_127_accounts_and_data",
            message_version: MessageVersion::Legacy,
            fee_payer: key(0x01),
            blockhash: key(0xbf),
            instructions: vec![Ix {
                program: key(0xfe),
                accounts: (0..127)
                    .map(|index| unique_meta(index, false, false))
                    .collect(),
                data: (0..127).map(|index| index as u8).collect(),
            }],
            signer_seeds: vec![],
        },
        Vector {
            id: "shortvec_128_accounts_and_data",
            message_version: MessageVersion::Legacy,
            fee_payer: key(0x01),
            blockhash: key(0xc0),
            instructions: vec![Ix {
                program: key(0xfe),
                accounts: (0..128)
                    .map(|index| unique_meta(index, false, false))
                    .collect(),
                data: (0..128).map(|index| index as u8).collect(),
            }],
            signer_seeds: vec![],
        },
        Vector {
            id: "max_account_index_255",
            message_version: MessageVersion::Legacy,
            fee_payer: key(0x01),
            blockhash: key(0xe0),
            // Payer + program + 254 distinct metas = 256 indexed accounts. At least
            // one compiled instruction index is 255 and must round-trip as unsigned.
            instructions: vec![Ix {
                program: key(0xfe),
                accounts: (0..254)
                    .map(|index| unique_meta(index, false, false))
                    .collect(),
                data: vec![0x25, 0x5f],
            }],
            signer_seeds: vec![],
        },
        Vector {
            id: "shortvec_128_instructions",
            message_version: MessageVersion::Legacy,
            fee_payer: key(0x01),
            blockhash: key(0xd0),
            instructions: (0..128)
                .map(|index| Ix {
                    program: key(0xfe),
                    accounts: vec![unique_meta(index, false, index % 2 == 0)],
                    data: vec![index as u8],
                })
                .collect(),
            signer_seeds: vec![],
        },
    ]
}

fn signer_boundary_vector(id: &'static str, num_signers: usize, blockhash: u8) -> Vector {
    let signer_seeds = (0..num_signers).map(signer_seed).collect::<Vec<_>>();
    let signer_keys = signer_seeds
        .iter()
        .copied()
        .map(Keypair::new_from_array)
        .map(|keypair| keypair.pubkey().to_bytes())
        .collect::<Vec<_>>();
    Vector {
        id,
        message_version: MessageVersion::Legacy,
        fee_payer: signer_keys[0],
        blockhash: key(blockhash),
        instructions: vec![Ix {
            program: key(0x77),
            accounts: signer_keys[1..]
                .iter()
                .copied()
                .map(|key| Meta {
                    key,
                    signer: true,
                    writable: true,
                })
                .collect(),
            data: vec![num_signers as u8],
        }],
        // The Rust oracle receives the opposite order and resolves each key back to
        // its required message slot before serializing the signatures.
        signer_seeds: signer_seeds.into_iter().rev().collect(),
    }
}

fn versioned_signer_boundary_vector(id: &'static str, num_signers: usize, blockhash: u8) -> Vector {
    let mut vector = signer_boundary_vector(id, num_signers, blockhash);
    vector.message_version = MessageVersion::V0;
    vector
}

fn signer_seed(index: usize) -> [u8; 32] {
    let mut seed = [0x5au8; 32];
    seed[0] = (index >> 8) as u8;
    seed[1] = index as u8;
    seed[31] = 0xa5;
    seed
}

fn key(fill: u8) -> [u8; 32] {
    [fill; 32]
}

fn unique_meta(index: usize, signer: bool, writable: bool) -> Meta {
    let mut key = [0u8; 32];
    key[0] = 0x80 | ((index >> 8) as u8);
    key[1] = index as u8;
    key[31] = 0x5a;
    Meta {
        key,
        signer,
        writable,
    }
}

fn meta(fill: u8, signer: bool, writable: bool) -> Meta {
    Meta {
        key: key(fill),
        signer,
        writable,
    }
}

fn ix(program: u8, accounts: &[Meta], data: &[u8]) -> Ix {
    Ix {
        program: key(program),
        accounts: accounts.to_vec(),
        data: data.to_vec(),
    }
}

fn to_instruction(ix: &Ix) -> Instruction {
    Instruction {
        program_id: Address::from(ix.program),
        accounts: ix
            .accounts
            .iter()
            .map(|meta| AccountMeta {
                pubkey: Address::from(meta.key),
                is_signer: meta.signer,
                is_writable: meta.writable,
            })
            .collect(),
        data: ix.data.clone(),
    }
}

fn encode_instructions(instructions: &[Ix]) -> String {
    let mut encoded = String::new();
    for (instruction_index, ix) in instructions.iter().enumerate() {
        if instruction_index > 0 {
            encoded.push(';');
        }
        write!(encoded, "{}|", encode_hex(&ix.program)).unwrap();
        for (account_index, meta) in ix.accounts.iter().enumerate() {
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
        write!(encoded, "|{}", encode_hex(&ix.data)).unwrap();
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
