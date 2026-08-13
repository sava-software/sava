plugins {
  id("software.sava.build.feature.hardening")
  id("sava.docs-in-sync")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  // suites target by package wildcard with exclusions, never allowlist, so a
  // new class is mutated by default instead of silently skipped (policy:
  // sava-build's HARDENING.md); packages without a suite are deliberate scope
  // decisions, not omissions
  mutation.register("borsh") {
    targetClasses = listOf("software.sava.core.borsh.*")
    excludedClasses = listOf(
      "software.sava.core.borsh.*Test*",
      "software.sava.core.borsh.*Fuzz*"
    )
    targetTests = "software.sava.core.borsh.*Test*"
  }
  mutation.register("ed25519") {
    targetClasses = listOf("software.sava.core.crypto.ed25519.*")
    excludedClasses = listOf(
      "software.sava.core.crypto.ed25519.*Test*",
      "software.sava.core.crypto.ed25519.*Fuzz*"
    )
    targetTests = "software.sava.core.crypto.ed25519.*Test*"
  }
  mutation.register("encoding") {
    targetClasses = listOf("software.sava.core.encoding.*")
    excludedClasses = listOf(
      "software.sava.core.encoding.*Test*",
      "software.sava.core.encoding.*Fuzz*"
    )
    targetTests = "software.sava.core.encoding.*Test*"
  }
  mutation.register("tx") {
    targetClasses = listOf(
      "software.sava.core.tx.*",
      "software.sava.core.accounts.lookup.*"
    )
    excludedClasses = listOf(
      "software.sava.core.tx.*Test*",
      "software.sava.core.tx.*Fuzz*",
      "software.sava.core.accounts.lookup.*Test*"
    )
    targetTests = "software.sava.core.tx.*Test*,software.sava.core.accounts.lookup.*Test*"
    // fluent receiver-returning calls (String formatting, iterator chains) are
    // invisible to VoidMethodCall; fired in the 2026-07-22 trial (HARDENING_NOTES.md)
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
  mutation.register("token2022") {
    targetClasses = listOf("software.sava.core.accounts.token.*")
    // the tests share the package with what they mutate, so they need excluding
    // by name the way every other suite does
    excludedClasses = listOf(
      "software.sava.core.accounts.token.*Test*",
      "software.sava.core.accounts.token.*Fuzz*"
    )
    targetTests = "software.sava.core.accounts.token.*Test*"
  }
  mutation.register("meta") {
    // the account privilege lattice: merge() decides the transaction header and,
    // via invoked(), whether an account may be moved into a lookup table
    targetClasses = listOf("software.sava.core.accounts.meta.*")
    excludedClasses = listOf("software.sava.core.accounts.meta.*Test*")
    targetTests = "software.sava.core.accounts.meta.*Test*"
    // fired in the 2026-07-22 NAKED_RECEIVER trial (HARDENING_NOTES.md)
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
  mutation.register("crypto") {
    // hashing primitives: sha256Twice and h160 have no caller in this repo, so
    // tests are the only thing that would notice them breaking
    targetClasses = listOf("software.sava.core.crypto.*")
    excludedClasses = listOf(
      // the ed25519 subpackage has its own suite; the wildcard above spans dots
      "software.sava.core.crypto.ed25519.*",
      "software.sava.core.crypto.*Test*"
    )
    // a wildcard rather than a list, so a new test in this package feeds the suite
    // instead of leaving its mutants looking uncovered; the ed25519 tests it also
    // matches are cheap and their own suite still owns that subpackage
    targetTests = "software.sava.core.crypto.*Tests"
  }
  mutation.register("vanity") {
    // Widened to the whole package 2026-08-04, retiring the Subsequence-only
    // allowlist. The allowlist existed because the workers search an unbounded
    // loop, so a mutant that breaks the match predicate would spin to the PIT
    // timeout rather than fail fast. That argument is now stale: BaseMaskWorker
    // carries a bounded-attempts seam (`searchExhausted`, whose javadoc names
    // tests as its reason for existing) and MaskWorkerTests already drives every
    // worker with a finite maxSearches, so a broken predicate exhausts the cap
    // and returns instead of hanging.
    targetClasses = listOf("software.sava.core.accounts.vanity.*")
    excludedClasses = listOf(
      "software.sava.core.accounts.vanity.*Test*",
      // a test fake named for its role, so it matches no *Test* pattern
      "software.sava.core.accounts.vanity.FixedSeedSecureRandom"
    )
    targetTests = "software.sava.core.accounts.vanity.*Test*"
    // fired in the 2026-07-22 NAKED_RECEIVER trial (HARDENING_NOTES.md)
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
  mutation.register("accounts") {
    // the library's central value types: PublicKey and its byte-array backing,
    // signers, PDA derivation, and the well-known-address table. A wrong offset
    // or a reversed comparison here is silent and reaches every caller.
    targetClasses = listOf("software.sava.core.accounts.*")
    excludedClasses = listOf(
      // the wildcard spans dots, so every sub-package with its own suite has to
      // be subtracted here or two suites would mutate the same classes
      "software.sava.core.accounts.lookup.*",
      "software.sava.core.accounts.meta.*",
      "software.sava.core.accounts.token.*",
      "software.sava.core.accounts.vanity.*",
      "software.sava.core.accounts.sysvar.*",
      "software.sava.core.accounts.pbkdf.*",
      "software.sava.core.accounts.*Test*"
    )
    targetTests = "software.sava.core.accounts.*Test*"
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
  mutation.register("sysvar") {
    // sysvar accounts are parsed from bytes an untrusted node hands back; a
    // mis-sized field or a skipped bounds check is exactly this repo's threat model
    targetClasses = listOf("software.sava.core.accounts.sysvar.*")
    excludedClasses = listOf("software.sava.core.accounts.sysvar.*Test*")
    targetTests = "software.sava.core.accounts.sysvar.*Test*"
  }
  mutation.register("pbkdf") {
    // key derivation and envelope encryption for stored keys. Deliberately slow
    // primitives, so the covering tests pin PBKDF2 to MIN_ITERATIONS and hold a
    // @ResourceLock around memory-hard Argon2id; keep that when adding tests, or
    // this suite's cost multiplies by the mutant count.
    targetClasses = listOf("software.sava.core.accounts.pbkdf.*")
    excludedClasses = listOf("software.sava.core.accounts.pbkdf.*Test*")
    targetTests = "software.sava.core.accounts.pbkdf.*Test*"
  }
  mutation.register("primitives") {
    // the small cross-cutting types that belong to no larger package: RPC
    // account filters, instruction discriminators, the serialization contract,
    // and the ElGamal pubkey wrapper
    targetClasses = listOf(
      "software.sava.core.rpc.*",
      "software.sava.core.programs.*",
      "software.sava.core.serial.*",
      "software.sava.core.zk.*"
    )
    excludedClasses = listOf(
      "software.sava.core.rpc.*Test*",
      "software.sava.core.programs.*Test*",
      "software.sava.core.serial.*Test*",
      "software.sava.core.zk.*Test*"
    )
    targetTests = "software.sava.core.rpc.*Test*,software.sava.core.programs.*Test*,software.sava.core.serial.*Test*,software.sava.core.zk.*Test*"
  }
  mutation.register("decimal") {
    // lamport and token amount conversion: a shift in the wrong direction or by
    // the wrong exponent is off by a factor of a billion and still looks like a
    // plausible balance
    targetClasses = listOf("software.sava.core.util.*")
    excludedClasses = listOf("software.sava.core.util.*Test*")
    targetTests = "software.sava.core.util.*Test*"
    // fired in the 2026-07-22 NAKED_RECEIVER trial (HARDENING_NOTES.md)
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
    // deliberately plain STRONGER otherwise: EXPERIMENTAL_BIG_DECIMAL only rewrites the
    // (BigDecimal)BigDecimal arithmetic methods — add/subtract/multiply/divide/
    // remainder/min/max/abs/negate/plus — and never the (int)BigDecimal shifts
    // this package is built on, so enabling it here generates nothing. The
    // shift direction is pinned by tests instead.
  }
  // PIT's default per-test allowance is `recorded time x 1.25 + 4000ms`, and every
  // hanging-mutant detection pays that flat fee. No test in this module's suites runs
  // longer than ~0.2s (the ranking is in HARDENING_NOTES.md), so the constant is cut
  // and the proportional headroom raised instead — load inflates a test in proportion
  // to its own runtime. Watch for SURVIVED -> TIMED_OUT drift in the verify output if
  // this is ever retuned; that is the signal the constant went too low.
  mutation.configureEach {
    timeoutFactor = 2.0
    timeoutConst = 1500L
  }

  fuzz.register("base58") {
    targetClass = "software.sava.core.encoding.Base58Fuzz"
    // every interesting Base58 boundary lives in small inputs; beyond this the O(n^2)
    // codec only burns executions per second
    maxLen = 256
    // NOT a bootstrap corpus — base58 is ASCII text, so a mutator reaches valid inputs
    // from scratch in seconds and these seeds buy no coverage the fuzzer cannot find.
    // They are the regression half: a committed corpus is where a future finding lands
    // (AGENTS.md: a finding is closed by a seed *and* a named test), and it is replayed
    // by 'check' in milliseconds rather than only when someone runs a campaign
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/base58")
  }
  fuzz.register("borsh") {
    targetClass = "software.sava.core.borsh.BorshFuzz"
    // shallow structure: a u32 length prefix then elements; every boundary lives in small
    // inputs, and valid prefixes are reachable from scratch — so, as with base58, the
    // corpus below is for regression replay inside 'check', not for bootstrapping
    maxLen = 1024
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/borsh")
  }
  fuzz.register("token2022") {
    targetClass = "software.sava.core.accounts.token.Token2022Fuzz"
    // real mints with metadata run a few hundred bytes; every TLV boundary case lives in
    // small inputs
    maxLen = 2048
    // the PYUSD mint (8 extensions incl. TokenMetadata) and a confidential token account:
    // a from-scratch mutator would take a long time to assemble a valid TLV chain
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/token2022")
  }
  fuzz.register("ed25519") {
    targetClass = "software.sava.core.crypto.ed25519.Ed25519Fuzz"
    // one input is one 32-byte point encoding and one keygen seed; anything longer
    // is truncated by the harness, so cap the mutator at exactly that
    maxLen = 32
    // NOT a bootstrap corpus — the structurally interesting subspaces (small-order
    // points, the 19 non-canonical encodings, boundary y values) are finite and
    // reachable by mutation from these seeds in seconds. They are the regression
    // half: a home for findings, replayed by 'check'. The campaign's value is
    // volume — carry and recoding bugs in the limb arithmetic have no branch
    // signature for coverage to steer by, only differential disagreement at depth
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/ed25519")
  }
  fuzz.register("ed25519Jdk") {
    targetClass = "software.sava.core.crypto.ed25519.Ed25519JdkFuzz"
    // the complete input domain is one RFC 8032 private-key seed
    maxLen = 32
    // Keep this corpus independent from fuzzEd25519 so minimizing either target
    // cannot remove a seed that only contributes to the other's oracle paths.
    // SunEC owns key derivation, not Solana's PDA membership predicate.
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/ed25519-jdk")
  }
  fuzz.register("txSkeleton") {
    targetClass = "software.sava.core.tx.TransactionSkeletonFuzz"
    // transactions cap at 1232 bytes on-chain; a little headroom lets the fuzzer probe
    // over-long inputs without wasting executions on the megabyte tail
    maxLen = 1500
    // real legacy + versioned(lookup-table) transactions: the header/offset/length
    // agreement can't be reached from scratch by a mutator
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/txSkeleton")
  }
}

// Mutator-trial hook (shared HARDENING.md: "trial per suite, enable only what
// fires"): -PtrialMutators=STRONGER,EXPERIMENTAL_X overrides every suite for a run.
// Trial results are recorded in HARDENING_NOTES.md ("Mutator-set trials").
providers.gradleProperty("trialMutators").orNull?.let { trial ->
  hardening.mutation.configureEach { mutators = trial }
}
