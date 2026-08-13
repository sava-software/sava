use sha2::{Digest, Sha256};
use solana_curve25519::edwards::{validate_edwards, PodEdwardsPoint};
use std::{
    env,
    fmt::Write as _,
    fs,
    path::{Path, PathBuf},
};

const FORMAT: &str = "sava-solana-ed25519-curve-v1";
const AGAVE: &str = "v4.2.0 ac82b5d438b0c2303dc7169f52c748977713a111";
const AGAVE_CARGO_LOCK_SHA256: &str =
    "5f29b3869fa78fae8f7780ba10b428198c1c3e5c0ac39153485a942931908557";
const SOLANA_PUBKEY: &str = "4.2.0 5b985fd7b60de1c845c25bb2d4fc16e19c9ee6ab";
const SOLANA_PUBKEY_CHECKSUM: &str =
    "7db719574990de7e8b0f55a8593ac92a5ccb42c8ce67b3e4bf05b139d5d9ee71";
const SOLANA_ADDRESS: &str = "2.6.1 14a725d6e9180e6cfbd98054473d61ef3aabde57";
const SOLANA_ADDRESS_CHECKSUM: &str =
    "39c93e262f671bf402e1040e4a7e40b05d81da5956c7681948c975a0997517bb";
const SOLANA_CURVE25519: &str = "4.0.1 a947d32fb1ab7d06b69bd96bd97eb4d002eb454e";
const SOLANA_CURVE25519_CHECKSUM: &str =
    "14b4d2a4bf0d0b0a86c22111917e86e8bd39a7b31420fb2c7d73eb83761fc7af";
const CURVE25519_DALEK: &str = "4.1.3 5312a0311ec40df95be953eacfa8a11b9a34bc54";
const CURVE25519_DALEK_CHECKSUM: &str =
    "97fb8b7c4503de7d6ae7b42ab72a5a59857b4c937ec27a3d4539dba95b5ab2be";
const SHA2_CHECKSUM: &str = "a7507d819769d01a365ab707794a4084392c824f54a7a6a7862f8c3d0892b283";
const SHA256_DOMAIN: &[u8] = b"sava:solana-ed25519-curve:v1";
const CATEGORY_COUNTS: &str =
    "sentinel=2,torsion=8,reduced=38,noncanonical=38,low_boundary=128,high_boundary=128,one_hot=510,sha256=512";
const ROW_COUNT: usize = 1_364;

const P: [u8; 32] = [
    0xed, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x7f,
];

const TORSION: [[u8; 32]; 8] = [
    hex("0100000000000000000000000000000000000000000000000000000000000000"),
    hex("0000000000000000000000000000000000000000000000000000000000000000"),
    hex("0000000000000000000000000000000000000000000000000000000000000080"),
    hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
    hex("c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a"),
    hex("c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa"),
    hex("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05"),
    hex("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85"),
];

#[derive(Debug)]
struct Row {
    id: String,
    category: &'static str,
    compressed: [u8; 32],
    sdk_on_curve: bool,
    agave_runtime_backend_on_curve: bool,
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
    assert_locked_package(&lock, "solana-pubkey", "4.2.0", SOLANA_PUBKEY_CHECKSUM)?;
    assert_locked_package(&lock, "solana-address", "2.6.1", SOLANA_ADDRESS_CHECKSUM)?;
    assert_locked_package(
        &lock,
        "solana-curve25519",
        "4.0.1",
        SOLANA_CURVE25519_CHECKSUM,
    )?;
    assert_locked_package(
        &lock,
        "curve25519-dalek",
        "4.1.3",
        CURVE25519_DALEK_CHECKSUM,
    )?;
    assert_locked_package(&lock, "sha2", "0.10.9", SHA2_CHECKSUM)?;

    let fixture = generate(&lock, &manifest, &generator, &toolchain)?;
    let fixture_path = manifest_dir.join("../../resources/ed25519/solana-curve-vectors.tsv");
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
    let rows = vectors()?;
    if rows.len() != ROW_COUNT {
        return Err(format!(
            "generated {} rows, expected {ROW_COUNT}",
            rows.len()
        ));
    }

