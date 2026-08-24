/**
 * Generates `sava-core/src/test/resources/tx/kit-v1-message-vectors.tsv` from @solana/kit 8.0.0.
 *
 *     pnpm exec tsx gen.ts --write   regenerate the committed fixture
 *     pnpm exec tsx gen.ts --check   regenerate to memory and diff against the committed fixture
 *
 * Every byte in the fixture is produced by kit: `compileTransaction` emits the unsigned message,
 * `signTransactionMessageWithSigners` the signed wire, and kit's own decoders the read-back
 * columns. Sava contributes nothing, so the fixture is an oracle for Sava's builder, signer, and
 * reader that shares no code and no author with them. See README.md for what each vector pins.
 */
import { getAddMemoInstruction } from '@solana-program/memo';
import { getTransferSolInstruction } from '@solana-program/system';
import {
    address,
    appendTransactionMessageInstruction,
    blockhash,
    compileTransaction,
    createKeyPairSignerFromPrivateKeyBytes,
    createTransactionMessage,
    decompileTransactionMessage,
    getBase58Decoder,
    getBase64Encoder,
    getBase64EncodedWireTransaction,
    getCompiledTransactionMessageDecoder,
    getTransactionDecoder,
    lamports,
    pipe,
    setTransactionMessageConfig,
    setTransactionMessageFeePayerSigner,
    setTransactionMessageLifetimeUsingBlockhash,
    signTransactionMessageWithSigners,
    type Instruction,
    type KeyPairSigner,
    type V1TransactionConfig,
} from '@solana/kit';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const GENERATOR_DIR = dirname(fileURLToPath(import.meta.url));
const FIXTURE_PATH = resolve(GENERATOR_DIR, '../../resources/tx/kit-v1-message-vectors.tsv');

const FORMAT = 'sava-kit-v1-message-v1';
const PROPERTY =
    "Sava's transaction v1 builder, signer, and reader agree with @solana/kit's compiler, signer, " +
    'and decoders on SIMD-0385 wire bytes';
const SIMD = '0385-transaction-v1';

/** Exact versions the fixture is generated against; package.json and pnpm-lock.yaml must agree. */
const PINS = {
    '@solana/kit': '8.0.0',
    '@solana-program/system': '0.13.0',
    '@solana-program/memo': '0.13.0',
    tsx: '4.23.12',
} as const;

const SIGNATURE_LENGTH = 64;
const V1_VERSION_BYTE = 0x81;

const ED25519_PROGRAM = address('Ed25519SigVerify111111111111111111111111111');

const hex = (bytes: ArrayLike<number>): string => Buffer.from(bytes as Uint8Array).toString('hex');
const sha256 = (bytes: Uint8Array | string): string => createHash('sha256').update(bytes).digest('hex');
const seed = (value: number): Uint8Array => new Uint8Array(32).fill(value);

const PAYER_SEED = seed(0x01);
const RECIPIENT_SEED = seed(0x02);
const SECOND_SEED = seed(0x03);
const BLOCKHASH_BYTES = seed(0x04);

const EXAMPLE_CONFIG = {
    computeUnitLimit: 20_000,
    heapSize: 64 * 1024,
    loadedAccountsDataSizeLimit: 64 * 1024,
    priorityFeeLamports: 5_000n,
} as const satisfies Required<V1TransactionConfig>;

/** Sava's TxBuilder defaults: CU limit and data size always serialized, fee and heap unset. */
const SAVA_DEFAULT_CONFIG = {
    computeUnitLimit: 1_400_000,
    loadedAccountsDataSizeLimit: 64 * 1024 * 1024,
} as const satisfies V1TransactionConfig;

const LARGE_MEMO = 'M'.repeat(3000);
const SHORT_MEMO = 'sava kit v1 vectors';
/** Ed25519 precompile payload: num_signatures = 0, one padding byte. Account free and valid. */
const ED25519_NO_SIGNATURES = new Uint8Array([0, 0]);

type Extra = 'none' | 'second-signer' | 'memo' | 'memo+ed25519';

type Case = {
    readonly id: string;
    readonly config?: V1TransactionConfig;
    readonly extra: Extra;
};

