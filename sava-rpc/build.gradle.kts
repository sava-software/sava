plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("jdk.httpserver")
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("responses") {
    // the hand-rolled json_iterator field predicates parse whatever an RPC node returns;
    // a compromised or buggy provider is the threat model
    targetClasses = listOf("software.sava.rpc.json.http.response.*")
    // the whitebox parser tests live inside the targeted package
    excludedClasses = listOf("software.sava.rpc.json.http.response.*Tests")
    targetTests = "software.sava.rpc.json.http.client.*Test*,software.sava.rpc.json.http.response.*Test*"
  }
}

configurations.all {
  resolutionStrategy {
    force("software.sava:json-iterator:25.3.0")
  }
}

dependencies {
  project(":sava-core")
}