    let lock_sha256 = encode_hex(&Sha256::digest(lock.as_bytes()));
    let manifest_sha256 = encode_hex(&Sha256::digest(manifest));
    let generator_sha256 = encode_hex(&Sha256::digest(generator));
    let toolchain_sha256 = encode_hex(&Sha256::digest(toolchain));
    let mut output = String::new();
    writeln!(output, "# format: {FORMAT}").unwrap();
    writeln!(
        output,
        "# property: Sava isNotOnCurve is the logical negation of Solana bytes_are_curve_point"
    )
    .unwrap();
    writeln!(output, "# agave: {AGAVE}").unwrap();
    writeln!(
        output,
        "# agave-cargo-lock-sha256: {AGAVE_CARGO_LOCK_SHA256}"
    )
    .unwrap();
    writeln!(output, "# solana-pubkey: {SOLANA_PUBKEY}").unwrap();
    writeln!(
        output,
        "# solana-pubkey-crate-checksum: {SOLANA_PUBKEY_CHECKSUM}"
    )
    .unwrap();
    writeln!(output, "# solana-address: {SOLANA_ADDRESS}").unwrap();
    writeln!(
        output,
        "# solana-address-crate-checksum: {SOLANA_ADDRESS_CHECKSUM}"
    )
    .unwrap();
    writeln!(output, "# solana-curve25519: {SOLANA_CURVE25519}").unwrap();
    writeln!(
        output,
        "# solana-curve25519-crate-checksum: {SOLANA_CURVE25519_CHECKSUM}"
    )
    .unwrap();
    writeln!(output, "# curve25519-dalek: {CURVE25519_DALEK}").unwrap();
    writeln!(
        output,
        "# curve25519-dalek-crate-checksum: {CURVE25519_DALEK_CHECKSUM}"
    )
    .unwrap();
    writeln!(output, "# sha2: 0.10.9").unwrap();
    writeln!(output, "# sha2-crate-checksum: {SHA2_CHECKSUM}").unwrap();
    writeln!(output, "# cargo-lock-sha256: {lock_sha256}").unwrap();
    writeln!(output, "# cargo-manifest-sha256: {manifest_sha256}").unwrap();
    writeln!(output, "# generator-source-sha256: {generator_sha256}").unwrap();
    writeln!(output, "# rust-toolchain: 1.96.1").unwrap();
    writeln!(output, "# rust-toolchain-sha256: {toolchain_sha256}").unwrap();
    writeln!(
        output,
        "# sha256-domain: {}",
        std::str::from_utf8(SHA256_DOMAIN).unwrap()
    )
    .unwrap();
    writeln!(output, "# category-counts: {CATEGORY_COUNTS}").unwrap();
    writeln!(output, "# rows: {ROW_COUNT}").unwrap();
    writeln!(
        output,
        "id\tcategory\tcompressed_hex\tsdk_on_curve\tagave_runtime_backend_on_curve"
    )
    .unwrap();
    for row in rows {
        writeln!(
            output,
            "{}\t{}\t{}\t{}\t{}",
            row.id,
            row.category,
            encode_hex(&row.compressed),
            row.sdk_on_curve,
            row.agave_runtime_backend_on_curve
        )
        .unwrap();
    }
    Ok(output)
}

fn vectors() -> Result<Vec<Row>, String> {
    let mut rows = Vec::with_capacity(ROW_COUNT);
    add(
        &mut rows,
        "sentinel_basepoint",
        "sentinel",
        hex("5866666666666666666666666666666666666666666666666666666666666666"),
    )?;
    add(&mut rows, "sentinel_all_ff", "sentinel", [0xff; 32])?;

    for (index, compressed) in TORSION.into_iter().enumerate() {
        add(
            &mut rows,
            format!("torsion_{index:02}"),
            "torsion",
            compressed,
        )?;
    }

    for k in 0_u8..19 {
        let mut reduced = [0_u8; 32];
        reduced[0] = k;
        add_both_signs(&mut rows, &format!("reduced_{k:02}"), "reduced", reduced)?;

        let mut noncanonical = P;
        noncanonical[0] += k;
        add_both_signs(
            &mut rows,
            &format!("noncanonical_{k:02}"),
            "noncanonical",
            noncanonical,
        )?;
    }

    for y in 0_u8..64 {
        let mut compressed = [0_u8; 32];
        compressed[0] = y;
        add_both_signs(
            &mut rows,
            &format!("low_{y:02}"),
            "low_boundary",
            compressed,
        )?;
    }

    for offset in 1_u8..=64 {
        add_both_signs(
            &mut rows,
            &format!("high_p_minus_{offset:02}"),
            "high_boundary",
            subtract_small(P, offset),
        )?;
    }

    for bit in 0_usize..255 {
        let mut compressed = [0_u8; 32];
        compressed[bit >> 3] = 1 << (bit & 7);
        add_both_signs(
            &mut rows,
            &format!("one_hot_{bit:03}"),
            "one_hot",
            compressed,
        )?;
    }

    for counter in 0_u64..512 {
        let mut digest = Sha256::new();
        digest.update(SHA256_DOMAIN);
        digest.update(counter.to_le_bytes());
        let mut compressed = [0_u8; 32];
        compressed.copy_from_slice(&digest.finalize());
        add(
            &mut rows,
            format!("sha256_{counter:03}"),
            "sha256",
            compressed,
        )?;
    }
    Ok(rows)
}