const CASES: readonly Case[] = [
    { id: 'a_example_config', config: EXAMPLE_CONFIG, extra: 'none' },
    { id: 'b_empty_config', extra: 'none' },
    { id: 'c_only_cu_limit', config: { computeUnitLimit: 20_000 }, extra: 'none' },
    { id: 'd_only_priority_fee', config: { priorityFeeLamports: 5_000n }, extra: 'none' },
    {
        id: 'e_explicit_zeros',
        config: { computeUnitLimit: 0, loadedAccountsDataSizeLimit: 0, priorityFeeLamports: 0n },
        extra: 'none',
    },
    { id: 'f_two_signers', config: EXAMPLE_CONFIG, extra: 'second-signer' },
    { id: 'g_large_memo', config: { computeUnitLimit: 1_400_000 }, extra: 'memo' },
    { id: 'h_sava_defaults', config: SAVA_DEFAULT_CONFIG, extra: 'none' },
    { id: 'i_two_readonly_programs', config: EXAMPLE_CONFIG, extra: 'memo+ed25519' },
];

const COLUMNS = [
    'id',
    'config',
    'extra',
    'config_mask',
    'header',
    'static_accounts',
    'program_ids',
    'instruction_data_hex',
    'unsigned_message_hex',
    'signed_wire_hex',
    'signatures_hex',
    'read_back_version',
    'read_back_config',
] as const;

class GeneratorError extends Error {}

function fail(message: string): never {
    throw new GeneratorError(message);
}

function expect(condition: boolean, message: string): asserts condition {
    if (!condition) {
        fail(message);
    }
}

/** `priority_fee:compute_unit_limit:loaded_accounts_data_size_limit:heap_size`, `-` when absent. */
function formatConfig(config: V1TransactionConfig | undefined): string {
    if (config === undefined) {
        return 'none';
    }
    const field = (value: number | bigint | undefined): string => (value === undefined ? '-' : String(value));
    return [
        field(config.priorityFeeLamports),
        field(config.computeUnitLimit),
        field(config.loadedAccountsDataSizeLimit),
        field(config.heapSize),
    ].join(':');
}

function readJson(path: string): Record<string, unknown> {
    return JSON.parse(readFileSync(path, 'utf8')) as Record<string, unknown>;
}

/**
 * Refuses to generate unless package.json pins exactly the versions above, the lockfile resolves
 * them, and the installed packages are those versions. The fixture then records what produced it.
 */
function assertPinned(packageJson: string, lock: string): void {
    const dependencies = (JSON.parse(packageJson) as { dependencies?: Record<string, string> }).dependencies ?? {};
    const pinned = Object.keys(PINS).sort();
    expect(
        JSON.stringify(Object.keys(dependencies).sort()) === JSON.stringify(pinned),
        `package.json dependencies must be exactly ${pinned.join(', ')}`,
    );
    for (const [name, version] of Object.entries(PINS)) {
        expect(dependencies[name] === version, `package.json must pin ${name} to exactly ${version}`);
        expect(lock.includes(`${name}@${version}`), `pnpm-lock.yaml does not resolve ${name}@${version}`);
        const installed = readJson(resolve(GENERATOR_DIR, 'node_modules', name, 'package.json')).version;
        expect(installed === version, `installed ${name} is ${String(installed)}, expected ${version}`);
    }
}

type Row = Record<(typeof COLUMNS)[number], string>;

type CompiledV1 = {
    readonly staticAccounts: readonly string[];
    readonly header: {
        readonly numSignerAccounts: number;
        readonly numReadonlySignerAccounts: number;
        readonly numReadonlyNonSignerAccounts: number;
    };
    readonly configMask?: number;
};

