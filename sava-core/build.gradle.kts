plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("ed25519") {
    targetClasses = listOf(
      "software.sava.core.crypto.ed25519.Ed25519Util",
      "software.sava.core.crypto.ed25519.Scalar25519",
      "software.sava.core.crypto.ed25519.Codec"
    )
    targetTests = "software.sava.core.crypto.ed25519.*Test*"
  }
  mutation.register("encoding") {
    targetClasses = listOf(
      "software.sava.core.encoding.Base58",
      "software.sava.core.encoding.ByteUtil",
      "software.sava.core.encoding.CompactU16Encoding",
      "software.sava.core.encoding.Jex"
    )
    targetTests = "software.sava.core.encoding.*Test*"
  }
  mutation.register("tx") {
    targetClasses = listOf(
      "software.sava.core.tx.TransactionSkeleton",
      "software.sava.core.tx.TransactionSkeletonRecord",
      "software.sava.core.tx.TransactionRecord",
      "software.sava.core.tx.InstructionRecord",
      "software.sava.core.accounts.lookup.AddressLookupTable",
      "software.sava.core.accounts.lookup.AddressLookupTableRoot",
      "software.sava.core.accounts.lookup.AddressLookupTableOverlay",
      "software.sava.core.accounts.lookup.AddressLookupTableWithReverseLookup"
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