fn add_both_signs(
    rows: &mut Vec<Row>,
    id: &str,
    category: &'static str,
    mut compressed: [u8; 32],
) -> Result<(), String> {
    compressed[31] &= 0x7f;
    add(rows, format!("{id}_sign0"), category, compressed)?;
    compressed[31] |= 0x80;
    add(rows, format!("{id}_sign1"), category, compressed)
}

fn add(
    rows: &mut Vec<Row>,
    id: impl Into<String>,
    category: &'static str,
    compressed: [u8; 32],
) -> Result<(), String> {
    let id = id.into();
    let sdk_on_curve = solana_pubkey::bytes_are_curve_point(compressed);
    let agave_runtime_backend_on_curve = validate_edwards(&PodEdwardsPoint(compressed));
    if sdk_on_curve != agave_runtime_backend_on_curve {
        return Err(format!(
            "Solana SDK/runtime backend disagreement for {id}: sdk={sdk_on_curve}, runtime={agave_runtime_backend_on_curve}, bytes={}",
            encode_hex(&compressed)
        ));
    }
    rows.push(Row {
        id,
        category,
        compressed,
        sdk_on_curve,
        agave_runtime_backend_on_curve,
    });
    Ok(())
}

fn subtract_small(mut value: [u8; 32], amount: u8) -> [u8; 32] {
    let mut borrow = amount as i16;
    for byte in &mut value {
        if borrow == 0 {
            break;
        }
        let difference = *byte as i16 - borrow;
        if difference < 0 {
            *byte = (difference + 256) as u8;
            borrow = 1;
        } else {
            *byte = difference as u8;
            borrow = 0;
        }
    }
    assert_eq!(borrow, 0);
    value
}

fn assert_locked_package(
    lock: &str,
    name: &str,
    version: &str,
    checksum: &str,
) -> Result<(), String> {
    let record = format!(
        "[[package]]\nname = \"{name}\"\nversion = \"{version}\"\nsource = \"registry+https://github.com/rust-lang/crates.io-index\"\nchecksum = \"{checksum}\""
    );
    if lock.contains(&record) {
        Ok(())
    } else {
        Err(format!(
            "Cargo.lock does not contain crates.io {name} {version} with checksum {checksum}"
        ))
    }
}

fn read_bytes(path: &Path) -> Result<Vec<u8>, String> {
    fs::read(path).map_err(|error| format!("failed to read {}: {error}", path.display()))
}

fn write_fixture(path: &Path, fixture: &str) -> Result<(), String> {
    fs::create_dir_all(path.parent().unwrap())
        .map_err(|error| format!("failed to create {}: {error}", path.display()))?;
    fs::write(path, fixture)
        .map_err(|error| format!("failed to write {}: {error}", path.display()))?;
    println!("wrote {}", path.display());
    Ok(())
}

fn check_fixture(path: &Path, fixture: &str) -> Result<(), String> {
    let committed = fs::read_to_string(path)
        .map_err(|error| format!("failed to read {}: {error}", path.display()))?;
    if committed == fixture {
        println!("verified {}", path.display());
        Ok(())
    } else {
        Err(format!(
            "{} is stale; run with --write and review the diff",
            path.display()
        ))
    }
}

fn encode_hex(bytes: &[u8]) -> String {
    let mut encoded = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        write!(encoded, "{byte:02x}").unwrap();
    }
    encoded
}

const fn hex(value: &str) -> [u8; 32] {
    let bytes = value.as_bytes();
    assert!(bytes.len() == 64);
    let mut decoded = [0_u8; 32];
    let mut index = 0;
    while index < decoded.len() {
        decoded[index] = (nibble(bytes[index * 2]) << 4) | nibble(bytes[index * 2 + 1]);
        index += 1;
    }
    decoded
}

const fn nibble(value: u8) -> u8 {
    match value {
        b'0'..=b'9' => value - b'0',
        b'a'..=b'f' => value - b'a' + 10,
        _ => panic!("invalid hex"),
    }
}