async function generateRow(
    testCase: Case,
    payer: KeyPairSigner,
    recipient: KeyPairSigner,
    second: KeyPairSigner,
    lifetime: { readonly blockhash: ReturnType<typeof blockhash>; readonly lastValidBlockHeight: bigint },
): Promise<Row> {
    let message = pipe(
        createTransactionMessage({ version: 1 }),
        m => setTransactionMessageFeePayerSigner(payer, m),
        m => setTransactionMessageLifetimeUsingBlockhash(lifetime, m),
        m =>
            appendTransactionMessageInstruction(
                getTransferSolInstruction({ amount: lamports(1n), destination: recipient.address, source: payer }),
                m,
            ),
    );
    switch (testCase.extra) {
        case 'none':
            break;
        case 'second-signer':
            message = appendTransactionMessageInstruction(
                getTransferSolInstruction({ amount: lamports(1n), destination: recipient.address, source: second }),
                message,
            );
            break;
        case 'memo':
            message = appendTransactionMessageInstruction(getAddMemoInstruction({ memo: LARGE_MEMO }), message);
            break;
        case 'memo+ed25519': {
            const ed25519: Instruction = { data: ED25519_NO_SIGNATURES, programAddress: ED25519_PROGRAM };
            message = pipe(
                message,
                m => appendTransactionMessageInstruction(getAddMemoInstruction({ memo: SHORT_MEMO }), m),
                m => appendTransactionMessageInstruction(ed25519, m),
            );
            break;
        }
    }
    if (testCase.config !== undefined) {
        message = setTransactionMessageConfig(testCase.config, message);
    }

    const unsigned = compileTransaction(message);
    const signed = await signTransactionMessageWithSigners(message);
    const wire = getBase64Encoder().encode(getBase64EncodedWireTransaction(signed));
    const messageBytes = unsigned.messageBytes;

    // Kit's own read back of its own bytes: the decoded version and config are recorded so the
    // consumer can require Sava's reading to agree with kit's, not only with the input.
    const decodedTransaction = getTransactionDecoder().decode(wire);
    const compiled = getCompiledTransactionMessageDecoder().decode(decodedTransaction.messageBytes);
    const decompiled = decompileTransactionMessage(compiled);
    const readBackConfig = 'config' in decompiled ? (decompiled.config as V1TransactionConfig | undefined) : undefined;
    const compiledV1 = compiled as unknown as CompiledV1;

    // The generator states its own expectations of kit; a disagreement aborts generation rather
    // than silently rewriting the fixture.
    const id = testCase.id;
    expect(messageBytes[0] === V1_VERSION_BYTE, `${id}: compiled message does not lead with 0x81`);
    expect(compiledV1.configMask !== undefined, `${id}: compiled v1 message reports no config mask`);
    expect(decompiled.version === 1, `${id}: kit decoded version ${String(decompiled.version)}`);
    const numSigners = compiledV1.header.numSignerAccounts;
    expect(
        wire.length === messageBytes.length + numSigners * SIGNATURE_LENGTH,
        `${id}: wire is not message + ${numSigners} trailing signatures`,
    );
    expect(hex(wire.subarray(0, messageBytes.length)) === hex(messageBytes), `${id}: signed wire does not start with the message`);
    expect(
        hex(decodedTransaction.messageBytes) === hex(messageBytes),
        `${id}: kit's transaction decoder recovered different message bytes`,
    );
    expect(
        formatConfig(readBackConfig) === formatConfig(testCase.config),
        `${id}: kit read back ${formatConfig(readBackConfig)} for input ${formatConfig(testCase.config)}`,
    );
    if (id === 'e_explicit_zeros') {
        expect(compiledV1.configMask === 0b1111, `${id}: explicit zeros must keep their mask bits, mask=${compiledV1.configMask}`);
    }

    // Signatures in signer order: the first numSignerAccounts static accounts are the signers.
    const signatures = compiledV1.staticAccounts.slice(0, numSigners).map(account => {
        const signature = signed.signatures[account as keyof typeof signed.signatures];
        expect(signature !== null && signature !== undefined, `${id}: no signature for signer ${account}`);
        return hex(signature);
    });
    expect(
        Object.keys(signed.signatures).length === numSigners,
        `${id}: kit holds ${Object.keys(signed.signatures).length} signatures for ${numSigners} signers`,
    );

    return {
        id,
        config: formatConfig(testCase.config),
        extra: testCase.extra,
        config_mask: String(compiledV1.configMask),
        header: [
            compiledV1.header.numSignerAccounts,
            compiledV1.header.numReadonlySignerAccounts,
            compiledV1.header.numReadonlyNonSignerAccounts,
        ].join(':'),
        static_accounts: compiledV1.staticAccounts.join(','),
        program_ids: message.instructions.map(instruction => instruction.programAddress as string).join(','),
        instruction_data_hex: message.instructions.map(instruction => hex(instruction.data ?? new Uint8Array())).join(','),
        unsigned_message_hex: hex(messageBytes),
        signed_wire_hex: hex(wire),
        signatures_hex: signatures.join(','),
        read_back_version: String(decompiled.version),
        read_back_config: formatConfig(readBackConfig),
    };
}

