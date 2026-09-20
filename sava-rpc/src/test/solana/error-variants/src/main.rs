//! Emits the committed fixture of upstream error-enum variants.
//!
//! The oracle is each enum's own `VARIANTS` constant. Its completeness is not a property
//! of the array's declared length, which only fixes how many elements the literal holds;
//! it is established by upstream's own tests, `test_transaction_error_variants_exhaustive`
//! and `test_instruction_error_variants_exhaustive`, which iterate every variant through
//! `strum::EnumIter` and assert each one is in `VARIANTS`. Relying on those tests is what
//! lets this program avoid parsing Rust source.
//!
//! Each row carries the variant's name and its shape, taken from the `Debug` rendering of
//! the array element: a unit variant renders as a bare name, a tuple variant as `Name(..)`,
//! a struct variant as `Name { .. }`. The Java side mirrors payload variants as records with
//! components, so the shape column lets the test check that no payload is dropped. It does
//! not carry the payload's field count, so it is a presence check, not a schema check.

use sha2::{Digest, Sha256};
use solana_instruction_error::InstructionError;
use solana_transaction_error::TransactionError;
use std::{
    env,
    fmt::Write as _,
    fs,
    path::{Path, PathBuf},
};

const FORMAT: &str = "sava-solana-error-variants-v1";
const TRANSACTION_ERROR: &str = "4.0.0";
/// From the crate's `.cargo_vcs_info.json`: the solana-sdk commit it was published from.
const TRANSACTION_ERROR_PUBLISHED_FROM: &str = "9d02e6dc068042257360dceef3f1fa7edd9527e6";
const INSTRUCTION_ERROR: &str = "3.0.0";
const INSTRUCTION_ERROR_PUBLISHED_FROM: &str = "5bcc7778a4f3d5ebb95735efeb7f1bae49122f9a";
/// The clone HEAD both source trees were reviewed against; unchanged since publication.
const SOLANA_SDK_REVIEWED: &str = "983858e1bb8ef9bd3c61f4a5172c705dae1f566b";
const TRANSACTION_ERROR_COUNT: usize = 40;
const INSTRUCTION_ERROR_COUNT: usize = 55;
const COLUMNS: &str = "enum\tindex\tvariant\tshape";

struct Row {
    enum_name: &'static str,
    index: usize,
    variant: String,
    shape: &'static str,
}

/// Splits a `Debug` rendering into its variant name and its shape.
fn split(debug: &str) -> Result<(String, &'static str), String> {
    let name: String = debug
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
        .collect();
    if name.is_empty() {
        return Err(format!("no variant name in {debug:?}"));
    }
    let shape = match debug[name.len()..].trim_start().chars().next() {
        Some('(') => "tuple",
        Some('{') => "struct",
        None => "unit",
        Some(other) => return Err(format!("unexpected {other:?} after {name} in {debug:?}")),
    };
    Ok((name, shape))
}

fn rows() -> Result<Vec<Row>, String> {
    let mut rows = Vec::with_capacity(TRANSACTION_ERROR_COUNT + INSTRUCTION_ERROR_COUNT);
    for (index, variant) in TransactionError::VARIANTS.iter().enumerate() {
        let (variant, shape) = split(&format!("{variant:?}"))?;
        rows.push(Row { enum_name: "TransactionError", index, variant, shape });
    }
    for (index, variant) in InstructionError::VARIANTS.iter().enumerate() {
        let (variant, shape) = split(&format!("{variant:?}"))?;
        rows.push(Row { enum_name: "InstructionError", index, variant, shape });
    }
    Ok(rows)
}

fn generate(lock: &[u8], manifest: &[u8], generator: &[u8], toolchain: &[u8]) -> Result<String, String> {
    let rows = rows()?;
    if TransactionError::VARIANTS.len() != TRANSACTION_ERROR_COUNT {
        return Err("TransactionError::VARIANTS length changed".into());
    }
    if InstructionError::VARIANTS.len() != INSTRUCTION_ERROR_COUNT {
        return Err("InstructionError::VARIANTS length changed".into());
    }
    let mut out = String::new();
    writeln!(out, "# format: {FORMAT}").unwrap();
    writeln!(out, "# property: every upstream error variant is mirrored by a Java record, and a variant with a payload by a record with components").unwrap();
    writeln!(out, "# solana-transaction-error: {TRANSACTION_ERROR}").unwrap();
    writeln!(out, "# solana-transaction-error-published-from: {TRANSACTION_ERROR_PUBLISHED_FROM}").unwrap();
    writeln!(out, "# solana-instruction-error: {INSTRUCTION_ERROR}").unwrap();
    writeln!(out, "# solana-instruction-error-published-from: {INSTRUCTION_ERROR_PUBLISHED_FROM}").unwrap();
    writeln!(out, "# solana-sdk-reviewed: {SOLANA_SDK_REVIEWED}").unwrap();
    writeln!(out, "# cargo-lock-sha256: {}", hex(&Sha256::digest(lock))).unwrap();
    writeln!(out, "# cargo-manifest-sha256: {}", hex(&Sha256::digest(manifest))).unwrap();
    writeln!(out, "# generator-source-sha256: {}", hex(&Sha256::digest(generator))).unwrap();
    writeln!(out, "# rust-toolchain-sha256: {}", hex(&Sha256::digest(toolchain))).unwrap();
    writeln!(out, "# transaction-error-variants: {TRANSACTION_ERROR_COUNT}").unwrap();
    writeln!(out, "# instruction-error-variants: {INSTRUCTION_ERROR_COUNT}").unwrap();
    writeln!(out, "{COLUMNS}").unwrap();
    for row in rows {
        writeln!(out, "{}\t{}\t{}\t{}", row.enum_name, row.index, row.variant, row.shape).unwrap();
    }
    Ok(out)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut s, b| {
        write!(s, "{b:02x}").unwrap();
        s
    })
}

fn main() -> Result<(), String> {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    let mode = match arguments.as_slice() {
        [mode] if mode == "--write" || mode == "--check" => mode.clone(),
        _ => return Err("usage: cargo run --locked --release -- --write|--check".into()),
    };
    let here = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let read = |name: &str| fs::read(here.join(name)).map_err(|e| format!("{name}: {e}"));
    let fixture = generate(
        &read("Cargo.lock")?,
        &read("Cargo.toml")?,
        &read("src/main.rs")?,
        &read("rust-toolchain.toml")?,
    )?;
    let path = here.join("../../resources/upstream/solana-error-variants.tsv");
    if mode == "--write" {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        fs::write(&path, &fixture).map_err(|e| e.to_string())?;
        println!("wrote {}", Path::new(&path).display());
    } else {
        let committed = fs::read_to_string(&path).map_err(|e| e.to_string())?;
        if committed != fixture {
            return Err("committed fixture differs from generated bytes".into());
        }
        println!("fixture matches");
    }
    Ok(())
}
