plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("encoding") {
    targetClasses = listOf(
      "software.sava.core.encoding.Base58",
      "software.sava.core.encoding.ByteUtil",
      "software.sava.core.encoding.CompactU16Encoding",
      "software.sava.core.encoding.Jex"
    )
    targetTests = "software.sava.core.encoding.*Test*"
  }
  fuzz.register("base58") {
    targetClass = "software.sava.core.encoding.Base58Fuzz"
    // every interesting Base58 boundary lives in small inputs; beyond this the O(n^2)
    // codec only burns executions per second
    maxLen = 256
  }
}