async function generate(): Promise<string> {
    const packageJson = readFileSync(resolve(GENERATOR_DIR, 'package.json'), 'utf8');
    const lock = readFileSync(resolve(GENERATOR_DIR, 'pnpm-lock.yaml'), 'utf8');
    const workspace = readFileSync(resolve(GENERATOR_DIR, 'pnpm-workspace.yaml'), 'utf8');
    const generator = readFileSync(fileURLToPath(import.meta.url), 'utf8');
    assertPinned(packageJson, lock);

    const payer = await createKeyPairSignerFromPrivateKeyBytes(PAYER_SEED);
    const recipient = await createKeyPairSignerFromPrivateKeyBytes(RECIPIENT_SEED);
    const second = await createKeyPairSignerFromPrivateKeyBytes(SECOND_SEED);
    const lifetime = {
        blockhash: blockhash(getBase58Decoder().decode(BLOCKHASH_BYTES)),
        lastValidBlockHeight: 100n,
    } as const;

    const rows: Row[] = [];
    for (const testCase of CASES) {
        rows.push(await generateRow(testCase, payer, recipient, second, lifetime));
    }
    const ids = new Set(rows.map(row => row.id));
    expect(ids.size === rows.length, 'duplicate vector id');
    for (const row of rows) {
        for (const column of COLUMNS) {
            const value = row[column];
            expect(value.length > 0, `${row.id}: empty ${column}`);
            expect(!/[\t\n\r]/.test(value), `${row.id}: ${column} contains a tab or newline`);
        }
    }

    const metadata: readonly (readonly [string, string])[] = [
        ['format', FORMAT],
        ['property', PROPERTY],
        ['simd', SIMD],
        ['@solana/kit', PINS['@solana/kit']],
        ['@solana-program/system', PINS['@solana-program/system']],
        ['@solana-program/memo', PINS['@solana-program/memo']],
        ['tsx', PINS.tsx],
        ['node', process.version],
        ['payer-seed-hex', hex(PAYER_SEED)],
        ['recipient-seed-hex', hex(RECIPIENT_SEED)],
        ['second-seed-hex', hex(SECOND_SEED)],
        ['blockhash-hex', hex(BLOCKHASH_BYTES)],
        ['payer-address', payer.address],
        ['recipient-address', recipient.address],
        ['second-address', second.address],
        ['blockhash-base58', lifetime.blockhash],
        ['same-role-ordering', 'kit sorts same-role accounts by Intl.Collator(en) over base58; Sava leaves them unordered'],
        ['package-json-sha256', sha256(packageJson)],
        ['pnpm-lock-sha256', sha256(lock)],
        ['pnpm-workspace-sha256', sha256(workspace)],
        ['generator-source-sha256', sha256(generator)],
        ['vectors', String(rows.length)],
    ];
    for (const [key, value] of metadata) {
        expect(!key.includes(': ') && !/[\t\n\r]/.test(value), `metadata ${key} is not representable`);
    }

    let output = '';
    for (const [key, value] of metadata) {
        output += `# ${key}: ${value}\n`;
    }
    output += `${COLUMNS.join('\t')}\n`;
    for (const row of rows) {
        output += `${COLUMNS.map(column => row[column]).join('\t')}\n`;
    }
    return output;
}

function check(fixture: string): void {
    let committed: string;
    try {
        committed = readFileSync(FIXTURE_PATH, 'utf8');
    } catch (error) {
        fail(`failed to read ${FIXTURE_PATH}: ${error instanceof Error ? error.message : String(error)}`);
    }
    if (committed === fixture) {
        return;
    }
    const committedLines = committed.split('\n');
    const generatedLines = fixture.split('\n');
    const lineCount = Math.max(committedLines.length, generatedLines.length);
    for (let line = 0; line < lineCount; ++line) {
        if (committedLines[line] !== generatedLines[line]) {
            const preview = (text: string | undefined): string =>
                text === undefined ? '<missing>' : text.length > 120 ? `${text.slice(0, 120)}...` : text;
            fail(
                `${FIXTURE_PATH} is stale; regenerate with --write\n` +
                    `  first difference at line ${line + 1}\n` +
                    `  committed: ${preview(committedLines[line])}\n` +
                    `  generated: ${preview(generatedLines[line])}`,
            );
        }
    }
    fail(`${FIXTURE_PATH} is stale; regenerate with --write`);
}

async function main(): Promise<void> {
    const args = process.argv.slice(2);
    const mode = args.length === 1 && (args[0] === '--write' || args[0] === '--check') ? args[0] : undefined;
    if (mode === undefined) {
        fail('usage: pnpm exec tsx gen.ts --write|--check');
    }
    const fixture = await generate();
    if (mode === '--write') {
        writeFileSync(FIXTURE_PATH, fixture);
        console.log(`wrote ${FIXTURE_PATH} (${CASES.length} vectors)`);
    } else {
        check(fixture);
        console.log(`${FIXTURE_PATH} is up to date (${CASES.length} vectors)`);
    }
}

try {
    await main();
} catch (error) {
    console.error(error instanceof GeneratorError ? `error: ${error.message}` : error);
    process.exit(1);
}
