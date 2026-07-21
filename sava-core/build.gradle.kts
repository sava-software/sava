plugins {
  id("software.sava.build.feature.hardening")
}

// AGENTS.md names the mutation suites so an agent knows which one owns the code it
// touched. That list is derivable from the registrations below, so it drifts silently
// whenever a suite is added — it already did. Fail the build instead of relying on a
// prose reminder.
val docsInSync = tasks.register("docsInSync") {
  group = "verification"
  description = "Fails when a registered mutation suite is not named in AGENTS.md."
  val agentsDoc = rootProject.layout.projectDirectory.file("AGENTS.md").asFile
  // resolved at configuration time so the task stays configuration-cache friendly
  val suiteTasks = hardening.mutation.names.map { "pitest" + it.replaceFirstChar(Char::uppercase) }
  val projectPath = project.path
  inputs.file(agentsDoc)
  inputs.property("suites", suiteTasks)
  doLast {
    val doc = agentsDoc.readText()
    val missing = suiteTasks.filterNot(doc::contains)
    if (missing.isNotEmpty()) {
      throw GradleException(
        "AGENTS.md does not mention ${missing.size} mutation suite(s) registered by $projectPath:\n" +
            missing.joinToString("\n") { "  $it" } +
            "\nAdd them to the 'Quality gate & mutation ratchet' section, with the package each covers."
      )
    }
  }
}

// Wired into check, not just qualityGate: CI deliberately runs only check (the
// serialized PIT suites are the cost being avoided, and this task reads two
// files), so without this the doc drift it exists to catch would go unenforced
// between releases.
tasks.named("check") { dependsOn(docsInSync) }
tasks.named("qualityGate") { dependsOn(docsInSync) }

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
    // DELIBERATE DEVIATION from the package-wildcard rule: this suite allowlists
    // the Subsequence pair instead of taking software.sava.core.accounts.vanity.*.
    // The workers search in an unbounded loop, so every mutant that breaks the
    // match predicate runs to the PIT timeout instead of failing fast. PIT scores
    // TIMED_OUT as killed so the ratchet stays correct, but a whole-package suite
    // would cost a timeout window per such mutant. The mask logic — where a wrong
    // answer is silent rather than a hang — is here; the workers stay covered by
    // MaskWorkerTests without being mutated.
    targetClasses = listOf("software.sava.core.accounts.vanity.Subsequence*")
    // SubsequenceTests itself matches that prefix
    excludedClasses = listOf("software.sava.core.accounts.vanity.*Test*")
    targetTests = "software.sava.core.accounts.vanity.SubsequenceTests"
  }
  mutation.register("decimal") {
    // lamport and token amount conversion: a shift in the wrong direction or by
    // the wrong exponent is off by a factor of a billion and still looks like a
    // plausible balance
    targetClasses = listOf("software.sava.core.util.*")
    excludedClasses = listOf("software.sava.core.util.*Test*")
    targetTests = "software.sava.core.util.*Test*"
    // deliberately plain STRONGER: EXPERIMENTAL_BIG_DECIMAL only rewrites the
    // (BigDecimal)BigDecimal arithmetic methods — add/subtract/multiply/divide/
    // remainder/min/max/abs/negate/plus — and never the (int)BigDecimal shifts
    // this package is built on, so enabling it here generates nothing. The
    // shift direction is pinned by tests instead.
  }
  fuzz.register("base58") {
    targetClass = "software.sava.core.encoding.Base58Fuzz"
    // every interesting Base58 boundary lives in small inputs; beyond this the O(n^2)
    // codec only burns executions per second
    maxLen = 256
  }
  fuzz.register("borsh") {
    targetClass = "software.sava.core.borsh.BorshFuzz"
    // shallow structure: a u32 length prefix then elements; every boundary lives in small
    // inputs, and valid prefixes are reachable from scratch so no seed corpus is needed
    maxLen = 1024
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
