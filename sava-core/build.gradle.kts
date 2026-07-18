plugins {
  id("software.sava.build.feature.hardening")
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
      "software.sava.core.borsh.*Fuzz"
    )
    targetTests = "software.sava.core.borsh.*Test*"
  }
  mutation.register("ed25519") {
    targetClasses = listOf("software.sava.core.crypto.ed25519.*")
    excludedClasses = listOf(
      "software.sava.core.crypto.ed25519.*Test*",
      "software.sava.core.crypto.ed25519.*Fuzz"
    )
    targetTests = "software.sava.core.crypto.ed25519.*Test*"
  }
  mutation.register("encoding") {
    targetClasses = listOf("software.sava.core.encoding.*")
    excludedClasses = listOf(
      "software.sava.core.encoding.*Test*",
      "software.sava.core.encoding.*Fuzz"
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
      "software.sava.core.tx.*Fuzz",
      "software.sava.core.accounts.lookup.*Test*"
    )
    targetTests = "software.sava.core.tx.*Test*,software.sava.core.accounts.lookup.*Test*"
  }
  mutation.register("token2022") {
    targetClasses = listOf("software.sava.core.accounts.token.*")
    targetTests = "software.sava.core.token.*Test*"
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
    targetClass = "software.sava.core.token.Token2022Fuzz"
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
