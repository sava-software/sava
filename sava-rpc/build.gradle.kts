plugins {
  id("software.sava.build.feature.hardening")
  id("sava.docs-in-sync")
}

testModuleInfo {
  requires("jdk.httpserver")
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("client") {
    // body decoding, the JSON-RPC envelope gate, and request construction — all of
    // it reading or answering an untrusted node
    targetClasses = listOf("software.sava.rpc.json.http.client.*")
    // test sources share this package, and not all of them are named *Test* — the
    // wildcard picks up the drift check and the stub helpers unless they are named
    excludedClasses = listOf(
      "software.sava.rpc.json.http.client.*Test*",
      "software.sava.rpc.json.http.client.*Check*",
      "software.sava.rpc.json.http.client.Stub*",
      // a git-ignored local scratch driver; not part of the build contract
      "software.sava.rpc.json.http.client.Integ*"
    )
    targetTests = "software.sava.rpc.json.http.client.*Test*"
    // fluent receiver-returning calls (iterator chains, builder-style writes) are
    // invisible to VoidMethodCall; fired in the 2026-07-22 trial (HARDENING_NOTES.md)
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
  mutation.register("ws") {
    // subscription bookkeeping, reconnect throttling, and ping pacing; time-dependent
    // paths run against the NanoClock seam so tests advance time instead of waiting
    targetClasses = listOf("software.sava.rpc.json.http.ws.*")
    excludedClasses = listOf(
      // TestClock matches *Test*; the no-network fakes are named for their role
      "software.sava.rpc.json.http.ws.*Test*",
      "software.sava.rpc.json.http.ws.Recording*"
    )
    targetTests = "software.sava.rpc.json.http.ws.*Test*"
    // fired in the 2026-07-22 NAKED_RECEIVER trial (HARDENING_NOTES.md)
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
  mutation.register("responses") {
    // the hand-rolled json_iterator field predicates parse whatever an RPC node returns;
    // a compromised or buggy provider is the threat model
    targetClasses = listOf("software.sava.rpc.json.http.response.*")
    // the whitebox parser tests live inside the targeted package; trailing wildcard
    // so a helper nested in a test class stays excluded too
    excludedClasses = listOf("software.sava.rpc.json.http.response.*Tests*")
    targetTests = "software.sava.rpc.json.http.client.*Test*,software.sava.rpc.json.http.response.*Test*"
    // fired in the 2026-07-22 NAKED_RECEIVER trial (HARDENING_NOTES.md)
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
}

// Mutator-trial hook (shared HARDENING.md: "trial per suite, enable only what
// fires"): -PtrialMutators=STRONGER,EXPERIMENTAL_X overrides every suite for a run.
// Trial results are recorded in HARDENING_NOTES.md ("Mutator-set trials").
providers.gradleProperty("trialMutators").orNull?.let { trial ->
  hardening.mutation.configureEach { mutators = trial }
}
